package app.brokeometer.travel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DayTimelineTest {

    private val london = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 12)

    private fun at(h: Int, m: Int, s: Int = 0): Long = monday.atTime(LocalTime.of(h, m, s)).atZone(london).toInstant().toEpochMilli()

    private fun move(from: Pair<Double, Double>, to: Pair<Double, Double>, start: Long, minutes: Double, everyS: Int = 15): List<Fix> {
        val steps = (minutes * 60 / everyS).toInt().coerceAtLeast(1)
        return (0..steps).map { i ->
            val f = i.toDouble() / steps
            Fix(start + i * everyS * 1000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 15f)
        }
    }

    private fun stay(place: Pair<Double, Double>, start: Long, minutes: Int, everyS: Int = 15): List<Fix> =
        (0..minutes * 60 / everyS).map { i -> Fix(start + i * everyS * 1000L, place.first, place.second, 15f) }

    private val home = 51.6368 to -0.1335
    private val wp = 51.6322 to -0.1280
    private val gp = 51.5069 to -0.1426
    private val vx = 51.4860 to -0.1240
    private val office = 51.4880 to -0.1280

    @Test
    fun splitsADayIntoStaysWalksAndRides() {
        val day = buildList {
            addAll(stay(home, at(7, 0), 60, everyS = 120)) // asleep: a position every 2 minutes
            addAll(move(home, wp, at(8, 0), 8.0)) // walk to the station
            addAll(stay(wp, at(8, 8), 4)) // on the platform
            addAll(stay(gp, at(8, 36), 3)) // changing at Green Park, underground in between
            addAll(move(vx, office, at(8, 50), 5.0)) // out of Vauxhall, walk to work
            addAll(stay(office, at(8, 56), 120, everyS = 120))
        }
        val segments = DayTimeline.build(day)
        val kinds = segments.map { it.kind }
        assertEquals("kinds: $kinds", Motion.STILL, kinds.first())
        assertEquals("kinds: $kinds", Motion.STILL, kinds.last())
        assertEquals("kinds: $kinds", 2, kinds.count { it == Motion.FAST })
        assertTrue("kinds: $kinds", segments.all { it.kind != Motion.FAST || it.underground })

        // One after another, with no gaps or overlaps.
        segments.zipWithNext().forEach { (a, b) -> assertEquals("$a then $b", a.end, b.start) }
        assertEquals(at(7, 0), segments.first().start)
        assertEquals(at(10, 56), segments.last().end)

        // Home until about 8, then a walk of about 640 metres.
        val homeStay = segments.first()
        assertTrue(homeStay.end in at(8, 0)..at(8, 2))
        val walk = segments[1]
        assertEquals(Motion.WALKING, walk.kind)
        assertTrue("walk ${walk.metres}", walk.metres in 350.0..700.0)

        // At work from about 8:56, as one stay.
        val work = segments.last()
        assertTrue(work.start <= at(8, 57))
        assertTrue(TripFinder.metres(work.lat, work.lon, office.first, office.second) < 30)

        val totals = DayTimeline.totals(segments)
        assertEquals(at(10, 56) - at(7, 0), totals.still + totals.walking + totals.fast)
        assertTrue(totals.fastMetres > 5_000)
    }

    @Test
    fun wobblingInOnePlaceIsStayingPut() {
        // Indoors the position jumps around by up to 50 metres, every 2 minutes, for 3 hours.
        val wobble = (0..90).map { i ->
            val d = if (i % 2 == 0) 0.0003 else -0.0003
            Fix(at(12, 0) + i * 120_000L, office.first + d, office.second - d, 40f)
        }
        val segments = DayTimeline.build(wobble)
        assertEquals("segments: $segments", 1, segments.size)
        assertEquals(Motion.STILL, segments.single().kind)
    }

    @Test
    fun aFlightIsMovingFastNotAWalk() {
        val trip = stay(home, at(9, 0), 30) + stay(55.9533 to -3.1883, at(10, 40), 30)
        val segments = DayTimeline.build(trip)
        assertEquals("segments: $segments", listOf(Motion.STILL, Motion.FAST, Motion.STILL), segments.map { it.kind })
        assertTrue(segments[1].metres > 500_000)
    }

    @Test
    fun aBusIsMovingFastWithAPauseInside() {
        val ride = stay(wp, at(19, 0), 5) +
            move(wp, 51.6303 to -0.1115, at(19, 5, 15), 4.0) +
            stay(51.6303 to -0.1115, at(19, 9, 30), 1) +
            move(51.6303 to -0.1115, 51.6278 to -0.0965, at(19, 10, 45), 4.0) +
            stay(51.6278 to -0.0965, at(19, 15), 5)
        val segments = DayTimeline.build(ride)
        assertEquals("segments: $segments", listOf(Motion.STILL, Motion.FAST, Motion.STILL), segments.map { it.kind })
        assertTrue(segments[1].top > 3.0)
    }
}
