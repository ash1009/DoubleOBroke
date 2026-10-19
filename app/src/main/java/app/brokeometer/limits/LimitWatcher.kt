package app.brokeometer.limits

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.brokeometer.BrokeApp
import app.brokeometer.R
import app.brokeometer.ui.MainActivity
import java.time.LocalDate

/** Shared between the parts of App limits running in the app. */
object LimitLive {
    /** Whether the watcher is running. */
    @Volatile var running = false
}

/**
 * The part of App limits that watches which app is in front. It reads the phone's own record of
 * apps opening (Usage access) about once a second while the screen is on, counts the app's time
 * today, warns before the limit, and puts up the block screen once it's used. It needs "Display over
 * other apps" for Android to let the block screen come up over another app.
 *
 * It uses no Accessibility, so banking apps that refuse to run alongside apps from outside Google
 * Play with Accessibility switched on (HSBC does) keep working.
 */
class LimitWatcher : Service() {

    private lateinit var app: BrokeApp
    private val store get() = app.container.limits
    private val usage get() = app.container.usage

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    /** The app in front, from the newest "opened" in the phone's record. */
    private var front: String? = null

    /** Everything up to here has been read from the phone's record. */
    private var readUpTo = 0L

    /** The app in front's time today, worked out when it came to the front ([countedAt]). */
    private var counted = 0L
    private var countedAt = 0L
    private var countedFor: String? = null
    private var countedDay: LocalDate? = null

    private var blockedPkg: String? = null
    private var blockedAt = 0L
    private var checkedRunAt = 0L
    private var ignore: Set<String> = emptySet()

