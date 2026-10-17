package app.brokeometer.travel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * A bus taken a few minutes after a tube ride, fast enough on a main road to look like a train, used to
 * be joined onto the tube ride. The joined ride then ended nowhere near a station and both were lost.
 */
class BusAfterTrainTest {

    private val london = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int): Long = LocalDate.of(2026, 10, 12).atTime(h, m).atZone(london).toInstant().toEpochMilli()

    private val greenPark = Stop("GPK", "Green Park", 51.5067, -0.1428, "1")
    private val northGreenwich = Stop("NGW", "North Greenwich", 51.5005, 0.0039, "2+3")

    private val lookup = object : StopLookup {
        val stations = listOf(greenPark, northGreenwich)
        override suspend fun stations(lat: Double, lon: Double, radius: Int) =
            stations.filter { TripFinder.metres(lat, lon, it.lat, it.lon) <= radius }
        override suspend fun busStops(lat: Double, lon: Double, radius: Int) =
            listOf(Stop("BUS$lat,$lon", "Stop", lat, lon))
        override suspend fun fare(from: Stop, to: Stop) = FareInfo(480, 360, "Monday to Friday from 0630 to 0930")
        override suspend fun routes(from: Stop, to: Stop, at: Long, modes: String): List<Route> =
            if (modes == "bus") listOf(Route(listOf("486"), at, at + 15 * 60_000L, busLegs = 1, walkMinutes = 0))
            else listOf(Route(listOf("Jubilee"), at, at + 35 * 60_000L, busLegs = 0, walkMinutes = 0))
    }

    private val train = Leg(Fix(at(10, 50), 51.5067, -0.1428), Fix(at(11, 25), 51.5005, 0.0039), true, 30.0, 15_000.0)

    @Test
    fun aFastBusRightAfterATrainIsStillABus() = runBlocking {
        // Out of the station and on a bus 9 minutes later, up to 61 km/h on the main road.
        val bus = Leg(Fix(at(11, 34), 51.4998, 0.0047), Fix(at(11, 49), 51.4950, 0.0350), false, 16.9, 4_000.0)
        val trips = TripPlanner.plan(listOf(train, bus), lookup)
        assertEquals(trips.toString(), listOf(TripKind.RAIL, TripKind.BUS), trips.map { it.kind })
        assertEquals("Green Park", trips[0].from.name)
        assertEquals("North Greenwich", trips[0].to.name)
        assertEquals(listOf("486"), trips[1].via)
    }

    @Test
    fun aTrainBrokenIntoPiecesIsStillOneRide() = runBlocking {
        // The same ride in two pieces, a minute apart in a tunnel: still one trip, as before.
        val first = Leg(Fix(at(10, 50), 51.5067, -0.1428), Fix(at(11, 5), 51.5040, -0.0700), true, 30.0, 6_000.0)
        val second = Leg(Fix(at(11, 6), 51.5040, -0.0700), Fix(at(11, 25), 51.5005, 0.0039), true, 30.0, 9_000.0)
        val trips = TripPlanner.plan(listOf(first, second), lookup)
        assertEquals(trips.toString(), 1, trips.size)
        assertEquals("North Greenwich", trips[0].to.name)
    }
}
