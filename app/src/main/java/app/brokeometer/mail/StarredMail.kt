package app.brokeometer.mail

import app.brokeometer.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Important page: starred emails, and in Gmail ones marked important. Copied from each mailbox
 * now and then, and starring or unstarring in the app changes the mailbox too (in Gmail, starring
 * also marks the email important, and unstarring takes that off), so Gmail and Yahoo match the app.
 */
class StarredMail(
    private val dao: MailDao,
    private val prefs: Prefs,
    private val mail: MailAgent,
    private val client: MailClient = MailClient(),
) {
    private val lock = Mutex()

    /** When the page was last matched with the mailboxes. */
    @Volatile var syncedAt = 0L
        private set

    /** Matches the page with each account's mailbox (or just [accountId]'s). Null when it went fine, otherwise the problem. */
    suspend fun sync(accountId: Long? = null): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            var problem: String? = null
            for (account in dao.accounts()) {
                if (accountId != null && account.id != accountId) continue
                val password = prefs.mailPassword(account.address)
                if (password.isEmpty()) continue
                try {
                    val found = client.session(mail.server(account), account.address, password, account.provider, background = true).use { it.marked(MAX) }
                    save(account.id, found)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem = "Couldn't check ${account.address} for starred emails. Pull down to try again."
                }
            }
            syncedAt = System.currentTimeMillis()
            problem
        }
    }

    /** Keeps each email's row (and so its place on the page), adds new ones and drops ones no longer starred. */
    private suspend fun save(accountId: Long, found: List<MarkedItem>) {
        val have = dao.starredOf(accountId).associateBy { it.messageId }
        for (f in found) {
            val old = have[f.messageId]
            val row = MailStarredEntity(
                id = old?.id ?: 0,
                accountId = accountId,
                messageId = f.messageId,
                fromName = f.fromName,
                fromAddress = f.fromAddress,
                subject = f.subject,
                at = f.receivedAt,
                seen = f.seen,
                starred = f.starred,
                important = f.important,
            )
            if (old == null) dao.insertStarred(row) else if (old != row) dao.updateStarred(row)
        }
        val keep = found.map { it.messageId }.toSet()
        val gone = have.values.filter { it.messageId !in keep }.map { it.id }
        if (gone.isNotEmpty()) dao.deleteStarred(gone)
    }

    /** What the page needs to show an email starred in the app before the next match. */
    data class About(val fromName: String, val fromAddress: String, val subject: String, val at: Long, val seen: Boolean)

    /** Stars or unstars an email in its mailbox, then on the page. False when the mailbox couldn't be changed. */
    suspend fun setStar(accountId: Long, messageId: String, on: Boolean, about: About): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val account = dao.account(accountId) ?: return@withLock false
            val password = prefs.mailPassword(account.address)
            if (password.isEmpty()) return@withLock false
            val changed = try {
                client.session(mail.server(account), account.address, password, account.provider).use { it.setStar(messageId, on) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!changed) return@withLock false
            val old = dao.starredFor(accountId, messageId)
            if (on) {
                val row = MailStarredEntity(
                    id = old?.id ?: 0,
                    accountId = accountId,
                    messageId = messageId,
                    fromName = about.fromName,
                    fromAddress = about.fromAddress,
                    subject = about.subject,
                    at = about.at,
                    seen = about.seen,
                    starred = true,
                    important = account.provider == MailProviders.GMAIL,
                )
                if (old == null) dao.insertStarred(row) else dao.updateStarred(row)
            } else if (old != null) {
                dao.deleteStarred(listOf(old.id))
            }
            true
        }
    }

    companion object {
        /** The newest starred emails kept per account, and the newest important ones (Gmail). */
        const val MAX = 300

        /** How often opening the page matches it with the mailboxes; pulling down always does. */
        const val SYNC_EVERY_MS = 10 * 60_000L
    }
}
