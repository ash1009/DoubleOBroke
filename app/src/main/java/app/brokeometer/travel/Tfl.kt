package app.brokeometer.travel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A station or bus stop. [zone] is like "4" or "1+2"; bus stops have none. */
data class Stop(val id: String, val name: String, val lat: Double, val lon: Double, val zone: String = "")

/** TfL's fares between two stations: pence at peak and off-peak, and its words for when peak is. */
data class FareInfo(val peak: Long, val offPeak: Long, val peakWhen: String)

/** A route TfL's journey planner suggests: the lines, and when it leaves and arrives. */
data class Route(val lines: List<String>, val leaves: Long, val arrives: Long, val busLegs: Int, val walkMinutes: Int)

/** What the trip planner needs to look up. Kept as an interface so tests can answer without the internet. */
interface StopLookup {
    suspend fun stations(lat: Double, lon: Double, radius: Int): List<Stop>
    suspend fun busStops(lat: Double, lon: Double, radius: Int): List<Stop>
    suspend fun fare(from: Stop, to: Stop): FareInfo?
    /** Routes from one stop to another leaving around [at], by [modes] ("bus", or "tube,overground,..."). */
    suspend fun routes(from: Stop, to: Stop, at: Long, modes: String): List<Route>
}

/**
 * TfL's open data (api.tfl.gov.uk): stations and stops near a place, fares, and the journey planner.
 * No account or key is needed. Only places and times of trips are sent, never the whole day's trace.
 */
class TflClient(private val dao: TravelDao? = null) : StopLookup {

    class TflError(message: String) : Exception(message)

    private val nearCache = HashMap<String, List<Stop>>()

    override suspend fun stations(lat: Double, lon: Double, radius: Int): List<Stop> =
        near(lat, lon, radius, "NaptanMetroStation,NaptanRailStation", "rail")

    override suspend fun busStops(lat: Double, lon: Double, radius: Int): List<Stop> =
        near(lat, lon, radius, "NaptanPublicBusCoachTram", "bus")

    private suspend fun near(lat: Double, lon: Double, radius: Int, types: String, kind: String): List<Stop> {
        val key = "%s|%.4f|%.4f|%d".format(kind, lat, lon, radius)
        nearCache[key]?.let { return it }
        val json = get("StopPoint?lat=$lat&lon=$lon&stopTypes=$types&radius=$radius&returnLines=false")
        val list = JSONObject(json).optJSONArray("stopPoints") ?: JSONArray()
        val stops = (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            val id = s.optString("naptanId").ifBlank { s.optString("id") }
            if (id.isBlank()) return@mapNotNull null
            var zone = ""
            s.optJSONArray("additionalProperties")?.let { props ->
                for (p in 0 until props.length()) {
                    val o = props.optJSONObject(p) ?: continue
                    if (o.optString("key").equals("Zone", ignoreCase = true)) zone = o.optString("value")
                }
            }
            Stop(id, cleanName(s.optString("commonName")), s.optDouble("lat"), s.optDouble("lon"), zone) to s.optDouble("distance", 9e9)
        }.sortedBy { it.second }.map { it.first }
        nearCache[key] = stops
        dao?.let { d -> runCatching { d.saveStops(stops.map { TflStopEntity(it.id, it.name, it.lat, it.lon, it.zone, kind) }) } }
        return stops
    }

    override suspend fun fare(from: Stop, to: Stop): FareInfo? {
        dao?.fare(from.id, to.id)?.let { cached ->
            if (System.currentTimeMillis() - cached.fetchedAt < FARE_KEEP_MS) return FareInfo(cached.peak, cached.offPeak, cached.peakWhen)
        }
        val json = get("StopPoint/${enc(from.id)}/FareTo/${enc(to.id)}")
        val info = parseFare(json) ?: return null
        dao?.let { runCatching { it.saveFare(TflFareEntity(from.id, to.id, info.peak, info.offPeak, info.peakWhen, System.currentTimeMillis())) } }
        return info
    }

