package app.brokeometer.mail

import app.brokeometer.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * One signed-in connection per account, kept open for a minute after use, for the things you do: opening
 * an email, marking it read or unread, starring, archiving. Signing in takes a second or two, so doing one
 * after another on an open connection is quick. Used by one thing at a time, in the order asked.
 */
class MailSessions(
    private val prefs: Prefs,
    private val mail: MailAgent,
    private val client: MailClient = MailClient(),
) {
    private class Warm(val session: MailSession)

    private val warm = ConcurrentHashMap<Long, Warm>()
    private val turns = ConcurrentHashMap<Long, Mutex>()
    private val closers = ConcurrentHashMap<Long, Job>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs [block] on the account's open connection, signing in only when there isn't one. A connection
     * the server had closed is replaced and [block] tried once more.
     */
    suspend fun <T> use(account: MailAccountEntity, block: suspend (MailSession) -> T): T {
        val turn = turns.getOrPut(account.id) { Mutex() }
        return turn.withLock {
            withContext(Dispatchers.IO) {
                closers.remove(account.id)?.cancel()
                val reused = warm[account.id]?.session?.takeIf { it.isOpen }
                val result = try {
                    block(reused ?: connect(account))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (reused == null || e is MailClient.MailError) throw e
                    drop(account.id)
                    block(connect(account))
                }
                closers[account.id] = background.launch {
                    delay(KEEP_OPEN_MS)
                    turn.withLock { drop(account.id) }
                }
                result
            }
        }
    }

    private fun connect(account: MailAccountEntity): MailSession {
        drop(account.id)
        val s = client.session(mail.server(account), account.address, prefs.mailPassword(account.address), account.provider)
        warm[account.id] = Warm(s)
        return s
    }

    private fun drop(accountId: Long) {
        warm.remove(accountId)?.let { runCatching { it.session.close() } }
    }

    companion object {
        /** How long a connection stays open after it was last used. */
        private const val KEEP_OPEN_MS = 60_000L
    }
}