    @Volatile private var screenOn = true

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }
            if (screenOn) handler.postDelayed(this, POLL_MS)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    handler.removeCallbacks(tick)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as BrokeApp
        ensureChannel(this)
        val keyboards = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        ignore = (keyboards + listOf("com.android.systemui", "android")).toSet()
        thread = HandlerThread("app-limits").also { it.start() }
        handler = Handler(thread.looper)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        LimitLive.running = true
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!showAsRunning()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LimitLive.running = false
        runCatching { unregisterReceiver(screen) }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    /** The small notification that keeps Android from stopping it. */
    private fun showAsRunning(): Boolean {
        val open = PendingIntent.getActivity(
            this, 71,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "limits").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("App limits is on")
            .setContentText("Closes apps once today's time is used.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }.isSuccess
    }

    private fun step() {
        val now = System.currentTimeMillis()
        // Every half minute: the day moves on, changes waiting for today take over, and it stops when not needed.
        if (now - checkedRunAt > 30_000L) {
            checkedRunAt = now
            store.refresh()
            if (!shouldRun(this)) {
                stopSelf()
                return
            }
        }
        val from = if (readUpTo == 0L) now - LOOK_BACK_MS else readUpTo - OVERLAP_MS
        val opened = usage.events(from, now).lastOrNull { it.kind == UsageEvent.Kind.RESUMED && it.pkg !in ignore }
        readUpTo = now
        if (opened != null && opened.pkg != front) {
            front = opened.pkg
            // Counted again when it comes back, so time spent elsewhere isn't added on.
            countedFor = null
        }
        check(now)
    }

    /** The app in front's time today: worked out once when it comes to the front, then counted on. */
    private fun usedNow(pkg: String, now: Long): Long {
        val today = LocalDate.now()
        if (countedFor != pkg || countedDay != today || now - countedAt > 15 * 60_000L) {
            counted = usage.usedToday(pkg)
            countedAt = now
            countedFor = pkg
            countedDay = today
        }
        return counted + (now - countedAt)
    }

    private fun check(now: Long) {
        if (!store.on.value) return
        val pkg = front ?: return
        // No limit or blocked hours for this app (or it's this one, or the block screen): nothing to do.
        val limit = store.limitFor(pkg)
        val inHours = store.hours.value.apps.any { it.pkg == pkg }
        if (limit == null && !inHours) return
        if (!autoTime(this)) {
            block(pkg, BlockActivity.REASON_CLOCK, now)
            return
        }
        if (inHours && store.closedNow(pkg)) {
            block(pkg, BlockActivity.REASON_HOURS, now)
            return
        }
        if (limit == null) return
        val left = LimitRules.allowedMs(limit, store.extraMinutes(pkg)) - usedNow(pkg, now)
        val warnMs = limit.warnMinutes * 60_000L
        if (limit.warnMinutes > 0 && left in 1..warnMs && !store.warned(pkg)) {
            store.markWarned(pkg)
            warn(limit, left)
        }
        if (left <= 0) block(pkg, BlockActivity.REASON_LIMIT, now)
    }

    /**
     * Puts the block screen over the app. Once it's up, it's the app in front, so this stops; if
     * Android didn't let it come up, it's tried again every few seconds and a notification says so.
     */
    private fun block(pkg: String, reason: String, now: Long) {
        if (pkg == blockedPkg && now - blockedAt < RETRY_MS) return
        val firstTry = pkg != blockedPkg || now - blockedAt > 60_000L
        blockedPkg = pkg
        blockedAt = now
        val i = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(BlockActivity.EXTRA_PKG, pkg)
            .putExtra(BlockActivity.EXTRA_REASON, reason)
        runCatching { startActivity(i) }
        if (!Settings.canDrawOverlays(this) && firstTry) blockedNote(pkg, i)
    }

    /** Without "Display over other apps" Android may keep the block screen back: a notification instead. */
    private fun blockedNote(pkg: String, i: Intent) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val label = store.limitFor(pkg)?.label ?: store.hours.value.apps.firstOrNull { it.pkg == pkg }?.label ?: usage.label(pkg)
        val open = PendingIntent.getActivity(this, 72, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hours = i.getStringExtra(BlockActivity.EXTRA_REASON) == BlockActivity.REASON_HOURS
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(if (hours) "$label is closed in blocked hours" else "$label is done for today")
            .setContentText("Allow Display over other apps in App limits, Setup, so it closes by itself.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(pkg.hashCode() + 1, n) }
    }

    private fun warn(limit: AppLimit, leftMs: Long) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            this, 70,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "limits").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val minutes = ((leftMs + 59_999) / 60_000).coerceAtLeast(1)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("${limit.label}: $minutes ${if (minutes == 1L) "minute" else "minutes"} left today")
            .setContentText("Today's limit is ${LimitRules.hmMinutes(limit.minutes + store.extraMinutes(limit.pkg))}.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(limit.pkg.hashCode(), n) }
    }

    companion object {
        const val CHANNEL = "limits"
        private const val CHANNEL_RUNNING = "limits_running"
        private const val NOTIFICATION_ID = 4201

        /** How often it looks at which app is in front, while the screen is on. */
        private const val POLL_MS = 1_000L

        /** On starting: how far back to look for the app already open. */
        private const val LOOK_BACK_MS = 60 * 60_000L

        /** A little overlap between looks, for anything noted a moment late. */
        private const val OVERLAP_MS = 2_000L

        /** How long to give the block screen to come up before trying again. */
        private const val RETRY_MS = 3_000L

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "App limits", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "A heads-up before an app's daily limit."
                    },
                )
            }
            if (nm.getNotificationChannel(CHANNEL_RUNNING) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_RUNNING, "App limits running", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Shows while App limits watches for apps past today's limit."
                    },
                )
            }
        }

        /** On, with an app on the list (or in blocked hours) and Usage access given. */
        fun shouldRun(context: Context): Boolean {
            val app = context.applicationContext as BrokeApp
            val store = app.container.limits
            return store.on.value && (store.limits.value.isNotEmpty() || store.hours.value.apps.isNotEmpty()) && app.container.usage.allowed()
        }

        /** Starts or stops the watcher, as App limits' settings say. */
        fun sync(context: Context) {
            val intent = Intent(context, LimitWatcher::class.java)
            if (shouldRun(context)) {
                if (!LimitLive.running) runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }

        /** Whether "Display over other apps" is allowed, so the block screen can come up over another app. */
        fun overlayAllowed(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun overlayIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

        fun adminActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(context, LimitAdmin::class.java))

        fun batteryFree(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

        /** Whether the phone sets its own clock, so the day can't be moved on by hand. */
        fun autoTime(context: Context): Boolean =
            Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 1

        fun batteryIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    }
}

/** After the phone restarts, or the app is updated, App limits carries on if it was on. */
class LimitBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LimitWatcher.sync(context)
        }
    }
}
