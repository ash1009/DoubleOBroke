package app.brokeometer.travel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A train held outside a station (at a signal, or waiting for a platform) stops away from any
 * station, then creeps in. The ride ends where it really stopped, not where it was held.
 */
class HeldTrainTest {

    private val london = ZoneId.of("Europe/London")
    private val day = LocalDate.of(2026, 10, 14)
    private fun at(h: Int, m: Int, s: Int = 0): Long = day.atTime(LocalTime.of(h, m, s)).atZone(london).toInstant().toEpochMilli()

    // Three stations on a line running north. The train is held 470 m short of the middle one.
    private val south = Stop("S", "South", 51.5000, -0.1000, "1")
    private val middle = Stop("M", "Middle", 51.5400, -0.1000, "3")
    private val north = Stop("N", "North", 51.5700, -0.1000, "4")
    private val held = 51.53577 to -0.1000

    private val lookup = object : StopLookup {
        val all = listOf(south, middle, north)
        override suspend fun stations(lat: Double, lon: Double, radius: Int) =
            all.filter { TripFinder.metres(lat, lon, it.lat, it.lon) <= radius }.sortedBy { TripFinder.metres(lat, lon, it.lat, it.lon) }
        override suspend fun busStops(lat: Double, lon: Double, radius: Int) = emptyList<Stop>()
        override suspend fun fare(from: Stop, to: Stop) = FareInfo(480, 360, "")
        override suspend fun routes(from: Stop, to: Stop, at: Long, modes: String) =
            listOf(Route(listOf("Jubilee"), at, at + 20 * 60_000L, 0, 0))
    }

    /** From one place to another at [mps], a position every 15 seconds with the phone's own speed. */
    private fun move(from: Pair<Double, Double>, to: Pair<Double, Double>, start: Long, mps: Float): List<Fix> {
        val metres = TripFinder.metres(from.first, from.second, to.first, to.second)
        val steps = (metres / mps / 15).toInt().coerceAtLeast(1)
        return (0..steps).map { i ->
            val f = i.toDouble() / steps
            Fix(start + i * 15_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 10f, mps)
        }
    }

    private fun stay(place: Pair<Double, Double>, start: Long, minutes: Int): List<Fix> =
        (0..minutes * 4).map { i ->
            val wobble = if (i % 2 == 0) 0.0001 else -0.0001
            Fix(start + i * 15_000L, place.first + wobble, place.second, 10f, 0f)
        }

    private fun Stop.at() = lat to lon

    private fun plan(points: List<Fix>): List<Trip> = runBlocking {
        val sorted = points.sortedBy { it.at }.distinctBy { it.at }
        TripPlanner.plan(TripFinder.legs(sorted), lookup, sorted)
    }

    @Test
    fun heldOutsideThenCreepingInEndsAtTheStation() {
        val day = buildList {
            addAll(stay(south.at(), at(0, 10), 4))
            addAll(move(south.at(), held, at(0, 14, 15), 15f))
            addAll(stay(held, at(0, 19), 3)) // held at a signal
            addAll(move(held, middle.at(), at(0, 22, 15), 8f)) // creeping in
            addAll(stay(middle.at(), at(0, 23, 30), 1))
            addAll(move(middle.at(), 51.5400 to -0.0940, at(0, 24, 45), 1.3f)) // walking home
        }
        val trips = plan(day)
        assertEquals("trips: $trips", 1, trips.size)
        assertEquals("South", trips[0].from.name)
        assertEquals("Middle", trips[0].to.name)
        assertTrue(trips[0].endAt >= at(0, 23))
    }

    @Test
    fun heldThenOnPastTheStationEndsWhereYouGetOff() {
        val day = buildList {
            addAll(stay(south.at(), at(0, 10), 4))
            addAll(move(south.at(), held, at(0, 14, 15), 15f))
            addAll(stay(held, at(0, 19), 3))
            addAll(move(held, north.at(), at(0, 22, 15), 15f)) // through the middle station and on
            addAll(stay(north.at(), at(0, 27), 1))
            addAll(move(north.at(), 51.5700 to -0.0940, at(0, 28, 15), 1.3f))
        }
        val trips = plan(day)
        assertEquals("trips: $trips", 1, trips.size)
        assertEquals("South", trips[0].from.name)
        assertEquals("North", trips[0].to.name)
    }

    @Test
    fun walkingOffWhereItStoppedIsNotMadeIntoAStation() {
        val day = buildList {
            addAll(stay(south.at(), at(0, 10), 4))
            addAll(move(south.at(), held, at(0, 14, 15), 15f))
            addAll(stay(held, at(0, 19), 3))
            addAll(move(held, 51.53577 to -0.1080, at(0, 22, 15), 1.3f)) // walking west, away from any station
        }
        assertTrue(plan(day).isEmpty())
    }

    @Test
    fun theWalkAfterIsNotPartOfTheRide() {
        // Stopped, then walking: the ride ends where it stopped.
        val leg = Leg(Fix(at(0, 10), 51.50, -0.10), Fix(at(0, 20), held.first, held.second), underground = false, maxSpeed = 15.0, distance = 4000.0)
        val walk = move(held, 51.5400 to -0.1000, at(0, 20, 15), 1.3f)
        assertEquals(leg, TripPlanner.carryOn(leg, walk))
    }
}
