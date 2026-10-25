package app.brokeometer.tasks

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * When tasks are due and when you hear about them, with no Android in it. Each evening at a set time
 * one summary lists what's due tomorrow; a task with a time also gets its own reminder a day before. A
 * task found (in an email or a note) after its summary went gets a notice of its own, so none is missed.
 */
object TaskRules {

    /** The evening summary, in minutes after midnight: 20:00 to start with. */
    const val DEFAULT_SUMMARY_AT = 20 * 60

    /** How far ahead the calendar is read. */
    const val CALENDAR_DAYS = 30L

    /** How far back emails are looked through once, when Tasks starts. */
    const val BACKFILL_DAYS = 14L

    /** "2026-10-27" as a day, or null. */
    fun day(text: String?): LocalDate? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** "09:30" or "9:30 pm" as minutes after midnight, or null. */
    fun time(text: String?): Int? {
        val t = text?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val m = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?$""").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val half = m.groupValues[3]
        if (half.isEmpty() && m.groupValues[2].isEmpty()) return null
        if (half == "pm" && h < 12) h += 12
        if (half == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** When it's due: its time, or the start of its day. */
    fun dueAt(day: LocalDate, time: Int?, zone: ZoneId): ZonedDateTime =
        day.atStartOfDay(zone).plusMinutes((time ?: 0).toLong())

    private fun minutesOf(now: ZonedDateTime): Int = now.hour * 60 + now.minute

    /** The evening summary should go now: it's past its time today and none went today. */
    fun summaryDue(now: ZonedDateTime, summaryAt: Int, lastSentAt: Long?): Boolean {
        if (minutesOf(now) < summaryAt) return false
        val last = lastSentAt ?: return true
        return Instant.ofEpochMilli(last).atZone(now.zone).toLocalDate() != now.toLocalDate()
    }

    /** A task with a time gets its own reminder once it's a day away or less, until its time. */
    fun ownReminderDue(day: LocalDate, time: Int?, remindedAt: Long?, now: ZonedDateTime): Boolean {
        if (time == null || remindedAt != null) return false
        val due = dueAt(day, time, now.zone)
        return !now.isBefore(due.minusHours(24)) && now.isBefore(due)
    }

    /**
     * A task with no time that turned up after the summary that would have covered it: one due tomorrow
     * found after this evening's summary went, or one due today found after yesterday's. Tasks you add
     * yourself never need one.
     */
    fun lateNoticeDue(
        day: LocalDate,
        time: Int?,
        createdAt: Long,
        remindedAt: Long?,
        addedByYou: Boolean,
        now: ZonedDateTime,
        summaryAt: Int,
        lastSummaryAt: Long?,
    ): Boolean {
        if (time != null || remindedAt != null || addedByYou) return false
        val today = now.toLocalDate()
        return when (day) {
            today.plusDays(1) -> lastSummaryAt != null &&
                Instant.ofEpochMilli(lastSummaryAt).atZone(now.zone).toLocalDate() == today && createdAt > lastSummaryAt
            today -> createdAt > today.minusDays(1).atStartOfDay(now.zone).plusMinutes(summaryAt.toLong()).toInstant().toEpochMilli()
            else -> false
        }
    }

    /** Where a task sits on the list. */
    enum class Group(val label: String) {
        OVERDUE("Overdue"), TODAY("Today"), TOMORROW("Tomorrow"), WEEK("This week"), LATER("Later"), NONE("No date")
    }

    fun group(day: LocalDate?, today: LocalDate): Group = when {
        day == null -> Group.NONE
        day.isBefore(today) -> Group.OVERDUE
        day == today -> Group.TODAY
        day == today.plusDays(1) -> Group.TOMORROW
        !day.isAfter(today.plusDays(6)) -> Group.WEEK
        else -> Group.LATER
    }

    private val SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** "Today, 10:00", "Tomorrow", "Friday", or "Tue 3 Nov, 09:30". */
    fun whenText(day: LocalDate?, time: Int?, today: LocalDate): String {
        if (day == null) return "No date"
        val d = when {
            day == today -> "Today"
            day == today.plusDays(1) -> "Tomorrow"
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today) && !day.isAfter(today.plusDays(6)) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
            else -> day.format(SHORT)
        }
        return if (time != null) "$d, ${clock(time)}" else d
    }

    /** "Friday 23 October", for prompts: the AI works out "next Monday" from it. */
    fun longDay(day: LocalDate): String =
        "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)} ${day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK))} ($day)"

    /** A short, steady key for a task's words, so the same task found twice is kept once. */
    fun keyOf(title: String): String = title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(40)

    /** The organiser Google gives events Gmail adds (flights, bookings). */
    const val GMAIL_ORGANIZER = "unknownorganizer@calendar.google.com"

    private val BIRTHDAY = Regex("""birthday|b'?day|\x{1F382}""", RegexOption.IGNORE_CASE)

    /**
     * Whether an event in your own calendar counts: ones you made, and ones Gmail adds. Invitations from
     * other people are left out, and so are birthdays, which Google keeps in your own calendar as all-day
     * events that come back every year.
     */
    fun keepEvent(title: String, allDay: Boolean, rrule: String?, organizer: String, account: String): Boolean {
        val by = organizer.trim()
        if (by.isNotEmpty() && !by.equals(account.trim(), ignoreCase = true) && !by.equals(GMAIL_ORGANIZER, ignoreCase = true)) return false
        if (allDay && (rrule.orEmpty().contains("FREQ=YEARLY", ignoreCase = true) || BIRTHDAY.containsMatchIn(title))) return false
        return true
    }

    /** A Google Tasks due date ("2026-10-28T00:00:00.000Z": the day only, Google keeps no time). */
    fun googleDue(due: String?): LocalDate? = day(due?.trim()?.take(10))
}
