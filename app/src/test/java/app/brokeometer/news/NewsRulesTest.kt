package app.brokeometer.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class NewsRulesTest {

    private val london = ZoneId.of("Europe/London")

    @Test
    fun theNextRunIsTodayOrTomorrow() {
        val morning = LocalDate.of(2026, 10, 19).atTime(6, 0).atZone(london)
        assertEquals(LocalDate.of(2026, 10, 19).atTime(7, 30).atZone(london), NewsRules.nextRun(morning, 7 * 60 + 30))
        val later = LocalDate.of(2026, 10, 19).atTime(9, 0).atZone(london)
        assertEquals(LocalDate.of(2026, 10, 20).atTime(7, 30).atZone(london), NewsRules.nextRun(later, 7 * 60 + 30))
    }

    @Test
    fun topicsAreAddedOnce() {
        val topics = listOf("UK", "Technology")
        assertEquals(listOf("UK", "Technology", "UK fintech"), NewsRules.addTopic(topics, "  UK   fintech "))
        assertEquals(topics, NewsRules.addTopic(topics, "technology"))
        assertEquals(topics, NewsRules.addTopic(topics, "   "))
    }

    @Test
    fun findsTheJsonInAnAnswer() {
        assertEquals("""{"sections":[]}""", NewsRules.jsonPart("Here it is: {\"sections\":[]} Hope it helps."))
        assertNull(NewsRules.jsonPart("No news today."))
    }

    @Test
    fun promptHasTheDateAndTopicsInOrder() {
        val p = NewsRules.prompt(listOf("UK", "AI"), LocalDate.of(2026, 10, 19))
        assertTrue(p, p.startsWith("Today is Monday 19 October 2026 in London."))
        assertTrue(p, p.endsWith("1. UK\n2. AI"))
    }

    @Test
    fun aBriefingIsDueEveryTwoHoursWhileAwake() {
        val h = 3_600_000L
        assertTrue(NewsRules.due(now = 10 * h, lastAt = 8 * h, everyHours = 2, awake = true))
        assertTrue(NewsRules.due(now = 10 * h - 5 * 60_000L, lastAt = 8 * h, everyHours = 2, awake = true))
        assertTrue(!NewsRules.due(now = 9 * h, lastAt = 8 * h, everyHours = 2, awake = true))
        assertTrue(!NewsRules.due(now = 20 * h, lastAt = 8 * h, everyHours = 2, awake = false))
        // Up after a long sleep: due straight away.
        assertTrue(NewsRules.due(now = 30 * h, lastAt = 20 * h, everyHours = 2, awake = true))
        assertTrue(NewsRules.awake(4 * 60_000L))
        assertTrue(!NewsRules.awake(40_000L))
    }

    @Test
    fun onlyNewsSinceTheLastOne() {
        val p = NewsRules.prompt(listOf("UK"), LocalDate.of(2026, 10, 19), since = "10:02 today")
        assertTrue(p, p.endsWith("My last briefing was at 10:02 today. Only give news from after that; a topic with nothing new gets no stories."))
    }

    @Test
    fun historyGoesByDayAndCanBeSearched() {
        val d1 = LocalDate.of(2026, 10, 18).atTime(20, 0).atZone(london).toInstant().toEpochMilli()
        val d2 = LocalDate.of(2026, 10, 19).atTime(10, 0).atZone(london).toInstant().toEpochMilli()
        val a = Briefing(d1, listOf(Section("UK", listOf(Story("Rates held", "", "", "")))))
        val b = Briefing(d2, listOf(Section("AI", listOf(Story("New model", "chips", "", "")))))
        val days = NewsRules.byDay(listOf(a, b), london)
        assertEquals(listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 18)), days.map { it.first })
        assertEquals(listOf(b), NewsRules.search(listOf(a, b), "CHIPS"))
        assertEquals(1, NewsRules.count(a))
    }

    @Test
    fun clockAndSteps() {
        assertEquals("07:30", NewsRules.clock(450))
        assertEquals(23 * 60 + 45, NewsRules.step(0, -1))
    }

    @Test
    fun headlinesTakeTheFirstStoryOfEachTopic() {
        val b = Briefing(0, listOf(
            Section("UK", listOf(Story("A", "", "", ""), Story("B", "", "", ""))),
            Section("AI", emptyList()),
            Section("World", listOf(Story("C", "", "", ""))),
        ))
        assertEquals(listOf("A", "C"), NewsRules.headlines(b))
    }
}
