package app.brokeometer.news

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One story in a briefing. */
data class Story(val headline: String, val summary: String, val source: String, val url: String)

/** One topic's stories. */
data class Section(val topic: String, val stories: List<Story>)

/** A day's briefing: made at [at], for these topics. */
data class Briefing(val at: Long, val sections: List<Section>)

/** The rules of the news briefing, with no Android in them so they can be tested on their own. */
object NewsRules {

    /** Topics to pick from, like a news app's sections. Your own topics can be added too. */
    val PRESETS = listOf(
        "Top stories", "UK", "World", "London", "Politics", "Business", "Money", "Technology", "AI",
        "Science", "Health", "Climate", "Sport", "Football", "Entertainment", "Travel",
    )

    /** Stories per topic, at most. */
    const val PER_TOPIC = 3

    /** Briefings kept on the phone: all of them, up to about a year's worth. */
    const val KEEP = 3000

    /** How often, in hours, while you're awake: the choices and the usual one. */
    val EVERY_HOURS = 1..6
    const val DEFAULT_EVERY = 2

    /** Screen time in the last half hour that means you're awake (a glance at the time isn't). */
    const val AWAKE_MS = 3 * 60_000L
    const val AWAKE_WINDOW_MS = 30 * 60_000L

    /** Whether you count as awake: a few minutes on the phone in the last half hour. */
    fun awake(screenMsLastHalfHour: Long): Boolean = screenMsLastHalfHour >= AWAKE_MS

    /**
     * Whether a briefing is due: you're awake and the last one (or the last try) was [everyHours] ago or
     * more. After sleeping, the first time you're up it's always due, so a briefing waits for you.
     * A few minutes early is close enough, as the check runs every quarter of an hour.
     */
    fun due(now: Long, lastAt: Long, everyHours: Int, awake: Boolean): Boolean =
        awake && now - lastAt >= everyHours * 3_600_000L - 10 * 60_000L

    /** Briefings by day, newest first: (yyyy-MM-dd, that day's briefings). */
    fun byDay(briefings: List<Briefing>, zone: ZoneId): List<Pair<LocalDate, List<Briefing>>> =
        briefings.sortedByDescending { it.at }
            .groupBy { java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            .toList()

    /** Briefings with [query] in a topic, headline or summary. */
    fun search(briefings: List<Briefing>, query: String): List<Briefing> {
        val q = query.trim()
        if (q.isEmpty()) return briefings
        return briefings.filter { b ->
            b.sections.any { s -> s.topic.contains(q, true) || s.stories.any { it.headline.contains(q, true) || it.summary.contains(q, true) } }
        }
    }

    /** How many stories a briefing has. */
    fun count(b: Briefing): Int = b.sections.sumOf { it.stories.size }

    /** The next time the briefing is due: [minuteOfDay] today, or tomorrow if that has passed (or is less than a minute away). */
    fun nextRun(now: ZonedDateTime, minuteOfDay: Int): ZonedDateTime {
        var next = now.toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(now.zone)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        return next
    }

    /** "07:30". */
    fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** A time [steps] quarter hours on (or back), round the clock. */
    fun step(minuteOfDay: Int, steps: Int): Int = ((minuteOfDay + steps * 15) % (24 * 60) + 24 * 60) % (24 * 60)

    /** Adds a topic typed in, unless it's blank or already there (in any case). */
    fun addTopic(topics: List<String>, typed: String): List<String> {
        val t = typed.trim().replace(Regex("\\s+"), " ").take(60)
        if (t.isEmpty() || topics.any { it.equals(t, ignoreCase = true) }) return topics
        return topics + t
    }

    /** The JSON object in Claude's answer: from the first "{" to the last "}", or null when there is none. */
    fun jsonPart(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    val SYSTEM = """
        You write a short daily news briefing for one person who lives in London.
        Use web search to find the most important news on each topic you are given: from the last 24 hours, or since the last briefing when you are told when that was.
        Write in plain, simple English, as a friend would explain it. Never use em dashes or en dashes.
        Give each story a short headline, one or two sentences on what happened and why it matters, the name of the source, and the link to the article you read.
        Give at most $PER_TOPIC stories per topic, fewer when little happened, and never the same story twice. No opinions.
        Reply with only JSON, no other words, in this shape:
        {"sections":[{"topic":"the topic as given","stories":[{"headline":"...","summary":"...","source":"...","url":"https://..."}]}]}
    """.trimIndent()

    /**
     * What Claude is asked for: today's date in London, the topics in order, and when the last briefing
     * was ([since], "10:02 today", say), so only news after it comes.
     */
    fun prompt(topics: List<String>, today: LocalDate, since: String? = null): String {
        val date = today.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK))
        val window = if (since == null) "" else
            "\nMy last briefing was at $since. Only give news from after that; a topic with nothing new gets no stories."
        return "Today is $date in London. My topics, in this order:\n" + topics.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n") + window
    }

    /** The headlines for the notification: the first story of each topic, a few at most. */
    fun headlines(b: Briefing, max: Int = 4): List<String> =
        b.sections.mapNotNull { it.stories.firstOrNull()?.headline }.filter { it.isNotBlank() }.take(max)

    val LONDON: ZoneId = ZoneId.of("Europe/London")
}
