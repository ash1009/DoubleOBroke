package app.brokeometer.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailPromptsTest {

    private val senders = listOf(
        SenderSummary("HSBC UK", "alerts@hsbc.co.uk", 120, false, listOf("New sign in", "Statement ready")),
        SenderSummary("", "deals@shop.com", 300, true, listOf("50% off today")),
    )

    @Test
    fun designPromptCarriesTheOwnersListAndSenders() {
        val prompt = MailPrompts.designCategories(senders)
        assertTrue(prompt.contains("Login codes I asked for"))
        assertTrue(prompt.contains("Not important: Newsletters"))
        assertTrue(prompt.contains("1. HSBC UK <alerts@hsbc.co.uk> | 120 emails | direct | New sign in ; Statement ready"))
        assertTrue(prompt.contains("2. <deals@shop.com> | 300 emails | mailing list | 50% off today"))
    }

    @Test
    fun readsCategoriesEvenInsideACodeFence() {
        val answer = """Here you go:
            ```json
            {"categories":[{"name":"Bank alerts","description":"Security","important":true},
            {"name":"Promos","description":"Offers","important":false},
            {"name":"bank alerts","description":"duplicate","important":false},
            {"name":"","description":"no name"}]}
            ```"""
        val ideas = MailPrompts.parseCategories(answer)
        assertEquals(listOf("Bank alerts", "Promos"), ideas.map { it.name })
        assertTrue(ideas[0].important)
        assertFalse(ideas[1].important)
    }

    @Test
    fun badAnswersGiveNothing() {
        assertEquals(emptyList<CategoryIdea>(), MailPrompts.parseCategories("sorry, I can't"))
        assertEquals(emptyMap<Int, Int>(), MailPrompts.parseAssignments("{oops", 2, 2))
        assertNull(MailPrompts.jsonIn("no json here"))
    }

    @Test
    fun assignmentsDropAnythingOutOfRange() {
        val prompt = MailPrompts.assignSenders(listOf("Bank" to "alerts", "Promos" to "offers"), senders)
        assertTrue(prompt.contains("s0. HSBC UK <alerts@hsbc.co.uk>"))
        assertTrue(prompt.contains("1. Promos: offers"))
        val map = MailPrompts.parseAssignments("""{"a":{"s0":0,"s1":1,"s2":0,"s1x":1,"s0b":9}}""", senders = 2, categories = 2)
        assertEquals(mapOf(0 to 0, 1 to 1), map)
    }

    @Test
    fun unsubscribeHeaders() {
        val both = UnsubscribeWays.parse("<mailto:leave@list.com?subject=unsubscribe>, <https://list.com/u/123>", oneClick = true)
        assertEquals("https://list.com/u/123", both.web)
        assertEquals("mailto:leave@list.com?subject=unsubscribe", both.mail)
        assertTrue(both.oneClick)

        val mailOnly = UnsubscribeWays.parse("<mailto:leave@list.com>", oneClick = true)
        assertNull(mailOnly.web)
        assertFalse(mailOnly.oneClick)

        val bare = UnsubscribeWays.parse("https://list.com/u/9", oneClick = false)
        assertEquals("https://list.com/u/9", bare.web)
        assertFalse(UnsubscribeWays.parse(null, true).any)
    }

    @Test
    fun tallyKeepsNewestNameAndListHeader() {
        val t = SenderTally("deals@shop.com")
        val recent = 1_000L
        t.add("Shop", at = 500, seen = true, subject = "Old sale", unsubscribe = "<https://old>", oneClick = false, recentSince = recent)
        t.add("Shop UK", at = 2_000, seen = false, subject = "New   sale", unsubscribe = "<https://new>", oneClick = true, recentSince = recent)
        t.add("", at = 1_500, seen = true, subject = "new sale", unsubscribe = null, oneClick = false, recentSince = recent)
        assertEquals("Shop UK", t.name)
        assertEquals(3, t.total)
        assertEquals(1, t.unread)
        assertEquals(2, t.recent)
        assertEquals(500L, t.firstAt)
        assertEquals(2_000L, t.lastAt)
        assertEquals("<https://new>", t.unsubscribe)
        assertTrue(t.oneClick)
        assertEquals(listOf("New sale", "Old sale"), t.recentSubjects())
    }

    @Test
    fun guessesTheProvider() {
        assertEquals(MailProviders.GMAIL, MailProviders.guess("Someone@Gmail.com"))
        assertEquals(MailProviders.YAHOO, MailProviders.guess("me@yahoo.co.uk"))
        assertNull(MailProviders.guess("me@work.com"))
    }

    private val item = MailItem(
        uid = 7, messageId = "<a@b>", fromName = "HSBC UK", fromAddress = "alerts@hsbc.co.uk", to = "me@gmail.com",
        subject = "Security alert: new sign in", receivedAt = 0, seen = false, text = "We noticed a new sign in.",
    )
    private val categories = listOf(
        MailCategoryEntity(1, "Real people", "Written by a person", true, 0),
        MailCategoryEntity(2, "Promotions", "Sales", false, 1),
    )

    @Test
    fun decisionPromptAndAnswer() {
        val prompt = MailPrompts.decideEmail(categories, item)
        assertTrue(prompt.contains("0. Real people: Written by a person"))
        // The email is judged on what it says: the sender's usual kind isn't given.
        assertTrue(!prompt.contains("Usual category"))
        assertTrue(prompt.contains("From: HSBC UK <alerts@hsbc.co.uk>"))
        assertTrue(prompt.contains("We noticed a new sign in."))
        assertEquals(MailPrompts.Decision(1, "Sale"), MailPrompts.parseDecision("""{"category":1,"summary":" Sale "}""", 2))
        // A promotions sender writing about your own viewing request: personal, so it reaches you.
        assertEquals(
            MailPrompts.Decision(1, "Viewing declined", personal = true),
            MailPrompts.parseDecision("""{"category":1,"personal":true,"summary":"Viewing declined"}""", 2),
        )
        assertEquals(setOf(1, 3), MailPrompts.parsePersonal("""{"a":{"s0":0},"p":["s1","s3","s9"]}""", 5))
        assertEquals(emptySet<Int>(), MailPrompts.parsePersonal("""{"a":{"s0":0}}""", 5))
        assertNull(MailPrompts.parseDecision("""{"category":5,"summary":"x"}""", 2))
        assertNull(MailPrompts.parseDecision("nope", 2))
    }

    @Test
    fun aDatedTaskComesBackWithTheDecision() {
        val prompt = MailPrompts.decideEmail(categories, item, java.time.LocalDate.of(2026, 10, 25))
        assertTrue(prompt.contains("Today is Sunday 25 October 2026 (2026-10-25)"))
        assertTrue(prompt.contains("\"task\":null"))
        val d = MailPrompts.parseDecision(
            """{"category":0,"summary":"Assessment invite","task":{"what":"Finish the WTW assessment","day":"2026-10-30","time":"17:00"}}""", 2,
        )
        assertEquals(MailPrompts.TaskHint("Finish the WTW assessment", "2026-10-30", "17:00"), d?.task)
        // No real day, or no task: none.
        assertNull(MailPrompts.parseDecision("""{"category":0,"summary":"x","task":{"what":"Reply","day":"soon"}}""", 2)?.task)
        assertNull(MailPrompts.parseDecision("""{"category":0,"summary":"x","task":null}""", 2)?.task)
        assertEquals(MailPrompts.TaskHint("Pay the bill", "2026-11-01"), MailPrompts.parseTask(org.json.JSONObject("""{"what":"Pay the bill","day":"2026-11-01","time":""}""")))
    }

    @Test
    fun spamAnswersLeanTowardsKeeping() {
        assertTrue(MailPrompts.checkSpam(item).startsWith("This email is in the spam folder"))
        assertEquals(MailPrompts.SpamVerdict(true, "Scam"), MailPrompts.parseSpam("""{"spam":true,"why":"Scam"}"""))
        assertEquals(false, MailPrompts.parseSpam("""{"spam":"maybe","why":"?"}""")?.spam)
        assertNull(MailPrompts.parseSpam("""{"why":"no verdict"}"""))
    }

    @Test
    fun htmlBecomesPlainWords() {
        val html = "<html><head><style>p{color:red}</style></head><body><p>Hello&nbsp;Alex,</p>" +
            "<div>Your code is <b>482913</b></div><script>x()</script></body></html>"
        assertEquals("Hello Alex,\nYour code is 482913", MailText.tidy(MailText.fromHtml(html)))
    }

    @Test
    fun assignmentsInTheShapesAIsActuallySend() {
        assertEquals(mapOf(0 to 1, 1 to 0), MailPrompts.parseAssignments("""{"s0":1,"s1":0}""", 2, 2))
        assertEquals(mapOf(0 to 1, 1 to 0), MailPrompts.parseAssignments("""{"a":[["s0",1],[1,0]]}""", 2, 2))
        assertEquals(
            mapOf(0 to 1),
            MailPrompts.parseAssignments("""{"a":[{"sender":"s0","category":"Promos"}]}""", 2, 2, listOf("Bank", "Promos")),
        )
        assertEquals(mapOf(0 to 1), MailPrompts.parseAssignments("""{"a":{"0":"1"}}""", 2, 2))
    }
}
