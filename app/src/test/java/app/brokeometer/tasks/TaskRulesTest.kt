package app.brokeometer.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskRulesTest {

    private val zone = ZoneId.of("Europe/London")
    private fun at(day: String, h: Int, m: Int = 0) = ZonedDateTime.of(LocalDate.parse(day).atTime(h, m), zone)
    private fun ms(day: String, h: Int, m: Int = 0) = at(day, h, m).toInstant().toEpochMilli()

    @Test
    fun timesAndDaysAreRead() {
        assertEquals(570, TaskRules.time("09:30"))
        assertEquals(21 * 60 + 30, TaskRules.time("9:30 pm"))
        assertEquals(12 * 60, TaskRules.time("12pm"))
        assertEquals(0, TaskRules.time("12:00 am"))
        assertNull(TaskRules.time(""))
        assertNull(TaskRules.time("9"))
        assertNull(TaskRules.time("25:00"))
        assertEquals(LocalDate.of(2026, 10, 27), TaskRules.day("2026-10-27"))
        assertNull(TaskRules.day("next Monday"))
    }

    @Test
    fun theSummaryGoesOnceAnEveningAfterItsTime() {
        val summaryAt = 20 * 60
        assertFalse(TaskRules.summaryDue(at("2026-10-25", 19, 59), summaryAt, null))
        assertTrue(TaskRules.summaryDue(at("2026-10-25", 20, 5), summaryAt, ms("2026-10-24", 20, 1)))
        assertFalse(TaskRules.summaryDue(at("2026-10-25", 22, 0), summaryAt, ms("2026-10-25", 20, 1)))
    }

    @Test
    fun aTimedTaskIsRemindedADayBeforeOnce() {
        val day = LocalDate.parse("2026-10-27")
        assertFalse(TaskRules.ownReminderDue(day, 10 * 60, null, at("2026-10-26", 9, 59)))
        assertTrue(TaskRules.ownReminderDue(day, 10 * 60, null, at("2026-10-26", 10, 0)))
        assertFalse(TaskRules.ownReminderDue(day, 10 * 60, ms("2026-10-26", 10, 0), at("2026-10-26", 12, 0)))
        assertFalse(TaskRules.ownReminderDue(day, 10 * 60, null, at("2026-10-27", 10, 1)))
        // No time: that's for the evening summary.
        assertFalse(TaskRules.ownReminderDue(day, null, null, at("2026-10-26", 10, 0)))
    }

    @Test
    fun aTaskFoundAfterItsSummaryGetsANoticeOfItsOwn() {
        val summaryAt = 20 * 60
        val now = at("2026-10-25", 21, 0)
        val sent = ms("2026-10-25", 20, 0)
        val tomorrow = LocalDate.parse("2026-10-26")
        // Found at 20:30, after tonight's summary: told now.
        assertTrue(TaskRules.lateNoticeDue(tomorrow, null, ms("2026-10-25", 20, 30), null, false, now, summaryAt, sent))
        // Found before it: it was in the summary.
        assertFalse(TaskRules.lateNoticeDue(tomorrow, null, ms("2026-10-25", 19, 0), null, false, now, summaryAt, sent))
        // Tonight's summary hasn't gone yet: it'll be in it.
        assertFalse(TaskRules.lateNoticeDue(tomorrow, null, ms("2026-10-25", 18, 0), null, false, at("2026-10-25", 19, 0), summaryAt, ms("2026-10-24", 20, 0)))
        // Due today, found this morning: told now.
        assertTrue(TaskRules.lateNoticeDue(LocalDate.parse("2026-10-25"), null, ms("2026-10-25", 8, 0), null, false, now, summaryAt, sent))
        // Added by you: no notice.
        assertFalse(TaskRules.lateNoticeDue(tomorrow, null, ms("2026-10-25", 20, 30), null, true, now, summaryAt, sent))
    }

    @Test
    fun groupsAndWords() {
        val today = LocalDate.parse("2026-10-25")
        assertEquals(TaskRules.Group.OVERDUE, TaskRules.group(today.minusDays(1), today))
        assertEquals(TaskRules.Group.TOMORROW, TaskRules.group(today.plusDays(1), today))
        assertEquals(TaskRules.Group.WEEK, TaskRules.group(today.plusDays(6), today))
        assertEquals(TaskRules.Group.LATER, TaskRules.group(today.plusDays(7), today))
        assertEquals(TaskRules.Group.NONE, TaskRules.group(null, today))
        assertEquals("Today, 09:30", TaskRules.whenText(today, 570, today))
        assertEquals("Tuesday", TaskRules.whenText(today.plusDays(2), null, today))
        assertEquals("Tue 3 Nov, 10:00", TaskRules.whenText(LocalDate.parse("2026-11-03"), 600, today))
        assertEquals(TaskRules.keyOf("Submit WTW assessment!"), TaskRules.keyOf("submit wtw assessment"))
    }
}
