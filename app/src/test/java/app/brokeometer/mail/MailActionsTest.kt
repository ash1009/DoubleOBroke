package app.brokeometer.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailActionsTest {

    private fun entry(action: String, folder: String = MailFolders.INBOX, undone: Boolean = false) = MailLogEntity(
        id = 1, at = 0, accountId = 1, folder = folder, uid = 1, messageId = "<1@x>",
        fromName = "EE", fromAddress = "bills@ee.co.uk", subject = "Your bill is ready",
        categoryId = 1, categoryName = "Bills", action = action, summary = "", undone = undone,
    )

    @Test
    fun emailsLeftUnreadCanBeMarkedRead() {
        val a = MailActions
        val unread = listOf(
            entry(a.NOTIFIED), entry(a.KEPT), entry(a.UNDECIDED),
            entry(a.NOT_SPAM, MailFolders.SPAM), entry(a.MOVED_FROM_SPAM, MailFolders.SPAM),
            entry(a.ARCHIVED, undone = true), entry(a.SPAM_DELETED, MailFolders.SPAM, undone = true),
        )
        unread.forEach { assertTrue(it.action, a.leftUnread(it)) }
        val done = listOf(
            entry(a.ARCHIVED), entry(a.READ_ELSEWHERE), entry(a.MARKED_READ),
            entry(a.SPAM_DELETED, MailFolders.SPAM), entry(a.SPAM_FLAGGED, MailFolders.SPAM),
        )
        done.forEach { assertFalse(it.action, a.leftUnread(it)) }
    }

    @Test
    fun theListMatchesTheEmailApp() {
        val a = MailActions
        // Read in Gmail: read here, and an email left unread here follows.
        assertEquals(a.MARKED_READ, a.matching(entry(a.NOTIFIED), MailPlace.INBOX_READ))
        assertEquals(a.MARKED_READ, a.matching(entry(a.KEPT), MailPlace.INBOX_READ))
        assertEquals(null, a.matching(entry(a.READ_ELSEWHERE), MailPlace.INBOX_READ))
        // Archiving didn't go through: still in the inbox, read or unread.
        assertEquals(a.MARKED_READ, a.matching(entry(a.ARCHIVED), MailPlace.INBOX_READ))
        assertEquals(a.MARKED_UNREAD, a.matching(entry(a.ARCHIVED), MailPlace.INBOX_UNREAD))
        // Unread again in Gmail.
        assertEquals(a.MARKED_UNREAD, a.matching(entry(a.MARKED_READ), MailPlace.INBOX_UNREAD))
        assertEquals(null, a.matching(entry(a.NOTIFIED), MailPlace.INBOX_UNREAD))
        // Archived or deleted in Gmail: out of Needs you; already done here: left as it is.
        assertEquals(a.ARCHIVED, a.matching(entry(a.NOTIFIED), MailPlace.ARCHIVED))
        assertEquals(a.DELETED, a.matching(entry(a.UNDECIDED), MailPlace.GONE))
        assertEquals(null, a.matching(entry(a.ARCHIVED), MailPlace.ARCHIVED))
        assertEquals(null, a.matching(entry(a.MARKED_READ), MailPlace.GONE))
    }

    @Test
    fun onlyInboxEmailsOfferTheSenderChoice() {
        val a = MailActions
        assertTrue(a.inInbox(entry(a.KEPT)))
        assertTrue(a.inInbox(entry(a.NOT_SPAM, MailFolders.SPAM)))
        assertTrue(a.inInbox(entry(a.SPAM_DELETED, MailFolders.SPAM, undone = true)))
        assertFalse(a.inInbox(entry(a.SPAM_FLAGGED, MailFolders.SPAM)))
        assertFalse(a.inInbox(entry(a.SPAM_DELETED, MailFolders.SPAM)))
    }

    @Test
    fun markedReadEmailsLoseTheirEmailAppNotification() {
        val e = entry(MailActions.MARKED_READ).copy(id = 7)
        val r = AlertMatcher.match(
            listOf(AppAlert("k", "com.google.android.gm", "me@gmail.com", "EE", "Your bill is ready", false)),
            listOf(e), mapOf(1L to "me@gmail.com"),
        )
        assertEquals(listOf("k"), r.clear)
    }
}
