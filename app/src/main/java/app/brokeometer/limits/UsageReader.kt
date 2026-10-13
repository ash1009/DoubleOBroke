package app.brokeometer.limits

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** An app on the phone you can open. */
data class PhoneApp(val pkg: String, val label: String)

/**
 * Screen time from the phone's own record (needs Usage access). Past days are worked out once and
 * kept, so the usual day still knows them after the phone's record of single events has moved on.
 */
class UsageReader(private val context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val sp = context.getSharedPreferences("usage_days", Context.MODE_PRIVATE)
    private val icons = HashMap<String, Bitmap?>()

    fun allowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun events(from: Long, to: Long): List<UsageEvent> {
        if (!allowed()) return emptyList()
        val out = ArrayList<UsageEvent>()
        val list = runCatching { usm.queryEvents(from, to) }.getOrNull() ?: return out
        val e = UsageEvents.Event()
        while (list.hasNextEvent()) {
            list.getNextEvent(e)
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Kind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEvent.Kind.PAUSED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> UsageEvent.Kind.SCREEN_OFF
                UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEvent.Kind.SHUTDOWN
                else -> null
            } ?: continue
            out += UsageEvent(e.timeStamp, e.packageName, e.className, kind)
        }
        return out
    }

    private fun zone() = ZoneId.systemDefault()

    private fun startOf(day: LocalDate) = day.atStartOfDay(zone()).toInstant().toEpochMilli()

    /** Time on screen per app between [from] and [to], looking a little earlier for an app already open at [from]. */
    fun between(from: Long, to: Long): Map<String, Long> =
        UsageMath.foreground(events(from - LOOK_BACK_MS, to), from, to)

    /** Today so far, per app. */
    fun today(now: Long = System.currentTimeMillis()): Map<String, Long> = between(startOf(LocalDate.now()), now)

    fun usedToday(pkg: String): Long = today()[pkg] ?: 0L

    /** The last [n] whole days, oldest first, each as time per app (empty for a day the phone has no record of). */
    @Synchronized
    fun days(n: Int = 7): List<Map<String, Long>> {
        val today = LocalDate.now()
        val saved = runCatching { JSONObject(sp.getString(KEY, "{}")!!) }.getOrDefault(JSONObject())
        val out = (n downTo 1).map { back ->
            val d = today.minusDays(back.toLong())
            val key = d.toString()
            saved.optJSONObject(key)?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } ?: run {
                val map = if (allowed()) between(startOf(d), startOf(d.plusDays(1))) else emptyMap()
                if (allowed()) saved.put(key, JSONObject(map as Map<*, *>))
                map
            }
        }
        // Keep five weeks.
        val oldest = today.minusDays(35).toString()
        saved.keys().asSequence().toList().filter { it < oldest }.forEach { saved.remove(it) }
        sp.edit().putString(KEY, saved.toString()).apply()
        return out
    }

    /** Each app's usual day over the last week. */
    fun typical(): Map<String, Long> {
        val days = days(7)
        val pkgs = days.flatMap { it.keys }.toSet()
        return pkgs.mapNotNull { p -> UsageMath.typical(days, p)?.let { p to it } }.toMap()
    }

    /** The home screen app: its time isn't screen time. */
    fun launcher(): String? = context.packageManager
        .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName

    private var appsCache: Pair<Long, List<PhoneApp>>? = null

    /** Apps you can open from the home screen, apart from this one, by name. */
    @Synchronized
    fun phoneApps(): List<PhoneApp> {
        appsCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return list }
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .map { PhoneApp(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
            .also { appsCache = System.currentTimeMillis() to it }
    }

    fun label(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    @Synchronized
    fun icon(pkg: String): Bitmap? = icons.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
    }

    /** Not counted as screen time: the home screen, and the phone's own bars. */
    fun notScreenTime(): Set<String> = setOfNotNull(launcher(), "com.android.systemui", "android")

    companion object {
        private const val KEY = "days"
        private const val LOOK_BACK_MS = 6 * 60 * 60 * 1000L
    }
}
