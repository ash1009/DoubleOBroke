package app.brokeometer.limits

import java.time.LocalDate

/** One app with a daily limit. */
data class AppLimit(
    val pkg: String,
    val label: String,
    /** Minutes a day. */
    val minutes: Int,
    /** Minutes before the limit for a heads-up notification; 0 for none. */
    val warnMinutes: Int = 10,
    /** Whether the block screen offers waiting for [LimitRules.EXTRA_MINUTES] more minutes. */
    val wayOut: Boolean = true,
    /** A change that loosens this limit, waiting for its day. */
    val next: Change? = null,
)

/** A loosening change to an app's limit; it starts on [from] (yyyy-MM-dd). [remove] takes the app off the list. */
data class Change(val from: String, val minutes: Int, val wayOut: Boolean, val remove: Boolean = false)

/**
 * The rules of App limits, with no Android in them so they can be tested on their own. Making a
 * limit stricter happens at once; loosening one (more time, turning a way out back on, taking an
 * app off the list) only starts the next day, after a wait.
 */
object LimitRules {

    /** Minutes added by the way out on the block screen. */
    const val EXTRA_MINUTES = 10

    /** The wait for the first way out of the day, in minutes; it doubles each time after. */
    private const val FIRST_WAIT = 1
    private const val LONGEST_WAIT = 60

    /** The wait to loosen a limit, and to turn App limits off, in minutes. */
    const val LOOSEN_WAIT = 10
    const val TURN_OFF_WAIT = 30

    /** What has to be typed, exactly, to loosen a limit. */
    const val PROMISE = "I am choosing more time on my phone over the things I said matter more to me."

    /** The warnings you can pick from, minutes before the limit (0 is none). */
    val WARNINGS = listOf(0, 5, 10, 15)

    /** Minutes to wait for the way out when it has been used [used] times today: 1, 2, 4, 8 and so on. */
    fun wayOutWait(used: Int): Int = (FIRST_WAIT shl used.coerceIn(0, 10)).coerceAtMost(LONGEST_WAIT)

    /** A quarter less than your usual day, to the 15 minutes below, and never under 15 minutes. */
    fun suggest(typicalMs: Long?): Int {
        if (typicalMs == null || typicalMs <= 0) return 60
        val minutes = typicalMs / 60_000.0 * 0.75
        return ((minutes / 15).toInt() * 15).coerceAtLeast(15)
    }

    /** Whether going from [old] to [minutes] and [wayOut] gives you more room. */
    fun loosens(old: AppLimit, minutes: Int, wayOut: Boolean): Boolean = minutes > old.minutes || (wayOut && !old.wayOut)

    /** The limit as it stands on [day], with a waiting change taking over once its day comes; null once removed. */
    fun onDay(limit: AppLimit, day: LocalDate): AppLimit? {
        val c = limit.next ?: return limit
        if (LocalDate.parse(c.from).isAfter(day)) return limit
        if (c.remove) return null
        return limit.copy(minutes = c.minutes, wayOut = c.wayOut, next = null)
    }

    /** Time allowed today, with any way-out minutes earned. */
    fun allowedMs(limit: AppLimit, extraMinutes: Int): Long = (limit.minutes + extraMinutes) * 60_000L

    /** Whether the typed [text] is the promise: the same words, ignoring capitals and spacing. */
    fun promised(text: String): Boolean = norm(text) == norm(PROMISE)

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** "3 h", "1 h 20 m", "45 m". */
    fun hm(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        val h = m / 60
        val r = m % 60
        return when {
            h == 0L -> "$r m"
            r == 0L -> "$h h"
            else -> "$h h $r m"
        }
    }

    fun hmMinutes(minutes: Int): String = hm(minutes * 60_000L)
}

/** What the phone noted: an app's screen came to the front or went away, or the screen went off. */
data class UsageEvent(val at: Long, val pkg: String, val cls: String?, val kind: Kind) {
    enum class Kind { RESUMED, PAUSED, SCREEN_OFF, SHUTDOWN }
}

/** Time on screen for each app, from what the phone noted. */
object UsageMath {

    /**
     * Milliseconds each app was on screen between [from] and [to]. One app is on screen at a time: an
     * app is on screen from when one of its screens comes to the front until its last one goes, another
     * app comes to the front, or the screen goes off. Still on screen at [to] counts up to [to].
     */
    fun foreground(events: List<UsageEvent>, from: Long, to: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        var openPkg: String? = null
        var openAt = 0L
        val screens = HashSet<String>()
        fun close(at: Long) {
            val p = openPkg ?: return
            val s = maxOf(openAt, from)
            val e = minOf(at, to)
            if (e > s) out[p] = (out[p] ?: 0L) + (e - s)
            openPkg = null
            screens.clear()
        }
        for (ev in events.sortedBy { it.at }) {
            if (ev.at > to) break
            when (ev.kind) {
                UsageEvent.Kind.RESUMED -> {
                    if (openPkg != ev.pkg) {
                        close(ev.at)
                        openPkg = ev.pkg
                        openAt = ev.at
                    }
                    screens += ev.cls.orEmpty()
                }
                UsageEvent.Kind.PAUSED -> if (ev.pkg == openPkg) {
                    screens -= ev.cls.orEmpty()
                    if (screens.isEmpty()) close(ev.at)
                }
                UsageEvent.Kind.SCREEN_OFF, UsageEvent.Kind.SHUTDOWN -> close(ev.at)
            }
        }
        close(to)
        return out
    }

    /**
     * An app's usual day from [days] (each day's time per app). Only days the phone noted anything
     * count, so days before the app was installed, or before the phone kept a record, don't drag it down.
     */
    fun typical(days: List<Map<String, Long>>, pkg: String): Long? {
        val known = days.filter { it.isNotEmpty() }
        if (known.isEmpty()) return null
        return known.sumOf { it[pkg] ?: 0L } / known.size
    }
}
