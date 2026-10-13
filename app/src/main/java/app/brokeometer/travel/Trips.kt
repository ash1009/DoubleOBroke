package app.brokeometer.travel

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

enum class TripKind { RAIL, BUS }

/**
 * A trip as it would be charged: tap in at [from], tap out at [to]. Changing trains on the way is part
 * of the same trip. [via] is TfL's best guess at the lines, from the times.
 */
data class Trip(
    val kind: TripKind,
    val from: Stop,
    val to: Stop,
    val startAt: Long,
    val endAt: Long,
    val via: List<String> = emptyList(),
)

/**
 * Turns legs of travel into tube and bus trips. A leg counts as a train when it starts and ends at
 * different stations and either went underground or went faster than a bus ever does; as a bus when
 * TfL's journey planner has a single bus between the two stops taking about as long. Anything else
 * (a bike, a car, a taxi) is left out.
 */
object TripPlanner {

    private const val STATION_M = 450
    private const val BUS_STOP_M = 120

    /** Faster than this above ground is a train, not a bus (about 50 km/h). */
    private const val TRAIN_MPS = 14.0

    /**
     * Two train rides this close together are one trip: changing lines inside a station, even down
     * long tunnels where the position wobbles (King's Cross, Bank). With more time between them, the
     * second must start near where the first ended (a change through the street, as TfL allows).
     */
    private const val CHANGE_MS = 10 * 60 * 1000L
    private const val CHANGE_LONG_MS = 20 * 60 * 1000L
    private const val CHANGE_M = 700.0

    /** A ride ending this close to where the trip began is coming back, so a new trip. */
    private const val RETURN_M = 500.0

    /** Stations looked at near each end of a trip, nearest first. */
    private const val PICKS = 3

    private sealed interface Found {
        val leg: Leg
        /** [starts] and [ends] are the stations near each end, nearest first; [from] and [to] the nearest. */
        data class Train(override val leg: Leg, val starts: List<Stop>, val ends: List<Stop>) : Found {
            val from: Stop get() = starts.first()
            val to: Stop get() = ends.first()
        }
        data class Bus(override val leg: Leg, val from: Stop, val to: Stop, val line: String?) : Found
    }

    suspend fun plan(legs: List<Leg>, lookup: StopLookup): List<Trip> {
        // Train rides close together are one trip before anything else: a train stopping at a station,
        // or changing lines, often breaks the ride into pieces, some of them ending between stations.
        val found = chain(legs).mapNotNull { classify(it, lookup) }

        // Join trains with only a short walk between them at a station: that's changing trains, one fare.
        val joined = ArrayList<Found>()
        for (f in found) {
            val last = joined.lastOrNull()
            if (f is Found.Train && last is Found.Train && isChange(last, f)) {
                joined[joined.size - 1] = Found.Train(
                    Leg(last.leg.from, f.leg.to, last.leg.underground || f.leg.underground, maxOf(last.leg.maxSpeed, f.leg.maxSpeed), last.leg.distance + f.leg.distance),
                    last.starts,
                    f.ends,
                )
            } else {
                joined += f
            }
        }

        return joined.mapNotNull { f ->
            when (f) {
                is Found.Train -> {
                    if (f.from.id == f.to.id) return@mapNotNull null
                    val (from, to, routes) = stations(f, lookup)
                    val via = bestRoute(routes, f.leg)
                    Trip(TripKind.RAIL, from, to, f.leg.from.at, f.leg.to.at, via?.lines.orEmpty())
                }
                is Found.Bus -> Trip(TripKind.BUS, f.from, f.to, f.leg.from.at, f.leg.to.at, listOfNotNull(f.line))
            }
        }
    }

