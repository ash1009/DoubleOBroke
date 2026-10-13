package app.brokeometer.travel

/** How you were moving: staying put, walking, or faster than walking (a train, bus, bike or car). */
enum class Motion { STILL, WALKING, FAST }

/**
 * One stretch of the day. For a stay, [lat] and [lon] are its middle; for a walk or ride, where it
 * ended, and [fromLat] and [fromLon] where it began.
 */
data class Segment(
    val kind: Motion,
    val start: Long,
    val end: Long,
    val lat: Double,
    val lon: Double,
    val fromLat: Double,
    val fromLon: Double,
    /** Metres moved. */
    val metres: Double,
    /** Fastest, metres a second (rides only). */
    val top: Double = 0.0,
    /** No position for a while, then far away: the tube. */
    val underground: Boolean = false,
) {
    val millis: Long get() = end - start

    /** Average metres a second. */
    val speed: Double get() = if (millis > 0) metres / (millis / 1000.0) else 0.0
}

/** Time (ms) and distance (metres) for each kind, for one day. */
data class DayTotals(val still: Long, val walking: Long, val fast: Long, val walkMetres: Double, val fastMetres: Double)

/**
 * Turns a day of positions into stays, walks and rides, one after another. Rides come from
 * [TripFinder]; between them, anywhere you stayed within [STAY_M] metres for [STAY_MS] or more is a
 * stay, and the rest is walking. No Android here, so it can be tested on its own.
 */
object DayTimeline {

    private const val STAY_M = 100.0
    private const val STAY_MS = 3 * 60_000L

    /** Moving less than this is staying put, however long it took. */
    private const val STEPS_M = 60.0

    /** Two stays this close, with only a moment between them, are one. */
    private const val SAME_PLACE_M = 150.0

    /** Walks are measured between positions at least this far apart in time. */
    private const val WALK_STEP_MS = 60_000L

    /** Further than this between two positions in a row can't have been walked. */
    private const val JUMP_M = 2_000.0

    fun build(points: List<Fix>): List<Segment> {
        val pts = TripFinder.clean(points)
        if (pts.size < 2) return emptyList()
        val legs = TripFinder.legs(pts)
        val out = ArrayList<Segment>()
        var i = 0
        var li = 0
        while (i < pts.size - 1) {
            val leg = legs.getOrNull(li)
            if (leg != null && pts[i].at >= leg.from.at) {
                var e = i
                while (e + 1 < pts.size && pts[e + 1].at <= leg.to.at) e++
                out += Segment(
                    Motion.FAST, leg.from.at, leg.to.at, leg.to.lat, leg.to.lon, leg.from.lat, leg.from.lon,
                    leg.distance, if (leg.underground) 0.0 else leg.maxSpeed, leg.underground,
                )
                li++
                i = e
                continue
            }
            val until = leg?.from?.at ?: Long.MAX_VALUE
            var e = i
            while (e + 1 < pts.size && pts[e + 1].at <= until) e++
            if (e > i) quiet(pts.subList(i, e + 1), out)
            if (leg == null) break
            if (e == i) li++ // Can't happen, but keeps the loop moving if it does.
            i = e
        }
        return tidy(out)
    }

    fun totals(segments: List<Segment>): DayTotals = DayTotals(
        still = segments.filter { it.kind == Motion.STILL }.sumOf { it.millis },
        walking = segments.filter { it.kind == Motion.WALKING }.sumOf { it.millis },
        fast = segments.filter { it.kind == Motion.FAST }.sumOf { it.millis },
        walkMetres = segments.filter { it.kind == Motion.WALKING }.sumOf { it.metres },
        fastMetres = segments.filter { it.kind == Motion.FAST }.sumOf { it.metres },
    )

    /**
     * Between rides. A jump of kilometres from one position to the next (a flight, or the phone off on
     * a journey) is moving fast; the rest is stays and walks.
     */
    private fun quiet(p: List<Fix>, out: MutableList<Segment>) {
        var from = 0
        for (k in 0 until p.size - 1) {
            val d = TripFinder.metres(p[k], p[k + 1])
            val secs = ((p[k + 1].at - p[k].at) / 1000.0).coerceAtLeast(1.0)
            // Slower than that it could have been a walk with no positions on the way.
            if (d < JUMP_M || d / secs < 40) continue
            if (k > from) stays(p.subList(from, k + 1), out)
            out += Segment(Motion.FAST, p[k].at, p[k + 1].at, p[k + 1].lat, p[k + 1].lon, p[k].lat, p[k].lon, d, d / secs)
            from = k + 1
        }
        if (p.size - 1 > from) stays(p.subList(from, p.size), out)
    }