    override suspend fun routes(from: Stop, to: Stop, at: Long, modes: String): List<Route> {
        val time = Instant.ofEpochMilli(at).atZone(LONDON)
        val q = "date=${time.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}&time=${time.format(DateTimeFormatter.ofPattern("HHmm"))}" +
            "&timeIs=Departing&mode=${enc(modes)}&walkingSpeed=Average"
        val json = get("Journey/JourneyResults/${from.lat},${from.lon}/to/${to.lat},${to.lon}?$q")
        return parseRoutes(json)
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val conn = URL("$BASE/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw TflError("TfL answered $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://api.tfl.gov.uk"
        private const val FARE_KEEP_MS = 14L * 24 * 60 * 60 * 1000
        val LONDON: ZoneId = ZoneId.of("Europe/London")

        private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

        /** "Southgate Underground Station" reads as "Southgate". */
        fun cleanName(name: String): String = name
            .replace(Regex("""\s+(Underground|DLR|Rail|Overground|Elizabeth line)?\s*Station$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\((London|Bakerloo|Central|District|H&C Line|Circle Line)[^)]*\)$"""), "")
            .trim()

        /** Reads the adult pay-as-you-go peak and off-peak fares from TfL's Single Fare Finder answer. */
        fun parseFare(json: String): FareInfo? {
            val sections = runCatching { JSONArray(json) }.getOrNull() ?: return null
            for (i in 0 until sections.length()) {
                val rows = sections.optJSONObject(i)?.optJSONArray("rows") ?: continue
                for (r in 0 until rows.length()) {
                    val row = rows.optJSONObject(r) ?: continue
                    if (!row.optString("passengerType").equals("Adult", ignoreCase = true)) continue
                    val tickets = row.optJSONArray("ticketsAvailable") ?: continue
                    var peak: Long? = null
                    var off: Long? = null
                    var peakWhen = ""
                    for (t in 0 until tickets.length()) {
                        val tk = tickets.optJSONObject(t) ?: continue
                        val type = tk.optJSONObject("ticketType")?.optString("type").orEmpty()
                        if (!type.contains("Pay as you go", ignoreCase = true)) continue
                        val time = tk.optJSONObject("ticketTime")
                        val when_ = time?.optString("type").orEmpty().trim()
                        val pence = tk.optString("cost").toBigDecimalOrNull()?.movePointRight(2)?.toLong() ?: continue
                        when {
                            when_.equals("Off Peak", ignoreCase = true) -> off = pence
                            when_.equals("Peak", ignoreCase = true) -> {
                                peak = pence
                                peakWhen = time?.optString("description").orEmpty()
                            }
                            when_.equals("Anytime", ignoreCase = true) -> {
                                if (peak == null) peak = pence
                                if (off == null) off = pence
                            }
                        }
                    }
                    if (peak != null || off != null) return FareInfo(peak ?: off!!, off ?: peak!!, peakWhen)
                }
            }
            return null
        }

        fun parseRoutes(json: String): List<Route> {
            val journeys = runCatching { JSONObject(json).optJSONArray("journeys") }.getOrNull() ?: return emptyList()
            val out = ArrayList<Route>()
            for (i in 0 until journeys.length()) {
                val j = journeys.optJSONObject(i) ?: continue
                val legs = j.optJSONArray("legs") ?: continue
                val lines = ArrayList<String>()
                var bus = 0
                var walk = 0
                for (l in 0 until legs.length()) {
                    val leg = legs.optJSONObject(l) ?: continue
                    val mode = leg.optJSONObject("mode")?.optString("id").orEmpty()
                    if (mode == "walking") {
                        walk += leg.optInt("duration")
                        continue
                    }
                    if (mode == "bus") bus++
                    val name = leg.optJSONArray("routeOptions")?.optJSONObject(0)?.optString("name").orEmpty()
                    if (name.isNotBlank() && name !in lines) lines += name
                }
                val leaves = parseTime(j.optString("startDateTime")) ?: continue
                val arrives = parseTime(j.optString("arrivalDateTime")) ?: continue
                out += Route(lines, leaves, arrives, bus, walk)
            }
            return out
        }

        private fun parseTime(text: String): Long? = runCatching {
            LocalDateTime.parse(text).atZone(LONDON).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
