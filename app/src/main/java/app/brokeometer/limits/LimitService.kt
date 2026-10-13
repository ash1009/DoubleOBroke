package app.brokeometer.limits

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.brokeometer.BrokeApp
import app.brokeometer.R
import app.brokeometer.ui.MainActivity
import java.time.LocalDate

/** Shared between the parts of App limits running in the app: a pass into Instagram's chats. */
object LimitLive {
    /** When an Instagram message notification was tapped. */
    @Volatile var notificationTapAt = 0L

    /** When Messages only was tapped on the block screen. */
    @Volatile var messagesOnlyAt = 0L

    /** Whether the Accessibility part is running. */
    @Volatile var running = false
}

/**
 * The part of App limits that watches which app is in front (Accessibility). It counts an app's time
 * today from the phone's record, warns before the limit, and puts up the block screen once it's
 * used. After Instagram's limit it can let you into chats only. While on, it also keeps you out of
 * the Settings pages that would switch it off ([Guard]).
 */
class LimitService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: BrokeApp
    private val store get() = app.container.limits
    private val usage get() = app.container.usage

    /** The app in front, and since when. */
    private var front: String? = null

    /** The app in front's time today, worked out when it came to the front ([countedAt]). */
    private var counted = 0L
    private var countedAt = 0L
    private var countedFor: String? = null
    private var countedDay: LocalDate? = null

    /** Waited a moment for a notification tap to land before blocking Instagram. */
    private var waitedForPass = false

    private var chat: ChatVisit? = null
    private var lookQueued = false
    private var lastLeft = 0L
    private var ignore: Set<String> = emptySet()

    private val tick = Runnable { check() }
    private val look = Runnable {
        lookQueued = false
        lookAtScreen()
    }

    /** A visit to Instagram's chats after the day's time is used. */
    private class ChatVisit(val startedAt: Long, val fromButton: Boolean) {
        var sends = 0
        /** Time on screens the app doesn't recognise, and since when the current one. */
        var unknownMs = 0L
        var unknownSince: Long? = null
        var screen: InstaScreen = InstaScreen.UNKNOWN
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        app = application as BrokeApp
        LimitLive.running = true
        val keyboards = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        ignore = (keyboards + listOf("com.android.systemui", "android")).toSet()
        ensureChannel(this)
    }

    override fun onDestroy() {
        LimitLive.running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg !in ignore && pkg != front) moved(pkg)
                if (pkg == front && watching(pkg)) queueLook()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == front && watching(pkg)) queueLook()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (pkg == InstaScreens.PACKAGE) {
                val visit = chat ?: return
                val n = e.source ?: return
                if (InstaScreens.isSend(ScreenNode(n.viewIdResourceName, desc = n.contentDescription?.toString()))) visit.sends++
            }
        }
    }

    private fun watching(pkg: String) = pkg == InstaScreens.PACKAGE || pkg in Guard.SETTINGS

    private fun moved(pkg: String) {
        if (front == InstaScreens.PACKAGE && pkg != InstaScreens.PACKAGE) chat = null
        front = pkg
        waitedForPass = false
        handler.removeCallbacks(tick)
        check()
        if (watching(pkg)) queueLook()
    }

    private fun queueLook() {
        if (lookQueued) return
        lookQueued = true
        handler.postDelayed(look, 300)
    }

    /** The app in front's time today: worked out once when it comes to the front, then counted on. */
    private fun usedNow(pkg: String): Long {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        if (countedFor != pkg || countedDay != today || now - countedAt > 15 * 60_000L) {
            counted = usage.usedToday(pkg)
            countedAt = now
            countedFor = pkg
            countedDay = today
        }
        return counted + (now - countedAt)
    }

    private fun check() {
        handler.removeCallbacks(tick)
        if (!::app.isInitialized) return
        store.refresh()
        if (!store.on.value) return
        val pkg = front ?: return
        // No limit for this app: nothing to count.
        val limit = store.limitFor(pkg) ?: return
        if (!autoTime(this)) {
            block(pkg, BlockActivity.REASON_CLOCK)
            return
        }
        val now = System.currentTimeMillis()
        val left = LimitRules.allowedMs(limit, store.extraMinutes(pkg)) - usedNow(pkg)
        val warnMs = limit.warnMinutes * 60_000L
        if (limit.warnMinutes > 0 && left in 1..warnMs && !store.warned(pkg)) {
            store.markWarned(pkg)
            warn(limit, left)
        }
        if (left <= 0) {
            if (pkg == InstaScreens.PACKAGE) instagramOver(now) else block(pkg, BlockActivity.REASON_LIMIT)
            return
        }
        val next = listOf(left, left - warnMs).filter { it > 0 }.minOrNull() ?: left
        handler.postDelayed(tick, next.coerceAtMost(60_000L) + 500)
    }

    /** Instagram with today's time used: chats only, if a message tap or Messages only let you in. */
    private fun instagramOver(now: Long) {
        val visit = chat
        if (visit != null) {
            queueLook()
            handler.postDelayed(tick, 15_000)
            return
        }
        val fromTap = store.chatsFromNotifications.value && now - LimitLive.notificationTapAt < PASS_MS
        val fromButton = store.messagesButton.value && now - LimitLive.messagesOnlyAt < PASS_MS
        if (fromTap || fromButton) {
            LimitLive.notificationTapAt = 0
            LimitLive.messagesOnlyAt = 0
            chat = ChatVisit(now, fromButton)
            queueLook()
            handler.postDelayed(tick, 15_000)
            return
        }
        // The notification's tap can be noted a moment after Instagram opens.
        if (!waitedForPass && store.chatsFromNotifications.value) {
            waitedForPass = true
            handler.postDelayed(tick, 900)
            return
        }
        block(InstaScreens.PACKAGE, BlockActivity.REASON_LIMIT)
    }

    private fun lookAtScreen() {
        if (!::app.isInitialized || !store.on.value) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg in Guard.SETTINGS) {
            guard(pkg, root)
            return
        }
        if (pkg != InstaScreens.PACKAGE) return
        // Before the limit, Instagram's screens are only read when you've asked for them to be noted.
        if (chat == null && !store.recording.value) return
        val nodes = ArrayList<ScreenNode>()
        collect(root, nodes, 0)
        val screen = InstaScreens.classify(nodes)
        if (store.recording.value) Recorder.add(this, screen, nodes, chat != null)
        val visit = chat ?: return
        val now = System.currentTimeMillis()
        // Time on unrecognised screens adds up; a recognised one stops the clock.
        visit.unknownSince?.let { visit.unknownMs += now - it }
        visit.unknownSince = if (screen == InstaScreen.UNKNOWN) now else null
        visit.screen = screen
        when {
            InstaScreens.chatAllowed(screen) -> Unit
            // Switching accounts lands on the other account's profile: on to its messages instead.
            screen == InstaScreen.PROFILE -> clickMessages(root)
            // Messages only opens Instagram, which can show the feed first.
            screen == InstaScreen.FEED && visit.fromButton && now - visit.startedAt < 8_000 -> clickMessages(root)
            screen == InstaScreen.UNKNOWN -> {
                val budget = (FALLBACK_MINUTES + SEND_MINUTES * visit.sends) * 60_000L
                if (visit.unknownMs > budget) {
                    chat = null
                    block(InstaScreens.PACKAGE, BlockActivity.REASON_CHATS)
                } else {
                    handler.postDelayed({ queueLook() }, 20_000)
                }
            }
            else -> {
                chat = null
                block(InstaScreens.PACKAGE, BlockActivity.REASON_CHATS)
            }
        }
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<ScreenNode>, depth: Int) {
        if (out.size > 1500 || depth > 45) return
        out += ScreenNode(
            id = n.viewIdResourceName,
            cls = n.className?.toString(),
            selected = n.isSelected,
            editable = n.isEditable,
            hint = if (n.isEditable) n.hintText?.toString() else null,
            desc = if (n.isClickable || n.viewIdResourceName?.endsWith("_tab") == true) n.contentDescription?.toString() else null,
        )
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private fun clickMessages(root: AccessibilityNodeInfo) {
        val target = find(root, 0) { InstaScreens.isMessagesButton(ScreenNode(it.viewIdResourceName, desc = it.contentDescription?.toString())) }
            ?: return
        var n: AccessibilityNodeInfo? = target
        while (n != null && !n.isClickable) n = n.parent
        (n ?: target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun find(n: AccessibilityNodeInfo, depth: Int, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (depth > 45) return null
        if (test(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            find(c, depth + 1, test)?.let { return it }
        }
        return null
    }

    /** In Settings: leave a page that would switch App limits off. */
    private fun guard(pkg: String, root: AccessibilityNodeInfo) {
        if (store.limits.value.isEmpty()) return
        val texts = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (texts.size > 400 || depth > 40) return
            n.text?.let { texts += it.toString() }
            n.contentDescription?.let { texts += it.toString() }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (!Guard.shouldLeave(pkg, texts, getString(R.string.app_name), granted(this))) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        if (now - lastLeft > 4_000) {
            lastLeft = now
            Toast.makeText(this, "App limits is on. Turn it off in Double O Broke (a 30 minute wait).", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Puts the block screen over the app. Should Android not show it, the home screen comes up
     * instead (and the block screen is tried again), so the app is never left open.
     */
    private fun block(pkg: String, reason: String) {
        handler.removeCallbacks(tick)
        val i = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(BlockActivity.EXTRA_PKG, pkg)
            .putExtra(BlockActivity.EXTRA_REASON, reason)
        runCatching { startActivity(i) }
        handler.postDelayed({
            if (!BlockActivity.showing && front == pkg) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({ runCatching { startActivity(i) } }, 800)
            }
        }, 1_500)
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

        /** How long a notification tap or Messages only lets you into Instagram's chats for. */
        private const val PASS_MS = 15_000L

        /** On screens it can't recognise, a chat visit gets this long, plus a bit for each message sent. */
        private const val FALLBACK_MINUTES = 5
        private const val SEND_MINUTES = 2

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "App limits", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A heads-up before an app's daily limit."
                },
            )
        }

        fun component(context: Context) = ComponentName(context, LimitService::class.java)

        /** Whether App limits' Accessibility switch is on. */
        fun enabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = component(context)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun adminActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(context, LimitAdmin::class.java))

        fun notificationsAllowed(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun batteryFree(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

        /** Whether the phone sets its own clock, so the day can't be moved on by hand. */
        fun autoTime(context: Context): Boolean =
            Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 1

        fun granted(context: Context): Set<Guard.Perm> = buildSet {
            val app = context.applicationContext as BrokeApp
            if (app.container.usage.allowed()) add(Guard.Perm.USAGE)
            if (enabled(context)) add(Guard.Perm.ACCESSIBILITY)
            if (notificationsAllowed(context)) add(Guard.Perm.NOTIFICATIONS)
            if (adminActive(context)) add(Guard.Perm.ADMIN)
        }

        fun batteryIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    }
}
