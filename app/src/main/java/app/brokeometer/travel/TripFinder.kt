package app.brokeometer.travel

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One position, as the trip finder sees it. */
data class Fix(val at: Long, val lat: Double, val lon: Double, val accuracy: Float = 20f, val speed: Float? = null)

/**
 * A stretch of moving faster than walking: on a train, a bus, a bike or in a car. [from] is where
 * it began (where you got on, or went underground) and [to] where it ended. [underground] means the
 * phone had no position for a while and then turned up far away, which on foot is impossible.
 */
data class Leg(
    val from: Fix,
    val to: Fix,
    val underground: Boolean,
    /** Fastest speed seen, metres a second. */
    val maxSpeed: Double,
    /** Metres, along the positions seen. */
    val distance: Double,
)

/**
 * Splits a day of positions into legs of travel. Walking and staying put are what's left in between.
 * No Android and no internet here, so it can be tested on its own; working out which station or bus
 * stop each leg starts and ends at happens afterwards.
 */
object TripFinder {

    /** Positions rougher than this (mostly from phone masts, often underground) are left out. */
    const val ROUGH_M = 150f

    /** No position for this long, then far away: underground. */
    private const val GAP_S = 150.0

    /** Faster than this between two positions is not walking (about 11 km/h). A run may pass it; it's dropped later. */
    private const val MOTOR_MPS = 3.0

    /**
     * The phone's own speed (from GPS, much steadier than working it out from positions that wobble
     * between tall buildings) above this is riding, not walking (about 14 km/h).
     */
    private const val REPORTED_MOTOR_MPS = 4.0

    /** A fix this precise (GPS) has a speed worth believing. */
    private const val GOOD_FIX_M = 25f

    /** Speeds worked out from positions are checked over at least this long. */
    private const val WINDOW_MS = 30_000L

    /** A ride of at least this length with less than this share of GPS positions was below ground. */
    private const val BURIED_MIN_M = 1_000.0
    private const val GPS_SHARE = 0.25

    /** Above ground, a ride goes somewhere: it ends at least this much of its length away from where it began. */
    private const val STRAIGHT = 0.5

    /** A position that jumps this far and comes straight back, within this long, is a glitch. */
    private const val SPIKE_M = 150.0
    private const val SPIKE_BACK_M = 60.0
    private const val SPIKE_MS = 90_000L

    /** A train, bus or car goes at least this far; shorter "legs" are a run for the bus, or noise. */
    private const val LEG_MIN_M = 600.0

    /** A stop this short inside a leg (traffic lights, a bus stop) doesn't end it. */
    private const val PAUSE_S = 150.0
    private const val PAUSE_M = 200.0

    /**
     * The positions worth using: rough ones (phone masts) left out, and glitches removed, where the
     * position jumps a few hundred metres and comes straight back.
     */
    fun clean(points: List<Fix>): List<Fix> {
        val pts = points.filter { it.accuracy <= ROUGH_M }.sortedBy { it.at }.distinctBy { it.at }
        if (pts.size < 3) return pts
        val drop = BooleanArray(pts.size)
        for (i in 0 until pts.size - 2) {
            if (drop[i]) continue
            // Up to three positions away and back, within a minute and a half.
            var j = i + 2
            while (j < pts.size && j <= i + 4 && pts[j].at - pts[i].at <= SPIKE_MS) {
                if (metres(pts[i], pts[j]) <= SPIKE_BACK_M && (i + 1 until j).all { metres(pts[i], pts[it]) >= SPIKE_M }) {
                    for (k in i + 1 until j) drop[k] = true
                    break
                }
                j++
            }
        }
        return pts.filterIndexed { i, _ -> !drop[i] }
    }

