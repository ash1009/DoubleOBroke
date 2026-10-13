package app.brokeometer.travel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TripsTest {

    private val london = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 12)

    private fun at(h: Int, m: Int, s: Int = 0): Long = monday.atTime(LocalTime.of(h, m, s)).atZone(london).toInstant().toEpochMilli()

    private val southgate = Stop("940GZZLUSGT", "Southgate", 51.6320, -0.1278, "4")
    private val greenPark = Stop("940GZZLUGPK", "Green Park", 51.5067, -0.1428, "1")
    private val vauxhall = Stop("940GZZLUVXL", "Vauxhall", 51.4862, -0.1236, "1+2")
    private val stopA = Stop("490000A", "Southgate Station (A)", 51.6328, -0.1265)
    private val stopB = Stop("490000B", "Palmers Green (B)", 51.6278, -0.0965)

    /** Walking or riding in a straight line from one place to another, a position every [everyS] seconds. */
    private fun move(from: Pair<Double, Double>, to: Pair<Double, Double>, start: Long, minutes: Double, everyS: Int = 15): List<Fix> {
        val steps = (minutes * 60 / everyS).toInt().coerceAtLeast(1)
        return (0..steps).map { i ->
            val f = i.toDouble() / steps
            Fix(start + i * everyS * 1000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 15f)
        }
    }

    private fun stay(place: Pair<Double, Double>, start: Long, minutes: Int): List<Fix> =
        (0..minutes * 4).map { i -> Fix(start + i * 15_000L, place.first, place.second, 15f) }

    private val home = 51.6368 to -0.1335
    private val wp = 51.6322 to -0.1280
    private val gp = 51.5069 to -0.1426
    private val vx = 51.4860 to -0.1240
    private val office = 51.4880 to -0.1280

    private fun day(): List<Fix> = buildList {
        addAll(stay(home, at(7, 50), 10))
        addAll(move(home, wp, at(8, 0), 8.0)) // walk to the station
        addAll(stay(wp, at(8, 8), 4)) // on the platform
        // Underground: nothing until Green Park, a 3-minute walk between lines, nothing again until Vauxhall.
        addAll(stay(gp, at(8, 36), 3))
        addAll(move(vx, office, at(8, 50), 5.0)) // out of Vauxhall, walk to work
        addAll(stay(office, at(8, 56), 30))
        // Home again in the evening, straight through.
        addAll(move(office, vx, at(18, 0), 5.0))
        addAll(stay(vx, at(18, 5), 2))
        addAll(move(wp, home, at(18, 45), 8.0))
        addAll(stay(home, at(18, 54), 5))
        // A bus from Southgate to Palmers Green: about 2 km in 9 minutes, with a stop half way.
        addAll(move(stopA.lat to stopA.lon, 51.6303 to -0.1115, at(19, 10), 4.0))
        addAll(stay(51.6303 to -0.1115, at(19, 14, 15), 1))
        addAll(move(51.6303 to -0.1115, stopB.lat to stopB.lon, at(19, 15, 30), 4.0))
        addAll(stay(stopB.lat to stopB.lon, at(19, 20), 3))
    }

    private val lookup = object : StopLookup {
        val stations = listOf(southgate, greenPark, vauxhall)
        val stops = listOf(stopA, stopB)
        override suspend fun stations(lat: Double, lon: Double, radius: Int) =
            stations.filter { TripFinder.metres(lat, lon, it.lat, it.lon) <= radius }.sortedBy { TripFinder.metres(lat, lon, it.lat, it.lon) }
        override suspend fun busStops(lat: Double, lon: Double, radius: Int) =
            stops.filter { TripFinder.metres(lat, lon, it.lat, it.lon) <= radius }
        override suspend fun fare(from: Stop, to: Stop) = FareInfo(480, 360, "Monday to Friday from 0630 to 0930")
        override suspend fun routes(from: Stop, to: Stop, at: Long, modes: String): List<Route> =
            if (modes == "bus") listOf(Route(listOf("223"), at, at + 12 * 60_000L, busLegs = 1, walkMinutes = 1))
            else listOf(Route(listOf("Jubilee", "Victoria"), at, at + 38 * 60_000L, busLegs = 0, walkMinutes = 0))
    }

    @Test
    fun findsTubeTripsAndABus() = runBlocking {
        val legs = TripFinder.legs(day())
        assertTrue("legs: $legs", legs.size >= 4)
        val trips = TripPlanner.plan(legs, lookup)
        assertEquals("trips: $trips", 3, trips.size)

        val morning = trips[0]
        assertEquals(TripKind.RAIL, morning.kind)
        // Changing at Green Park is part of the same trip.
        assertEquals("Southgate", morning.from.name)
        assertEquals("Vauxhall", morning.to.name)
        assertEquals(listOf("Jubilee", "Victoria"), morning.via)

        val evening = trips[1]
        assertEquals(TripKind.RAIL, evening.kind)
        assertEquals("Vauxhall", evening.from.name)
        assertEquals("Southgate", evening.to.name)

        val bus = trips[2]
        assertEquals(TripKind.BUS, bus.kind)
        assertEquals(listOf("223"), bus.via)
    }

    @Test
    fun aBikeRideIsNotABus() = runBlocking {
        // Fast enough to be a vehicle, but from nowhere near a stop to nowhere near a station.
        val ride = stay(51.5300 to -0.2000, at(12, 0), 2) + move(51.5300 to -0.2000, 51.5400 to -0.1800, at(12, 2), 10.0) +
            stay(51.5400 to -0.1800, at(12, 12, 15), 2)
        val trips = TripPlanner.plan(TripFinder.legs(ride), lookup)
        assertTrue(trips.isEmpty())
    }

    @Test
    fun pricesWithPeakHopperAndCap() {
        val peakTrip = Trip(TripKind.RAIL, southgate, vauxhall, at(8, 12), at(8, 50))
        val offPeakTrip = Trip(TripKind.RAIL, vauxhall, southgate, at(18, 5), at(18, 44))
        val bus1 = Trip(TripKind.BUS, southgate, vauxhall, at(19, 10), at(19, 22))
        val bus2 = Trip(TripKind.BUS, southgate, vauxhall, at(19, 40), at(19, 50))
        val fare = FareInfo(480, 360, "Monday to Friday from 0630 to 0930")
        val priced = Fares.price(listOf(peakTrip to fare, offPeakTrip to fare, bus1 to null, bus2 to null), railcard = false)
        assertEquals(listOf(480L, 360L, 175L, 0L), priced.map { it.pence })
        assertTrue(priced[0].peak)
        assertFalse(priced[1].peak)

        // Lots of trips in zones 1 to 4 stop at the £12.80 cap.
        val many = (0 until 5).map { i -> Trip(TripKind.RAIL, southgate, vauxhall, at(10 + i, 0), at(10 + i, 40)) to fare }
        val capped = Fares.price(many, railcard = false)
        assertEquals(1280L, capped.sumOf { it.pence })
        assertEquals(0L, capped.last().pence)

        // A railcard takes a third off off-peak fares, to the nearest 5p.
        assertEquals(240L, Fares.price(listOf(offPeakTrip to fare), railcard = true).single().pence)
        assertEquals(480L, Fares.price(listOf(peakTrip to fare), railcard = true).single().pence)
    }

    @Test
    fun readsPeakTimesAndZones() {
        assertTrue(Fares.isPeak(at(7, 0), "Monday to Friday from 0630 to 0930"))
        assertFalse(Fares.isPeak(at(9, 30), "Monday to Friday from 0630 to 0930"))
        assertFalse(Fares.isPeak(at(17, 0), "Monday to Friday from 0630 to 0930"))
        assertTrue(Fares.isPeak(at(17, 0), "Monday to Friday from 0630 to 0930 and from 1600 to 1900"))
        val saturday = LocalDate.of(2026, 10, 17).atTime(8, 0).atZone(london).toInstant().toEpochMilli()
        assertFalse(Fares.isPeak(saturday, "Monday to Friday from 0630 to 0930"))
        assertEquals(1, Fares.zoneOf("1+2"))
        assertEquals(4, Fares.zoneOf("4"))
        assertEquals(null, Fares.zoneOf(""))
    }

    @Test
    fun readsTflFares() {
        val json = """[{"header":"Single Fare Finder","rows":[{"passengerType":"Adult","ticketsAvailable":[
            {"ticketType":{"type":"Pay as you go"},"ticketTime":{"type":"Off Peak","description":"At all other times"},"cost":"3.60"},
            {"ticketType":{"type":"Pay as you go"},"ticketTime":{"type":"Peak ","description":"Monday to Friday from 0630 to 0930."},"cost":"4.80"},
            {"ticketType":{"type":"CashSingle"},"ticketTime":{"type":"Anytime"},"cost":"7.00"}]}]}]"""
        val fare = TflClient.parseFare(json)!!
        assertEquals(480L, fare.peak)
        assertEquals(360L, fare.offPeak)
        assertTrue(fare.peakWhen.contains("0630"))
        assertEquals("Southgate", TflClient.cleanName("Southgate Underground Station"))
    }
}
