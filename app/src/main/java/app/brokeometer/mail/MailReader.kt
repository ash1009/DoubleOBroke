package app.brokeometer.mail

import app.brokeometer.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.activation.DataHandler
import javax.mail.Message
import javax.mail.Part
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import javax.mail.Session
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Opens whole emails for reading in the app, and sends replies and new emails with the account's app password.
 * Emails opened or fetched ahead are kept on the phone (in the app's cache), so they open at once next
 * time, even after the app was closed. One connection per account is kept open for a minute after use,
 * so opening one email after another doesn't sign in each time.
 */
class MailReader(
    private val dao: MailDao,
    private val prefs: Prefs,
    private val mail: MailAgent,
    cacheDir: File? = null,
    private val client: MailClient = MailClient(),
    /** The open connections shared with the things you do to emails (marking read, starring). */
    private val sessions: MailSessions = MailSessions(prefs, mail, client),
) {
    sealed interface Opened {
        data class Email(val email: FullEmail) : Opened
        data class Problem(val message: String) : Opened
    }

    // Emails opened or fetched ahead lately, kept in memory so opening them again is instant.
    private val cache = object : LinkedHashMap<Long, FullEmail>(16, 0.75f, true) {}
    private var cacheChars = 0L
    private val cacheLock = Any()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetching = AtomicBoolean(false)
    @Volatile private var lastPrefetch = 0L

    private val disk = cacheDir?.let { MailDiskCache(it) }

    /** An email kept in memory, or on the phone (slow: not on the main thread). */
    private fun cached(id: Long): FullEmail? =
        synchronized(cacheLock) { cache[id] } ?: disk?.read(id)?.also { remember(id, it) }

    private fun keep(id: Long, email: FullEmail) {
        if (sizeOf(email) > MAX_ONE_CHARS) return
        remember(id, email)
        disk?.write(id, email)
    }

    private fun remember(id: Long, email: FullEmail) {
        val size = sizeOf(email)
        if (size > MAX_ONE_CHARS) return
        synchronized(cacheLock) {
            cache.remove(id)?.let { cacheChars -= sizeOf(it) }
            cache[id] = email
            cacheChars += size
            val oldestFirst = cache.entries.iterator()
            while ((cache.size > MAX_EMAILS || cacheChars > MAX_CHARS) && oldestFirst.hasNext()) {
                val oldest = oldestFirst.next()
                if (oldest.key == id) continue
                cacheChars -= sizeOf(oldest.value)
                oldestFirst.remove()
            }
        }
    }

    private fun sizeOf(e: FullEmail): Long = (e.html?.length ?: 0).toLong() + (e.text?.length ?: 0)

    /** The kept copy, if it is this email (an id could be used again after the list is trimmed). */
    private fun cachedFor(log: MailLogEntity): FullEmail? =
        cached(log.id)?.takeIf { it.messageId == null || log.messageId == null || it.messageId == log.messageId }

    /** Runs [block] on the account's open connection (see [MailSessions]). */
    private suspend fun <T> withSession(account: MailAccountEntity, block: (MailSession) -> T): T =
        sessions.use(account) { block(it) }

    suspend fun open(log: MailLogEntity): Opened {
        synchronized(cacheLock) { cache[log.id] }?.takeIf { it.messageId == null || log.messageId == null || it.messageId == log.messageId }
            ?.let { return Opened.Email(it) }
        return withContext(Dispatchers.IO) {
            cachedFor(log)?.let { return@withContext Opened.Email(it) }
            val account = dao.account(log.accountId) ?: return@withContext Opened.Problem("This account isn't in the app any more.")
            try {
                withSession(account) { s -> s.full(log.messageId, log.uid, log.folder) }?.let {
                    keep(log.id, it)
                    Opened.Email(it)
                } ?: Opened.Problem("This email isn't in the mailbox any more. It may have been deleted.")
            } catch (e: MailClient.MailError) {
                Opened.Problem(e.message ?: "Couldn't reach the mailbox.")
            } catch (e: Exception) {
                Opened.Problem("Couldn't open it (${e.javaClass.simpleName}). Check your connection and try again.")
            }
        }
    }

    /**
     * Fetches the emails you're most likely to open next (the ones that need you, then the newest) in the
     * background, one sign-in per account, so they open straight away. Fetching only reads; nothing is
     * marked read. At most once every few minutes.
     */
    fun prefetchRecent() {
        if (System.currentTimeMillis() - lastPrefetch < PREFETCH_EVERY_MS) return
        if (!prefetching.compareAndSet(false, true)) return
        lastPrefetch = System.currentTimeMillis()
        background.launch {
            try {
                val recent = dao.recentLogs(200)
                val needsYou = recent.filter { MailActions.attention(it) != null }.sortedBy { MailActions.attention(it) }
                val newest = recent.filter { it.action != MailActions.SPAM_DELETED }
                prefetch((needsYou + newest).distinctBy { it.id }.take(PREFETCH_COUNT))
            } finally {
                prefetching.set(false)
            }
        }
    }

    /** Fetches these emails ahead (those not already here), for swiping through them. */
    fun prefetch(ids: List<Long>) {
        background.launch { prefetch(dao.logs(ids)) }
    }

    // One email at a time, so an email you open goes ahead of the rest of these.
    private suspend fun prefetch(logs: List<MailLogEntity>) {
        val wanted = logs.filter { cachedFor(it) == null }
        for ((accountId, list) in wanted.groupBy { it.accountId }) {
            val account = dao.account(accountId) ?: continue
            for (log in list) {
                if (cachedFor(log) != null) continue
                val email = try {
                    withSession(account) { s -> s.full(log.messageId, log.uid, log.folder) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: MailClient.MailError) {
                    // Can't sign in or reach it now: the rest of this account waits for next time.
                    break
                } catch (e: Exception) {
                    null
                }
                email?.let { keep(log.id, it) }
            }
        }
    }

    /** Emails matching [query] anywhere in the account's mailbox, newest first. Throws when it can't be reached. */
    suspend fun search(account: MailAccountEntity, query: String, max: Int = SEARCH_MAX): List<FoundItem> =
        withSession(account) { s -> s.search(query, max) }

    /**
     * Emails from the last [days] days, newest first, as the agent sees them. Throws when it can't be
     * reached. A long read, so on a connection of its own: opening an email doesn't wait for it.
     */
    suspend fun recent(account: MailAccountEntity, days: Long, max: Int): List<MailItem> = withContext(Dispatchers.IO) {
        client.session(mail.server(account), account.address, prefs.mailPassword(account.address), account.provider, background = true)
            .use { s -> s.recent(days, max) }
    }

    /** An attached file of an email, saved in the app's cache to open with another app. */
    data class Saved(val file: File, val type: String)

    suspend fun attachment(log: MailLogEntity, index: Int, dir: File): Saved? = withContext(Dispatchers.IO) {
        val account = dao.account(log.accountId) ?: return@withContext null
        try {
            val found = withSession(account) { s -> s.attachment(log.messageId, log.uid, log.folder, index) } ?: return@withContext null
            dir.mkdirs()
            // Files opened before are cleared out after a day.
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
            val safe = found.first.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(120).ifBlank { "attachment" }
            val file = File(dir, safe)
            file.writeBytes(found.third)
            Saved(file, found.second)
        } catch (e: Exception) {
            null
        }
    }

    /** A file to attach to an email being written. */
    class Attachment(val name: String, val type: String, val bytes: ByteArray)

    /** An email to send. [replyTo] is the email being answered, if any; it is quoted below. */
    data class Draft(
        val accountId: Long,
        val to: String,
        val cc: String,
        val subject: String,
        val body: String,
        val replyTo: FullEmail?,
        val attachments: List<Attachment> = emptyList(),
    )

    /** Sends the email and files it in Sent. Null when it went, otherwise what went wrong. */
    suspend fun send(d: Draft): String? = withContext(Dispatchers.IO) {
        val account = dao.account(d.accountId) ?: return@withContext "Pick the account to send from."
        val to = addresses(d.to) ?: return@withContext "Check the To address."
        if (to.isEmpty()) return@withContext "Add who it's to."
        val cc = addresses(d.cc) ?: return@withContext "Check the Cc address."
        val password = prefs.mailPassword(account.address)
        if (password.isEmpty()) return@withContext "This account has no app password saved. Add it again under Setup."
        try {
            val imap = mail.server(account)
            val sent = client.send(MailProviders.smtp(account.provider, imap), account.address, password) { session ->
                build(session, account, d, to, cc)
            }
            // Gmail files sent email by itself; others are checked and filed if needed.
            if (account.provider != MailProviders.GMAIL) {
                runCatching { client.session(imap, account.address, password, account.provider).use { it.fileSent(sent) } }
            }
            null
        } catch (e: MailClient.MailError) {
            e.message ?: "Couldn't send it."
        } catch (e: Exception) {
            "Couldn't send it (${e.javaClass.simpleName}). Check your connection and try again."
        }
    }

    private fun addresses(text: String): Array<InternetAddress>? {
        val clean = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return try {
            clean.map { InternetAddress(it, true) }.toTypedArray()
        } catch (e: AddressException) {
            null
        }
    }

    private fun build(
        session: Session,
        account: MailAccountEntity,
        d: Draft,
        to: Array<InternetAddress>,
        cc: Array<InternetAddress>,
    ): MimeMessage {
        val domain = account.address.substringAfter('@')
        // The Message-ID names the account's own domain, as a normal email app would.
        val m = object : MimeMessage(session) {
            override fun updateMessageID() {
                setHeader("Message-ID", "<${UUID.randomUUID()}@$domain>")
            }
        }
        val name = prefs.username.value.trim()
        m.setFrom(if (name.isNotEmpty()) InternetAddress(account.address, name, "UTF-8") else InternetAddress(account.address))
        m.setRecipients(Message.RecipientType.TO, to)
        if (cc.isNotEmpty()) m.setRecipients(Message.RecipientType.CC, cc)
        m.setSubject(d.subject.trim(), "UTF-8")
        m.sentDate = Date()
        val original = d.replyTo
        val text = if (original == null) d.body else d.body.trimEnd() + "\n\n" + quote(original)
        if (d.attachments.isEmpty()) {
            m.setText(text, "UTF-8")
        } else {
            val parts = MimeMultipart()
            parts.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
            for (a in d.attachments) {
                parts.addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(a.bytes, a.type))
                        fileName = a.name
                        disposition = Part.ATTACHMENT
                    }
                )
            }
            m.setContent(parts)
        }
        original?.messageId?.let { id ->
            m.setHeader("In-Reply-To", id)
            m.setHeader("References", listOfNotNull(original.references?.trim()?.takeIf { it.isNotEmpty() }, id).joinToString(" "))
        }
        m.saveChanges()
        return m
    }

    companion object {
        private const val QUOTE_LINES = 300

        /** Emails kept in memory, and how much text in all (about 2 bytes a character). */
        private const val MAX_EMAILS = 40
        private const val MAX_CHARS = 8_000_000L
        private const val MAX_ONE_CHARS = 3_000_000L
        private const val PREFETCH_COUNT = 20
        /** Emails a search of the whole mailbox brings back, per account. */
        private const val SEARCH_MAX = 30
        private const val PREFETCH_EVERY_MS = 5 * 60_000L

        /** "On Sun, 11 Oct 2026 at 07:42, Priya <priya@x.com> wrote:" and the email, each line starting with ">". */
        fun quote(e: FullEmail): String {
            val day = SimpleDateFormat("EEE, d MMM yyyy 'at' HH:mm", Locale.UK).format(Date(e.date))
            val who = if (e.fromName.isNotBlank()) "${e.fromName} <${e.fromAddress}>" else e.fromAddress
            val text = e.text?.replace("\r", "") ?: e.html?.let { MailText.tidy(MailText.fromHtml(it)) }.orEmpty()
            val lines = text.lines().take(QUOTE_LINES).joinToString("\n") { "> $it" }
            return "On $day, $who wrote:\n$lines"
        }

        fun replySubject(subject: String): String =
            if (subject.trim().startsWith("re:", ignoreCase = true)) subject.trim() else "Re: ${subject.trim()}"
    }
}