    fun legs(points: List<Fix>): List<Leg> {
        val pts = clean(points)
        if (pts.size < 2) return emptyList()

        // Each step between two positions: moving like a vehicle, underground, or neither.
        val n = pts.size
        val motor = BooleanArray(n - 1)
        val under = BooleanArray(n - 1)
        val speed = DoubleArray(n - 1)
        for (k in 0 until n - 1) {
            val a = pts[k]
            val b = pts[k + 1]
            val dt = (b.at - a.at) / 1000.0
            if (dt <= 0) continue
            val d = metres(a, b)
            val v = d / dt
            speed[k] = v
            under[k] = dt >= GAP_S && d >= 700 && v >= 2.5 && v <= 45
            // When the phone says how fast it's going, believe that over positions that wobble.
            // Only a good fix's speed counts: rough ones (masts, wifi) often say 0 while moving.
            val reported = b.speed?.toDouble()?.takeIf { it > 0 && b.accuracy <= GOOD_FIX_M }
            motor[k] = under[k] || if (reported != null && dt < GAP_S) {
                reported >= REPORTED_MOTOR_MPS && v >= 1.5 && v <= 70
            } else {
                // Moved further than the positions' own uncertainty, quicker than walking, and still
                // quicker over half a minute: a position wobbling from side to side can't fake that.
                v >= MOTOR_MPS && d >= 25 && d > (a.accuracy + b.accuracy) / 2 && v <= 70 && windowSpeed(pts, k) >= MOTOR_MPS
            }
        }

        // Only on a phone that gives GPS speeds at all does a ride without them mean below ground.
        val gpsDay = pts.count { it.speed != null && it.accuracy <= GOOD_FIX_M } >= pts.size * GPS_SHARE

        val legs = ArrayList<Leg>()
        var k = 0
        while (k < n - 1) {
            if (!motor[k]) {
                k++
                continue
            }
            val start = k
            var end = k
            var j = k + 1
            while (j < n - 1) {
                if (motor[j]) {
                    end = j
                    j++
                    continue
                }
                // A short stop inside the leg: carry on if moving starts again soon, close by.
                var m = j
                while (m < n - 1 && !motor[m]) m++
                val pauseS = (pts[m].at - pts[j].at) / 1000.0
                val pauseM = metres(pts[j], pts[m])
                if (m < n - 1 && pauseS <= PAUSE_S && pauseM <= PAUSE_M) {
                    j = m
                } else {
                    break
                }
            }
            var dist = 0.0
            var top = 0.0
            var anyUnder = false
            for (s in start..end) {
                dist += metres(pts[s], pts[s + 1])
                // The phone's own speed where it has one; worked out from positions where it hasn't.
                if (under[s]) anyUnder = true else top = max(top, pts[s + 1].speed?.toDouble() ?: speed[s])
            }
            // Above ground a ride goes somewhere; wandering about with a wobbly position doesn't.
            val across = metres(pts[start], pts[end + 1])
            val real = if (anyUnder) dist >= LEG_MIN_M else across >= LEG_MIN_M && across >= STRAIGHT * dist
            // Hardly any GPS on the way (positions from phone masts in tube tunnels with 4G): below ground.
            val gps = (start + 1..end + 1).count { pts[it].speed != null && pts[it].accuracy <= GOOD_FIX_M }
            val buried = gpsDay && across >= BURIED_MIN_M && gps < (end - start + 1) * GPS_SHARE
            if (real) legs += Leg(pts[start], pts[end + 1], anyUnder || buried, top, dist)
            k = end + 1
        }
        return legs
    }

    /** Speed from position [k] to the first one at least [WINDOW_MS] later (or the last one). */
    private fun windowSpeed(pts: List<Fix>, k: Int): Double {
        var j = k + 1
        while (j < pts.size - 1 && pts[j].at - pts[k].at < WINDOW_MS) j++
        val dt = (pts[j].at - pts[k].at) / 1000.0
        return if (dt > 0) metres(pts[k], pts[j]) / dt else 0.0
    }

    /** Straight-line metres between two positions. */
    fun metres(a: Fix, b: Fix): Double = metres(a.lat, a.lon, b.lat, b.lon)

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
