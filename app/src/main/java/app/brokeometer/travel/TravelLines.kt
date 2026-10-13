package app.brokeometer.travel

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A line for Home about the day so far, a little cheeky: a nudge to walk after a long time still,
 * a well done after a lot of walking. Made on the phone from simple rules, so nothing is sent anywhere.
 */
object TravelLines {

    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    fun pick(segments: List<Segment>, steps: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochMilli(now).atZone(zone)
        val hour = time.hour
        // A different wording each day, so it doesn't read the same every time.
        val turn = time.dayOfYear % 2
        val walked = segments.filter { it.kind == Motion.WALKING }.sumOf { it.metres }
        val last = segments.lastOrNull()
        val ongoing = last != null && now - last.end < 10 * 60_000L

        if (segments.isEmpty()) {
            return if (hour < 11) {
                listOf("Fresh day. Where are we off to?", "Nothing noted yet. The day is young.")[turn]
            } else {
                "Nothing noted yet today."
            }
        }
        if (ongoing && last!!.kind == Motion.FAST) {
            return listOf("On the move. Mind the gap.", "Off somewhere. Enjoy the ride.")[turn]
        }
        if (ongoing && last!!.kind == Motion.STILL && now - last.start >= 2 * 3_600_000L && hour in 9..21) {
            val since = Instant.ofEpochMilli(last.start).atZone(zone).format(clock)
            return listOf(
                "You've been still since $since. Fancy a 10 minute walk?",
                "Still since $since. Your legs are asking for a stroll.",
            )[turn]
        }
        if (steps != null && steps >= 10_000) {
            return listOf("Over 10,000 steps today. Legend.", "${"%,d".format(steps)} steps. Your shoes are working hard.")[turn]
        }
        if (walked >= 5_000) {
            return listOf(
                "${km(walked)} on foot today. Your legs have earned a sit down.",
                "${km(walked)} walked. That's a proper day out.",
            )[turn]
        }
        if (hour >= 18 && walked < 1_000) {
            return listOf(
                "Only ${distance(walked)} on foot today. A short evening walk?",
                "A quiet day for your feet. Ten minutes outside, maybe?",
            )[turn]
        }
        return when {
            steps != null && steps > 0 -> listOf("${"%,d".format(steps)} steps so far. Keep them coming.", "${"%,d".format(steps)} steps and counting.")[turn]
            walked > 0 -> "${distance(walked)} walked so far today."
            else -> "Not much walking yet today."
        }
    }

    private fun km(metres: Double) = "%.1f km".format(Locale.UK, metres / 1_000)

    private fun distance(metres: Double) = if (metres < 1_000) "${(metres / 10).toInt() * 10} m" else km(metres)
}