    /** Stays where you were within [STAY_M] long enough, walking in between. */
    private fun stays(p: List<Fix>, out: MutableList<Segment>) {
        var i = 0
        var walkFrom = 0
        while (i < p.size) {
            var j = i
            while (j + 1 < p.size && TripFinder.metres(p[i], p[j + 1]) <= STAY_M) j++
            if (p[j].at - p[i].at >= STAY_MS) {
                if (i > walkFrom) out += walk(p, walkFrom, i)
                out += stay(p, i, j)
                walkFrom = j
                i = j + 1
            } else {
                i++
            }
        }
        if (p.size - 1 > walkFrom) out += walk(p, walkFrom, p.size - 1)
    }

    private fun stay(p: List<Fix>, a: Int, b: Int): Segment {
        val lat = (a..b).sumOf { p[it].lat } / (b - a + 1)
        val lon = (a..b).sumOf { p[it].lon } / (b - a + 1)
        return Segment(Motion.STILL, p[a].at, p[b].at, lat, lon, lat, lon, 0.0)
    }

    private fun walk(p: List<Fix>, a: Int, b: Int): Segment {
        // Measured between positions at least a minute apart, so a position wobbling from side to
        // side between buildings doesn't add made-up metres.
        var metres = 0.0
        var last = a
        for (k in a + 1..b) {
            if (k == b || p[k].at - p[last].at >= WALK_STEP_MS) {
                metres += TripFinder.metres(p[last], p[k])
                last = k
            }
        }
        // A few steps, or the position wobbling, is staying put.
        if (TripFinder.metres(p[a], p[b]) < STEPS_M && metres < STEPS_M * 2) return stay(p, a, b)
        return Segment(Motion.WALKING, p[a].at, p[b].at, p[b].lat, p[b].lon, p[a].lat, p[a].lon, metres)
    }

    /** Joins stays at the same place and walks that follow each other, and lets moments of walking join a stay. */
    private fun tidy(raw: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        var carry: Long? = null
        for ((k, s0) in raw.withIndex()) {
            val s = carry?.let { s0.copy(start = it) } ?: s0
            carry = null
            val prev = out.lastOrNull()
            if (s.kind == Motion.WALKING && s.millis < 60_000) {
                if (prev?.kind == Motion.STILL) {
                    out[out.lastIndex] = prev.copy(end = s.end)
                    continue
                }
                if (raw.getOrNull(k + 1)?.kind == Motion.STILL) {
                    carry = s.start
                    continue
                }
            }
            if (prev != null && prev.kind == s.kind && s.kind != Motion.FAST &&
                (s.kind == Motion.WALKING || TripFinder.metres(prev.lat, prev.lon, s.lat, s.lon) <= SAME_PLACE_M)
            ) {
                out[out.lastIndex] = join(prev, s)
                continue
            }
            out += s
        }
        return out
    }

    private fun join(a: Segment, b: Segment): Segment = if (a.kind == Motion.STILL) {
        val wa = a.millis.coerceAtLeast(1).toDouble()
        val wb = b.millis.coerceAtLeast(1).toDouble()
        val lat = (a.lat * wa + b.lat * wb) / (wa + wb)
        val lon = (a.lon * wa + b.lon * wb) / (wa + wb)
        a.copy(end = b.end, lat = lat, lon = lon, fromLat = lat, fromLon = lon)
    } else {
        a.copy(end = b.end, lat = b.lat, lon = b.lon, metres = a.metres + b.metres, top = maxOf(a.top, b.top))
    }
}

/**
 * Steps over a stretch of time from the step counter's [readings]. [before] is the last reading before
 * it, where the count starts; without it the count starts at the first reading. A reading lower than
 * the one before means the phone restarted, so the count carries on from 0.
 */
fun stepsIn(readings: List<Long>, before: Long?): Long {
    var steps = 0L
    var last = before ?: readings.firstOrNull() ?: return 0L
    for (r in readings) {
        steps += if (r >= last) r - last else r
        last = r
    }
    return steps
}