    /**
     * Which stations the trip was between. A place can have a tube station and a train station side by
     * side (King's Cross, Charing Cross), and the nearest isn't always the one you used: TfL has no fare
     * from the train station at King's Cross to Leicester Square, say. So the closest pair that TfL has
     * both a fare and a route for, or the nearest pair when none has.
     */
    private suspend fun stations(f: Found.Train, lookup: StopLookup): Triple<Stop, Stop, List<Route>> {
        val pairs = f.starts.take(PICKS).flatMap { a -> f.ends.take(PICKS).map { b -> a to b } }
            .filter { (a, b) -> a.id != b.id }
            .sortedBy { (a, b) -> TripFinder.metres(f.leg.from.lat, f.leg.from.lon, a.lat, a.lon) + TripFinder.metres(f.leg.to.lat, f.leg.to.lon, b.lat, b.lon) }
        for ((a, b) in pairs) {
            runCatching { lookup.fare(a, b) }.getOrNull() ?: continue
            val routes = runCatching { lookup.routes(a, b, f.leg.from.at, RAIL_MODES) }.getOrDefault(emptyList())
            if (routes.isNotEmpty()) return Triple(a, b, routes)
        }
        val routes = runCatching { lookup.routes(f.from, f.to, f.leg.from.at, RAIL_MODES) }.getOrDefault(emptyList())
        return Triple(f.from, f.to, routes)
    }

    private fun railLike(leg: Leg) = leg.underground || leg.maxSpeed > TRAIN_MPS

    /** Joins train rides that follow each other closely into one, unless the next one heads back to where it began. */
    internal fun chain(legs: List<Leg>): List<Leg> {
        val out = ArrayList<Leg>()
        for (leg in legs) {
            val last = out.lastOrNull()
            if (last != null && railLike(last) && railLike(leg)) {
                val gap = leg.from.at - last.to.at
                val walked = TripFinder.metres(last.to, leg.from)
                val close = gap in 0..CHANGE_MS || (gap in 0..CHANGE_LONG_MS && walked <= CHANGE_M)
                val headingBack = TripFinder.metres(leg.to, last.from) < RETURN_M
                if (close && !headingBack) {
                    out[out.lastIndex] = Leg(
                        last.from, leg.to, last.underground || leg.underground,
                        maxOf(last.maxSpeed, leg.maxSpeed), last.distance + leg.distance,
                    )
                    continue
                }
            }
            out += leg
        }
        return out
    }

    private fun isChange(a: Found.Train, b: Found.Train): Boolean {
        val gap = b.leg.from.at - a.leg.to.at
        val walked = TripFinder.metres(a.leg.to, b.leg.from)
        // Getting back on where you started is a return trip, not a change.
        if (b.to.id == a.from.id) return false
        return gap in 0..CHANGE_MS || (gap in 0..CHANGE_LONG_MS && walked <= CHANGE_M)
    }

    private suspend fun classify(leg: Leg, lookup: StopLookup): Found? {
        val starts = lookup.stations(leg.from.lat, leg.from.lon, STATION_M)
        val ends = lookup.stations(leg.to.lat, leg.to.lon, STATION_M)
        val start = starts.firstOrNull()
        val end = ends.firstOrNull()
        if (start != null && end != null && start.id != end.id && (leg.underground || leg.maxSpeed > TRAIN_MPS)) {
            return Found.Train(leg, starts, ends)
        }
        if (leg.underground || leg.maxSpeed > 22.0) return null
        // A bus: the journey planner must have one bus between the two stops taking about as long.
        val from = lookup.busStops(leg.from.lat, leg.from.lon, BUS_STOP_M).firstOrNull() ?: return null
        val to = lookup.busStops(leg.to.lat, leg.to.lon, BUS_STOP_M).firstOrNull() ?: return null
        if (from.id == to.id) return null
        val routes = runCatching { lookup.routes(from, to, leg.from.at, "bus") }.getOrDefault(emptyList())
        val took = (leg.to.at - leg.from.at) / 60_000.0
        val match = routes.filter { it.busLegs == 1 && it.walkMinutes <= 8 }.minByOrNull { abs((it.arrives - it.leaves) / 60_000.0 - took) }
            ?: return null
        val planned = (match.arrives - match.leaves) / 60_000.0
        if (took < planned * 0.5 - 3 || took > planned * 2 + 10) return null
        return Found.Bus(leg, from, to, match.lines.firstOrNull())
    }

    /** The planner's route whose times best match when you actually travelled. */
    private fun bestRoute(routes: List<Route>, leg: Leg): Route? =
        routes.minByOrNull { abs(it.arrives - leg.to.at) + abs(it.leaves - leg.from.at) }

    const val RAIL_MODES = "tube,overground,elizabeth-line,dlr,national-rail"
}

/** One trip with what it cost, and a few words on why. */
data class PricedTrip(val trip: Trip, val pence: Long, val peak: Boolean, val note: String)

