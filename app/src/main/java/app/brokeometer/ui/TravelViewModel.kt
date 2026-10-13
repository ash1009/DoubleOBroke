package app.brokeometer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brokeometer.BrokeApp
import app.brokeometer.BuildConfig
import app.brokeometer.data.TxSource
import app.brokeometer.data.TransactionEntity
import app.brokeometer.travel.DayTimeline
import app.brokeometer.travel.DayTotals
import app.brokeometer.travel.Fix
import app.brokeometer.travel.Motion
import app.brokeometer.travel.Segment
import app.brokeometer.travel.TravelCheckWorker
import app.brokeometer.travel.TravelTracker
import app.brokeometer.travel.TripFinder
import app.brokeometer.travel.stepsIn
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One stretch of the day on the Travel page, with the station it was near and the trip it was, if any. */
data class TimelineRow(
    /** Its place in the day, first to last; also its key on the page. */
    val index: Int,
    val segment: Segment,
    /** "Southgate" when a station (or bus stop) already looked up is close by. */
    val near: String?,
    /** The trip on Payments this ride was part of. */
    val trip: TransactionEntity?,
    /** The second part of a trip, after changing trains: no second fare. */
    val changed: Boolean = false,
)

data class TravelDay(
    val day: LocalDate,
    val rows: List<TimelineRow>,
    val totals: DayTotals,
    val points: Int,
    val last: Long?,
    val trips: List<TransactionEntity>,
    /** Steps counted that day, or null when the step counter has no readings for it. */
    val steps: Long? = null,
)

