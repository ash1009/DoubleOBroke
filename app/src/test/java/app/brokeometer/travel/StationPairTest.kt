package app.brokeometer.travel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * King's Cross has a train station and a tube station side by side, and the train station can be the
 * nearer one. TfL has no fare from it to Leicester Square, so the trip must use the tube station.
 */
class StationPairTest {

    private val kxRail = Stop("910GKNGX", "London King's Cross", 51.5320, -0.1233, "1")
    private val kxTube = Stop("940GZZLUKSX", "King's Cross St. Pancras", 51.5304, -0.1238, "1")
    private val lsq = Stop("940GZZLULSQ", "Leicester Square", 51.5113, -0.1281, "1")
    private val all = listOf(kxRail, kxTube, lsq)

    private fun lookup(routes: Boolean) = object : StopLookup {
        override suspend fun stations(lat: Double, lon: Double, radius: Int) =
            all.filter { TripFinder.metres(lat, lon, it.lat, it.lon) <= radius }.sortedBy { TripFinder.metres(lat, lon, it.lat, it.lon) }
        override suspend fun busStops(lat: Double, lon: Double, radius: Int) = emptyList<Stop>()
        override suspend fun fare(from: Stop, to: Stop) =
            if (from.id.startsWith("910G") || to.id.startsWith("910G")) null else FareInfo(360, 300, "")
        override suspend fun routes(from: Stop, to: Stop, at: Long, modes: String) =
            if (routes) listOf(Route(listOf("Piccadilly"), at, at + 6 * 60_000L, 0, 0)) else emptyList()
    }

    // Starting right by the train station's entrance, so it is the nearest.
    private val leg = Leg(Fix(1_000_000L, 51.5319, -0.1233), Fix(1_400_000L, 51.5113, -0.1281), underground = true, maxSpeed = 0.0, distance = 2400.0)

    @Test
    fun theTubeStationWhenTheTrainStationHasNoFare() = runBlocking {
        val trips = TripPlanner.plan(listOf(leg), lookup(routes = true))
        assertEquals(1, trips.size)
        assertEquals("King's Cross St. Pancras", trips[0].from.name)
        assertEquals("Leicester Square", trips[0].to.name)
        assertEquals(listOf("Piccadilly"), trips[0].via)
    }

    @Test
    fun theNearestWhenTflHasNothing() = runBlocking {
        val trips = TripPlanner.plan(listOf(leg), lookup(routes = false))
        assertEquals("London King's Cross", trips[0].from.name)
    }
}
