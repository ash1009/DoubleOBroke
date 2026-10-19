package app.brokeometer.limits

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** An app closed during blocked hours. */
data class HoursApp(val pkg: String, val label: String)

/**
 * Blocked hours: a fixed time of day (say 00:00 to 07:00) when the apps on its list stay closed,
 * whatever time they have left. There's no way out. [start] and [end] are minutes after midnight;
 * an [end] before [start] runs past midnight.
 */
data class BlockedHours(
    val start: Int = 0,
    val end: Int = 7 * 60,
    val apps: List<HoursApp> = emptyList(),
    /** A loosening change, waiting for its time. */
    val next: HoursChange? = null,
)

/**
 * A loosening change to blocked hours. It takes over at [from] (epoch ms): new times, when set,
 * and apps coming off the list.
 */
data class HoursChange(val from: Long, val start: Int? = null, val end: Int? = null, val remove: Set<String> = emptySet())

/** The rules of blocked hours, with no Android in them so they can be tested on their own. */
object HoursRules {

    const val DAY = 24 * 60

    /** Whether [minute] (after midnight) falls in the hours from [start] to [end]. */
    fun covers(start: Int, end: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    /** Whether the app [pkg] is closed at [time]. */
    fun closed(h: BlockedHours, pkg: String, time: LocalTime): Boolean =
        h.apps.any { it.pkg == pkg } && covers(h.start, h.end, time.hour * 60 + time.minute)

    /** Whether going from the hours [oldStart] to [oldEnd] to new ones frees up any time. */
    fun timesLoosen(oldStart: Int, oldEnd: Int, start: Int, end: Int): Boolean =
        (0 until DAY).any { covers(oldStart, oldEnd, it) && !covers(start, end, it) }

    /**
     * When a loosening change asked for at [now] takes over: tomorrow, but never part way through
     * blocked hours. Hours that are on at midnight (00:00 to 07:00, or 23:00 to 07:00) run to their
     * end under the old rules first.
     */
    fun changeFrom(h: BlockedHours, now: LocalDateTime, zone: ZoneId): Long {
        val midnight = now.toLocalDate().plusDays(1)
        val at = if (covers(h.start, h.end, 0)) midnight.atTime(h.end / 60, h.end % 60) else midnight.atStartOfDay()
        return at.atZone(zone).toInstant().toEpochMilli()
    }

    /** The hours as they stand at [now] (epoch ms), with a waiting change taking over once its time comes. */
    fun at(h: BlockedHours, now: Long): BlockedHours {
        val c = h.next ?: return h
        if (now < c.from) return h
        return BlockedHours(
            start = c.start ?: h.start,
            end = c.end ?: h.end,
            apps = h.apps.filterNot { it.pkg in c.remove },
        )
    }

    /** Adds apps: straight away. An app waiting to come off stays on instead. */
    fun add(h: BlockedHours, apps: List<HoursApp>): BlockedHours {
        val have = h.apps.map { it.pkg }.toSet()
        val added = h.apps + apps.filter { it.pkg !in have }.distinctBy { it.pkg }
        val next = h.next?.let { c -> c.copy(remove = c.remove - apps.map { it.pkg }.toSet()) }?.takeUnless { it.isEmpty() }
        return h.copy(apps = added.sortedBy { it.label.lowercase() }, next = next)
    }

    /** New times that only add blocked time: straight away, and any waiting change of times is dropped. */
    fun widen(h: BlockedHours, start: Int, end: Int): BlockedHours {
        val next = h.next?.copy(start = null, end = null)?.takeUnless { it.isEmpty() }
        return h.copy(start = start, end = end, next = next)
    }

    /** Adds a loosening change, taking over at [from]. Anything already waiting waits with it. */
    fun loosen(h: BlockedHours, from: Long, start: Int? = null, end: Int? = null, remove: Set<String> = emptySet()): BlockedHours {
        val old = h.next
        val next = HoursChange(
            from = maxOf(from, old?.from ?: 0L),
            start = start ?: old?.start,
            end = end ?: old?.end,
            remove = (old?.remove ?: emptySet()) + remove,
        )
        return h.copy(next = next)
    }

    private fun HoursChange.isEmpty() = start == null && end == null && remove.isEmpty()

    /** "07:00". */
    fun clock(minute: Int): String {
        val m = ((minute % DAY) + DAY) % DAY
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** A time [steps] quarter hours on (or back), round the clock. */
    fun step(minute: Int, steps: Int): Int = ((minute + steps * 15) % DAY + DAY) % DAY

    /** "Today" or "Tomorrow" or the date, then the time, for when a change takes over. */
    fun whenText(from: Long, zone: ZoneId, today: LocalDate): String {
        val at = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDateTime()
        val day = when (at.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> at.toLocalDate().toString()
        }
        return "$day at ${clock(at.hour * 60 + at.minute)}"
    }
}
