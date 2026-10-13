package app.brokeometer.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LimitRulesTest {

    private val min = 60_000L

    @Test
    fun theWayOutWaitDoubles() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 60, 60), (0..7).map { LimitRules.wayOutWait(it) })
    }

    @Test
    fun suggestsAQuarterLess() {
        assertEquals(180, LimitRules.suggest(240 * min)) // 4 hours a day: 3 hours
        assertEquals(90, LimitRules.suggest(130 * min)) // 97.5 minutes: down to 90
        assertEquals(15, LimitRules.suggest(10 * min))
        assertEquals(60, LimitRules.suggest(null))
    }

    @Test
    fun onlyLooseningWaits() {
        val l = AppLimit("com.instagram.android", "Instagram", 180, wayOut = false)
        assertTrue(LimitRules.loosens(l, 200, false))
        assertTrue(LimitRules.loosens(l, 180, true))
        assertFalse(LimitRules.loosens(l, 120, false))
        assertFalse(LimitRules.loosens(l, 180, false))
    }

    @Test
    fun aChangeStartsOnItsDay() {
        val today = LocalDate.of(2026, 10, 13)
        val l = AppLimit("p", "P", 180, next = Change("2026-10-14", 240, true))
        assertEquals(180, LimitRules.onDay(l, today)!!.minutes)
        val tomorrow = LimitRules.onDay(l, today.plusDays(1))!!
        assertEquals(240, tomorrow.minutes)
        assertNull(tomorrow.next)
        assertNull(LimitRules.onDay(l.copy(next = Change("2026-10-14", 180, true, remove = true)), today.plusDays(1)))
    }

    @Test
    fun thePromiseMustBeTyped() {
        assertTrue(LimitRules.promised("  " + LimitRules.PROMISE.uppercase().replace(" ", "  ")))
        assertFalse(LimitRules.promised(LimitRules.PROMISE.dropLast(5)))
    }

    @Test
    fun hoursAndMinutes() {
        assertEquals("3 h", LimitRules.hm(180 * min))
        assertEquals("1 h 20 m", LimitRules.hm(80 * min))
        assertEquals("45 m", LimitRules.hm(45 * min + 59_000))
    }

    private fun ev(at: Long, pkg: String, kind: UsageEvent.Kind, cls: String? = "A") = UsageEvent(at * min, pkg, cls, kind)

    @Test
    fun countsTimeOnScreen() {
        val r = UsageEvent.Kind.RESUMED
        val p = UsageEvent.Kind.PAUSED
        val events = listOf(
            // Open since before the start: only the part after it counts.
            ev(-10, "insta", r), ev(5, "insta", p),
            // Moving between two of its own screens is one stretch.
            ev(10, "insta", r, "Feed"), ev(20, "insta", p, "Feed"), ev(20, "insta", r, "Chat"), ev(30, "insta", p, "Chat"),
            // Another app coming to the front ends it, even with no pause noted.
            ev(40, "yt", r), ev(50, "insta", r), ev(55, "insta", p),
            // The screen going off ends it.
            ev(60, "yt", r), ev(70, "yt", UsageEvent.Kind.SCREEN_OFF, null), ev(90, "yt", p),
            // Still open at the end.
            ev(100, "insta", r),
        )
        val t = UsageMath.foreground(events, 0, 110 * min)
        assertEquals((5 + 20 + 5 + 10) * min, t["insta"])
        assertEquals((10 + 10) * min, t["yt"])
    }

    @Test
    fun aUsualDayLeavesOutDaysWithNothing() {
        val days = listOf(mapOf("insta" to 240 * min), mapOf("yt" to 10 * min), emptyMap())
        assertEquals(120 * min, UsageMath.typical(days, "insta"))
        assertNull(UsageMath.typical(listOf(emptyMap()), "insta"))
    }
}
