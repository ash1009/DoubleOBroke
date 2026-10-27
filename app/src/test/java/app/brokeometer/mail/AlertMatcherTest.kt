package app.brokeometer.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMatcherTest {

    private fun entry(id: Long, account: Long, subject: String, action: String, from: String = "Priya") = MailLogEntity(
        id = id, at = 0, accountId = account, folder = MailFolders.INBOX, uid = id, messageId = "<$id@x>",
        fromName = from, fromAddress = "${from.lowercase()}@example.com", subject = subject,
        categoryId = 1, categoryName = "Real people", action = action, summary = "",
    )

    private val accounts = mapOf(1L to "me@gmail.com", 2L to "me@yahoo.co.uk")

    private fun alert(key: String, account: String?, title: String, text: String, summary: Boolean = false, pkg: String = "com.google.android.gm") =
        AppAlert(key, pkg, account, title, text, summary)

    @Test
    fun aYahooEmailShownByGmailIsCleared() {
        val entries = listOf(entry(10, 2, "Can you call me tonight?", MailActions.NOTIFIED))
        val r = AlertMatcher.match(
            listOf(alert("k1", "me@yahoo.co.uk", "Priya", "Can you call me tonight?")),
            entries, accounts,
        )
        assertEquals(listOf("k1"), r.clear)
        assertEquals(listOf(10L), r.logIds)
    }

    @Test
    fun undecidedEmailsLoseTheirNotificationToo() {
        // The app tells you about it with its own notification instead.
        val entries = listOf(entry(11, 1, "Interview on Monday", MailActions.UNDECIDED))
        val r = AlertMatcher.match(listOf(alert("k2", "me@gmail.com", "Recruiter", "Interview on Monday")), entries, accounts)
        assertEquals(listOf("k2"), r.clear)
    }

    @Test
    fun anAlertFindsItsEmail() {
        val entries = listOf(
            entry(20, 1, "Interview on Monday", MailActions.NOTIFIED, from = "Recruiter"),
            entry(21, 2, "Interview on Monday", MailActions.ARCHIVED, from = "Recruiter"),
            entry(22, 1, "Flash sale", MailActions.ARCHIVED, from = "Shop"),
        )
        val found = AlertMatcher.entriesFor(alert("k5", "Me@Gmail.com", "Recruiter", "Interview on Monday\nSee you at 10"), entries, accounts)
        assertEquals(listOf(20L), found.map { it.id })
        assertTrue(AlertMatcher.entriesFor(alert("k6", "me@gmail.com", "Mum", "Dinner on Sunday?"), entries, accounts).isEmpty())
    }

    @Test
    fun unknownEmailsAreLeftAlone() {
        val entries = listOf(entry(12, 1, "Flash sale", MailActions.ARCHIVED, from = "Shop"))
        val r = AlertMatcher.match(listOf(alert("k3", "me@gmail.com", "Mum", "Dinner on Sunday?")), entries, accounts)
        assertTrue(r.clear.isEmpty())
    }

    @Test
    fun theBundleGoesOnlyWhenNothingIsLeftUnderIt() {
        val entries = listOf(
            entry(13, 1, "Re: Flash sale 70% off", MailActions.ARCHIVED, from = "Shop"),
            entry(14, 1, "Your bill is ready", MailActions.NOTIFIED, from = "EE"),
        )
        val all = listOf(
            alert("a", "me@gmail.com", "Shop", "Flash sale 70% off"),
            alert("b", "me@gmail.com", "EE", "Your bill is ready"),
            alert("sum", "me@gmail.com", "2 new emails", "", summary = true),
        )
        assertEquals(listOf("a", "b", "sum"), AlertMatcher.match(all, entries, accounts).clear)

        val oneLeft = all + alert("c", "me@gmail.com", "Mum", "Dinner on Sunday?")
        assertEquals(listOf("a", "b"), AlertMatcher.match(oneLeft, entries, accounts).clear)
    }

    @Test
    fun noSubjectFallsBackToTheSender() {
        val entries = listOf(entry(15, 1, "", MailActions.READ_ELSEWHERE, from = "Mum"))
        val r = AlertMatcher.match(listOf(alert("k4", null, "Mum", "(no subject)")), entries, accounts)
        assertEquals(listOf("k4"), r.clear)
    }

    @Test
    fun theEmailAppsOwnStatusIsNotAnEmail() {
        assertTrue(AlertMatcher.isStatus("Syncing mail", ongoing = false))
        assertTrue(AlertMatcher.isStatus("Checking for mail…", ongoing = false))
        assertTrue(AlertMatcher.isStatus("Priya Shah", ongoing = true))
        assertFalse(AlertMatcher.isStatus("Priya Shah", ongoing = false))
        assertFalse(AlertMatcher.isStatus("Amazon.co.uk", ongoing = false))
    }
}
