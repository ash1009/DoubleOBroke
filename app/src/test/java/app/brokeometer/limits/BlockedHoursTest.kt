package app.brokeometer.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class BlockedHoursTest {

    private val london = ZoneId.of("Europe/London")
    private val insta = HoursApp("com.instagram.android", "Instagram")
    private val yt = HoursApp("com.google.android.youtube", "YouTube")
    private fun ms(t: LocalDateTime) = t.atZone(london).toInstant().toEpochMilli()
    private val monday = LocalDate.of(2026, 10, 19)

    @Test
    fun hoursCanRunPastMidnight() {
        assertTrue(HoursRules.covers(0, 420, 0))
        assertTrue(HoursRules.covers(0, 420, 419))
        assertFalse(HoursRules.covers(0, 420, 420))
        assertTrue(HoursRules.covers(23 * 60, 420, 23 * 60 + 30))
        assertTrue(HoursRules.covers(23 * 60, 420, 300))
        assertFalse(HoursRules.covers(23 * 60, 420, 12 * 60))
        assertFalse(HoursRules.covers(600, 600, 600))
    }

    @Test
    fun onlyAppsOnTheListAreClosed() {
        val h = BlockedHours(0, 420, listOf(insta))
        assertTrue(HoursRules.closed(h, insta.pkg, LocalTime.of(2, 0)))
        assertFalse(HoursRules.closed(h, insta.pkg, LocalTime.of(7, 0)))
        assertFalse(HoursRules.closed(h, yt.pkg, LocalTime.of(2, 0)))
    }

    @Test
    fun onlyLessBlockedTimeWaits() {
        assertFalse(HoursRules.timesLoosen(0, 420, 0, 480)) // ends later
        assertFalse(HoursRules.timesLoosen(0, 420, 23 * 60, 420)) // starts earlier
        assertTrue(HoursRules.timesLoosen(0, 420, 0, 360)) // ends earlier
        assertTrue(HoursRules.timesLoosen(0, 420, 60, 480)) // moved: an hour freed at the start
        assertTrue(HoursRules.timesLoosen(23 * 60, 420, 0, 420))
    }

    @Test
    fun aChangeNeverStartsPartWayThroughBlockedHours() {
        val night = BlockedHours(0, 420, listOf(insta))
        // Asked for at 2am, inside the hours: tonight's hours still run in full, then it starts.
        assertEquals(ms(monday.plusDays(1).atTime(7, 0)), HoursRules.changeFrom(night, monday.atTime(2, 0), london))
        val late = BlockedHours(23 * 60, 420, listOf(insta))
        // Asked for at 23:30: not at midnight, but once these hours end the next morning.
        assertEquals(ms(monday.plusDays(1).atTime(7, 0)), HoursRules.changeFrom(late, monday.atTime(23, 30), london))
        val day = BlockedHours(13 * 60, 15 * 60, listOf(insta))
        // Hours that are off at midnight: from midnight, like a daily limit.
        assertEquals(ms(monday.plusDays(1).atStartOfDay()), HoursRules.changeFrom(day, monday.atTime(14, 0), london))
    }

    @Test
    fun aWaitingChangeTakesOverAtItsTime() {
        val from = ms(monday.plusDays(1).atTime(7, 0))
        val h = HoursRules.loosen(BlockedHours(0, 420, listOf(insta, yt)), from, start = 0, end = 360, remove = setOf(yt.pkg))
        assertEquals(h, HoursRules.at(h, from - 1))
        val after = HoursRules.at(h, from)
        assertEquals(360, after.end)
        assertEquals(listOf(insta), after.apps)
        assertNull(after.next)
    }

    @Test
    fun addingAnAppIsStraightAwayAndKeepsOneWaitingToComeOff() {
        val from = ms(monday.plusDays(1).atTime(7, 0))
        val h = HoursRules.loosen(BlockedHours(0, 420, listOf(insta)), from, remove = setOf(insta.pkg))
        val added = HoursRules.add(h, listOf(yt))
        assertEquals(listOf(insta, yt), added.apps)
        assertEquals(setOf(insta.pkg), added.next!!.remove)
        // Adding back the one waiting to come off keeps it on.
        assertNull(HoursRules.add(added, listOf(insta)).next)
    }

    @Test
    fun moreBlockedTimeDropsAWaitingChangeOfTimes() {
        val from = ms(monday.plusDays(1).atTime(7, 0))
        val h = HoursRules.loosen(BlockedHours(0, 420, listOf(insta, yt)), from, start = 60, end = 420, remove = setOf(yt.pkg))
        val wider = HoursRules.widen(h, 23 * 60, 420)
        assertEquals(23 * 60, wider.start)
        assertNull(wider.next!!.start)
        assertEquals(setOf(yt.pkg), wider.next!!.remove)
        assertNull(HoursRules.widen(HoursRules.loosen(BlockedHours(0, 420), from, start = 60, end = 420), 0, 480).next)
    }

    @Test
    fun aSecondChangeWaitsWithTheFirst() {
        val first = ms(monday.plusDays(1).atTime(7, 0))
        val second = ms(monday.plusDays(2).atTime(7, 0))
        val h = HoursRules.loosen(BlockedHours(0, 420, listOf(insta, yt)), first, remove = setOf(insta.pkg))
        val both = HoursRules.loosen(h, second, remove = setOf(yt.pkg))
        assertEquals(second, both.next!!.from)
        assertEquals(setOf(insta.pkg, yt.pkg), both.next!!.remove)
    }

    @Test
    fun clockAndSteps() {
        assertEquals("00:00", HoursRules.clock(0))
        assertEquals("07:00", HoursRules.clock(420))
        assertEquals("23:45", HoursRules.step(0, -1).let(HoursRules::clock))
        assertEquals(0, HoursRules.step(23 * 60 + 45, 1))
        assertEquals("tomorrow at 07:00", HoursRules.whenText(ms(monday.plusDays(1).atTime(7, 0)), london, monday))
    }
}