/** The Travel page: the day's stays, walks and rides; noting trips on or off; the railcard. */
class TravelViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as BrokeApp).container
    private val prefs = container.prefs
    private val dao = container.travelDatabase.dao()

    val tracking: StateFlow<Boolean> = prefs.trackTrips
    val railcard: StateFlow<String> = prefs.railcard

    private val _day = MutableStateFlow(LocalDate.now())
    val day: StateFlow<LocalDate> = _day

    /** Which kind of stretch the Activity list shows: all when null. */
    private val _kind = MutableStateFlow<Motion?>(null)
    val kind: StateFlow<Motion?> = _kind

    fun setKind(kind: Motion?) {
        _kind.value = kind
    }

    /** Opens Activity on today, showing [kind] (from a number tapped on Home). */
    fun showToday(kind: Motion?) {
        _day.value = LocalDate.now()
        _kind.value = kind
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val timeline: StateFlow<TravelDay?> = _day.flatMapLatest { dayOf(it) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Today, for the card on Home, whichever day the Travel page is showing. */
    private val _today = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val today: StateFlow<TravelDay?> = _today.flatMapLatest { dayOf(it) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** After midnight, Home moves on to the new day. */
    fun refreshToday() {
        val now = LocalDate.now()
        if (_today.value != now) _today.value = now
    }

    private fun dayOf(d: LocalDate): Flow<TravelDay> {
        val from = d.atStartOfDay(Dates.zone()).toInstant().toEpochMilli()
        val to = d.plusDays(1).atStartOfDay(Dates.zone()).toInstant().toEpochMilli()
        return combine(
            dao.observePoints(from, to),
            container.repository.dao.observeTravel(Dates.toDay(d)),
            dao.observeStepSamples(from, to),
        ) { points, trips, samples ->
            val segments = DayTimeline.build(points.map { Fix(it.at, it.lat, it.lon, it.accuracy, it.speed) })
            val steps = if (samples.isEmpty()) null else stepsIn(samples.map { it.total }, runCatching { dao.stepSampleBefore(from) }.getOrNull()?.total)
            TravelDay(d, rows(segments, trips), DayTimeline.totals(segments), points.size, points.lastOrNull()?.at, trips, steps)
        }
    }

    /**
     * Everything noted on [day], in one file to share for debugging: every position (time, place,
     * accuracy, speed), the step counter's readings, the stretches of travel the trip finder saw, and
     * the trips added to Payments. Returns the file, ready to share, or null if there's nothing.
     */
    suspend fun exportDay(day: LocalDate): java.io.File? = withContext(Dispatchers.IO) {
        val zone = Dates.zone()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val points = dao.points(from, to)
        if (points.isEmpty()) return@withContext null
        val steps = dao.stepSamplesIn(from, to)
        val before = dao.stepSampleBefore(from)
        val trips = container.repository.dao.onDays(listOf(Dates.toDay(day))).filter { it.source == TxSource.TRAVEL }
        val fixes = points.map { Fix(it.at, it.lat, it.lon, it.accuracy, it.speed) }
        val legs = TripFinder.legs(fixes)
        val root = org.json.JSONObject()
            .put("day", Dates.toDay(day))
            .put("zone", zone.id)
            .put("app", BuildConfig.VERSION_NAME)
            .put("railcard", prefs.railcard.value)
            .put("points", org.json.JSONArray().apply {
                points.forEach { p ->
                    put(org.json.JSONArray().put(p.at).put(p.lat).put(p.lon).put(p.accuracy.toDouble()).put(p.speed?.toDouble() ?: org.json.JSONObject.NULL))
                }
            })
            .put("pointsFormat", "[time ms, lat, lon, accuracy m, speed m/s or null]")
            .put("steps", org.json.JSONArray().apply {
                before?.let { put(org.json.JSONArray().put(it.at).put(it.total)) }
                steps.forEach { put(org.json.JSONArray().put(it.at).put(it.total)) }
            })
            .put("legs", org.json.JSONArray().apply {
                legs.forEach { l ->
                    put(org.json.JSONObject()
                        .put("from", l.from.at).put("to", l.to.at)
                        .put("fromLat", l.from.lat).put("fromLon", l.from.lon)
                        .put("toLat", l.to.lat).put("toLon", l.to.lon)
                        .put("underground", l.underground).put("maxSpeed", l.maxSpeed).put("metres", l.distance))
                }
            })
            .put("trips", org.json.JSONArray().apply {
                trips.forEach { t ->
                    put(org.json.JSONObject()
                        .put("at", t.occurredAt).put("name", t.merchantName).put("place", t.place)
                        .put("pence", t.amountMinor).put("status", t.status).put("note", t.note))
                }
            })
        val dir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        java.io.File(dir, "travel-${Dates.toDay(day)}.json").apply { writeText(root.toString()) }
    }

    /** Whether steps can be counted: allowed, and the phone has a step counter. */
    fun stepsReady(): Boolean = TravelTracker.stepsAllowed(getApplication()) && TravelTracker.hasStepCounter(getApplication())

    fun hasStepCounter(): Boolean = TravelTracker.hasStepCounter(getApplication())

    /** After step counting is allowed: the running service starts reading the step counter. */
    fun stepsAllowedNow() = TravelTracker.sync(getApplication())

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setDay(day: LocalDate) {
        _day.value = day
        _message.value = null
    }

    fun setTracking(on: Boolean) {
        prefs.setTrackTrips(on)
        val app = getApplication<Application>()
        TravelTracker.sync(app)
        TravelCheckWorker.schedule(app)
    }

    fun setRailcard(value: String) = prefs.setRailcard(value)

    fun dismissMessage() {
        _message.value = null
    }

    /** Runs the nightly check now, for the day shown. Trips still going on are left for later. */
    fun checkDay() {
        if (_checking.value) return
        val day = _day.value
        val today = day == LocalDate.now()
        viewModelScope.launch {
            _checking.value = true
            _message.value = null
            val started = System.currentTimeMillis()
            val result = try {
                // Off the main thread, so the spinner keeps turning while the day is worked out.
                val r = withContext(Dispatchers.Default) { container.travel.check(day, notify = false) }
                when {
                    r.points == 0 -> if (today) "Nothing noted yet today. Turn on Note my trips, then check again after a trip." else "Nothing was noted that day."
                    r.trips.isEmpty() -> if (today) "No tube or bus trips found yet today." else "No tube or bus trips found that day."
                    r.added == 0 -> "Found ${count(r.trips.size, "trip")}, already on Payments."
                    else -> "Found ${count(r.trips.size, "trip")}, ${Money.format(r.trips.sumOf { it.pence }, "GBP")}. " +
                        "${count(r.added, "trip")} added to Payments, waiting for you."
                }
            } catch (e: Exception) {
                "Couldn't reach TfL for the stations and fares. Check your connection and try again."
            }
            // A quick check still shows it was checking, for a moment.
            delay((1_000L - (System.currentTimeMillis() - started)).coerceAtLeast(0L))
            _checking.value = false
            _message.value = result
        }
    }

    /** Names stays after a station or stop close by, and rides after the trip they were part of. */
    private suspend fun rows(segments: List<Segment>, trips: List<TransactionEntity>): List<TimelineRow> {
        val out = ArrayList<TimelineRow>()
        var lastRide: TimelineRow? = null
        for (s in segments) {
            val near = when (s.kind) {
                Motion.STILL -> nearName(s.lat, s.lon)
                Motion.FAST -> nearName(s.lat, s.lon)
                Motion.WALKING -> null
            }
            var trip: TransactionEntity? = null
            var changed = false
            if (s.kind == Motion.FAST) {
                trip = trips.firstOrNull { it.occurredAt in (s.start - 15 * 60_000L)..s.end }
                // Changing trains: the next ride soon after is the same trip.
                val before = lastRide
                if (trip == null && before?.trip != null && s.start - before.segment.end <= 10 * 60_000L) {
                    trip = before.trip
                    changed = true
                }
            }
            val row = TimelineRow(out.size, s, near, trip, changed)
            if (s.kind == Motion.FAST) lastRide = row
            out += row
        }
        return out
    }

    private suspend fun nearName(lat: Double, lon: Double): String? {
        fun box(m: Double) = Pair(m / 111_000.0, m / (111_000.0 * Math.cos(Math.toRadians(lat))))
        val (dLat, dLon) = box(300.0)
        val stations = runCatching { dao.stopsIn("rail", lat - dLat, lat + dLat, lon - dLon, lon + dLon) }.getOrDefault(emptyList())
        stations.minByOrNull { TripFinder.metres(lat, lon, it.lat, it.lon) }
            ?.takeIf { TripFinder.metres(lat, lon, it.lat, it.lon) <= 300 }
            ?.let { return it.name }
        val (bLat, bLon) = box(80.0)
        val stops = runCatching { dao.stopsIn("bus", lat - bLat, lat + bLat, lon - bLon, lon + bLon) }.getOrDefault(emptyList())
        return stops.minByOrNull { TripFinder.metres(lat, lon, it.lat, it.lon) }
            ?.takeIf { TripFinder.metres(lat, lon, it.lat, it.lon) <= 80 }
            ?.let { "${it.name} bus stop" }
    }

    private fun count(n: Int, one: String) = "$n ${if (n == 1) one else one + "s"}"
}