/**
 * What each trip costs on Oyster: TfL's own fare (peak or off-peak), a third off off-peak trains
 * with a railcard, buses free within an hour of the last paid bus (the Hopper), and the daily cap.
 */
object Fares {

    /** Pay-as-you-go daily caps from March 2026, zones 1 to N. Journeys outside zone 1 may be capped lower. */
    private val CAPS = mapOf(2 to 890L, 3 to 1050L, 4 to 1280L, 5 to 1530L, 6 to 1630L)

    /** Bus and tram fare, and the cap when you only take buses (frozen until at least July 2026). */
    const val BUS = 175L
    const val BUS_CAP = 525L

    private const val HOPPER_MS = 60 * 60 * 1000L

    /** Whether [at] falls in TfL's peak, read from its own words, such as "Monday to Friday from 0630 to 0930". */
    fun isPeak(at: Long, peakWhen: String): Boolean {
        val t: ZonedDateTime = Instant.ofEpochMilli(at).atZone(TflClient.LONDON)
        val weekdaysOnly = peakWhen.isBlank() || peakWhen.contains("Monday to Friday", ignoreCase = true)
        if (weekdaysOnly && (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY)) return false
        val ranges = Regex("""(\d{4})\s*(?:to|-)\s*(\d{4})""").findAll(peakWhen).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
            .ifEmpty { listOf(630 to 930) }
        val hhmm = t.hour * 100 + t.minute
        return ranges.any { (a, b) -> hhmm in a until b }
    }

    /** A third off, to the nearest 5p. */
    fun railcard(pence: Long): Long = ((pence * 2 / 3.0) / 5.0).roundToLong() * 5

    /** The zone that counts for the cap: the lower one at a station on a boundary ("1+2" is 1). */
    fun zoneOf(zone: String): Int? = Regex("""\d+""").findAll(zone).mapNotNull { it.value.toIntOrNull() }.minOrNull()

    fun price(trips: List<Pair<Trip, FareInfo?>>, railcard: Boolean): List<PricedTrip> {
        val out = ArrayList<PricedTrip>()
        var lastPaidBus: Long? = null
        for ((trip, fare) in trips.sortedBy { it.first.startAt }) {
            when (trip.kind) {
                TripKind.BUS -> {
                    val paid = lastPaidBus
                    if (paid != null && trip.startAt - paid <= HOPPER_MS) {
                        out += PricedTrip(trip, 0, false, "Free: within an hour of the last bus")
                    } else {
                        lastPaidBus = trip.startAt
                        out += PricedTrip(trip, BUS, false, "Bus fare")
                    }
                }
                TripKind.RAIL -> {
                    if (fare == null) {
                        out += PricedTrip(trip, 0, false, "TfL had no fare for this trip")
                        continue
                    }
                    val peak = isPeak(trip.startAt, fare.peakWhen)
                    val pence = when {
                        peak -> fare.peak
                        railcard -> railcard(fare.offPeak)
                        else -> fare.offPeak
                    }
                    out += PricedTrip(trip, pence, peak, if (peak) "Peak fare" else if (railcard) "Off-peak, railcard" else "Off-peak fare")
                }
            }
        }
        return capped(out, railcard)
    }

    /** Once the day's fares reach the cap, the rest of the day is free. */
    private fun capped(trips: List<PricedTrip>, railcard: Boolean): List<PricedTrip> {
        val rail = trips.filter { it.trip.kind == TripKind.RAIL }
        val cap = if (rail.isEmpty()) {
            BUS_CAP
        } else {
            val zone = rail.flatMap { listOf(it.trip.from.zone, it.trip.to.zone) }.mapNotNull { zoneOf(it) }.maxOrNull()
                ?: return trips
            val full = CAPS[zone.coerceIn(2, 6)] ?: return trips
            if (railcard && rail.none { it.peak }) railcard(full) else full
        }
        var total = 0L
        return trips.map { t ->
            val left = (cap - total).coerceAtLeast(0)
            val pay = minOf(t.pence, left)
            total += pay
            if (pay < t.pence) t.copy(pence = pay, note = if (pay == 0L) "Free: the day's cap was reached" else "Capped: the day's cap was reached") else t
        }
    }
}
