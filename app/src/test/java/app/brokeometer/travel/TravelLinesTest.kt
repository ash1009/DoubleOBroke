package app.brokeometer.travel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TravelLinesTest {

    private val london = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 12)

    private fun at(h: Int, m: Int): Long = monday.atTime(LocalTime.of(h, m)).atZone(london).toInstant().toEpochMilli()

    private fun seg(kind: Motion, from: Long, to: Long, metres: Double = 0.0) =
        Segment(kind, from, to, 51.5, -0.1, 51.5, -0.1, metres)

    @Test
    fun nudgesAfterALongTimeStill() {
        val day = listOf(seg(Motion.WALKING, at(8, 0), at(8, 20), 1_500.0), seg(Motion.STILL, at(8, 20), at(12, 30)))
        val line = TravelLines.pick(day, steps = 2_000, now = at(12, 31), zone = london)
        assertTrue(line, line.contains("08:20"))
    }

    @Test
    fun praisesALotOfWalking() {
        val day = listOf(seg(Motion.WALKING, at(9, 0), at(11, 0), 6_400.0), seg(Motion.STILL, at(11, 0), at(11, 30)))
        val line = TravelLines.pick(day, steps = null, now = at(11, 31), zone = london)
        assertTrue(line, line.contains("6.4 km"))
        assertTrue(TravelLines.pick(day, steps = 12_000, now = at(11, 31), zone = london).contains("steps") ||
            TravelLines.pick(day, steps = 12_000, now = at(11, 31), zone = london).contains("Legend"))
    }

    @Test
    fun saysSomethingOnAnEmptyMorning() {
        assertEquals(true, TravelLines.pick(emptyList(), steps = null, now = at(7, 30), zone = london).isNotBlank())
    }

    @Test
    fun onTheMoveWhileRiding() {
        val day = listOf(seg(Motion.STILL, at(7, 0), at(8, 0)), seg(Motion.FAST, at(8, 0), at(8, 25), 9_000.0))
        val line = TravelLines.pick(day, steps = 500, now = at(8, 27), zone = london)
        assertTrue(line, line.contains("move") || line.contains("ride"))
    }

    @Test
    fun countsStepsAcrossARestart() {
        assertEquals(500L, stepsIn(listOf(10_200, 10_500), before = 10_000))
        // The phone restarted: the counter went back to 0 and counted 300 more.
        assertEquals(800L, stepsIn(listOf(10_500, 300), before = 10_000))
        assertEquals(0L, stepsIn(emptyList(), before = 10_000))
        assertEquals(300L, stepsIn(listOf(1_000, 1_300), before = null))
    }
}
