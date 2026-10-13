package app.brokeometer.mail

import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.io.IOException
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Where an account's mail lives. */
data class MailServer(val host: String, val port: Int, val ssl: Boolean)

object MailProviders {
    const val GMAIL = "gmail"
    const val YAHOO = "yahoo"
    const val CUSTOM = "custom"

    val servers = mapOf(
        GMAIL to MailServer("imap.gmail.com", 993, true),
        YAHOO to MailServer("imap.mail.yahoo.com", 993, true),
    )

    /** Where an account sends email from. Test servers send on their SMTP port. */
    fun smtp(provider: String, imap: MailServer): MailServer = when (provider) {
        GMAIL -> MailServer("smtp.gmail.com", 465, true)
        YAHOO -> MailServer("smtp.mail.yahoo.com", 465, true)
        else -> MailServer(imap.host, if (imap.port == 3143) 3025 else 587, imap.ssl)
    }

    /** Gmail or Yahoo, from the address. Google Workspace addresses can be picked by hand. */
    fun guess(address: String): String? {
        val domain = address.substringAfterLast('@', "").lowercase(Locale.ROOT)
        return when {
            domain == "gmail.com" || domain == "googlemail.com" -> GMAIL
            domain.startsWith("yahoo.") || domain == "ymail.com" || domain == "rocketmail.com" -> YAHOO
            else -> null
        }
    }
}

/** One sender's emails, added up while the mailbox is read. */
class SenderTally(val address: String) {
    var name = ""
    var total = 0
    var unread = 0
    var recent = 0
    var firstAt = Long.MAX_VALUE
    var lastAt = 0L
    var unsubscribe: String? = null
    var oneClick = false
    private var unsubscribeAt = 0L
    private val subjects = ArrayList<Pair<Long, String>>()

    fun add(name: String, at: Long, seen: Boolean, subject: String, unsubscribe: String?, oneClick: Boolean, recentSince: Long) {
        total++
        if (!seen) unread++
        if (at >= recentSince) recent++
        if (at < firstAt) firstAt = at
        if (at >= lastAt) {
            lastAt = at
            if (name.isNotBlank()) this.name = name
        } else if (this.name.isBlank() && name.isNotBlank()) {
            this.name = name
        }
        // The newest list header is the one most likely to still work.
        if (unsubscribe != null && at >= unsubscribeAt) {
            this.unsubscribe = unsubscribe
            this.oneClick = oneClick
            unsubscribeAt = at
        }
        val clean = subject.replace(Regex("\\s+"), " ").trim().take(120)
        if (clean.isNotEmpty()) {
            subjects += at to clean
            if (subjects.size > SUBJECTS_KEPT * 3) trim()
        }
    }

    private fun trim() {
        val newest = subjects.sortedByDescending { it.first }.distinctBy { it.second.lowercase() }.take(SUBJECTS_KEPT)
        subjects.clear()
        subjects.addAll(newest)
    }

    fun recentSubjects(): List<String> {
        trim()
        return subjects.map { it.second }
    }

    companion object {
        const val SUBJECTS_KEPT = 5
    }
}

/** Talks to Gmail and Yahoo over IMAP with an app password. Everything here blocks, so call it off the main thread. */
class MailClient {

    open class MailError(message: String) : Exception(message)

    /**
     * The server turned the sign-in down. Yahoo does this now and then even with the right app
     * password (when it's signed in to often, say), so one of these alone doesn't mean the password is
     * wrong. [reason] is what the server said.
     */
    class LoginRefused(message: String, val reason: String) : MailError(message)

    /** Waited too long for a turn on this mailbox (another check had it). Tried again later. */
    class Busy(message: String) : MailError(message)

    private fun connect(server: MailServer, address: String, password: String, background: Boolean = false): IMAPStore {
        val key = address.lowercase(Locale.ROOT)
        // Just refused: a check in the background waits a few minutes instead of asking again straight away.
        if (background) {
            trouble[key]?.let { t ->
                if (System.currentTimeMillis() - t.lastAt < COOL_DOWN_MS) throw LoginRefused(refusedText(t.reason), t.reason)
            }
        }
        var lastReason = ""
        for (attempt in 0 until LOGIN_TRIES) {
            if (attempt > 0) Thread.sleep(RETRY_GAP_MS)
            try {
                val store = open(server, address, password)
                trouble.remove(key)
                return store
            } catch (e: AuthenticationFailedException) {
                lastReason = e.message.orEmpty().trim().ifEmpty { "sign-in refused" }
            }
        }
        val now = System.currentTimeMillis()
        trouble.compute(key) { _, t -> t?.copy(count = t.count + 1, lastAt = now, reason = lastReason) ?: Trouble(now, now, 1, lastReason) }
        throw LoginRefused(refusedText(lastReason), lastReason)
    }

    private fun refusedText(reason: String) =
        "The server didn't accept the sign-in (it said: ${reason.take(120)}). If this keeps happening, the app password may be wrong: add a new one in Email setup."

    private fun open(server: MailServer, address: String, password: String): IMAPStore {
        val protocol = if (server.ssl) "imaps" else "imap"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", server.host)
            put("mail.$protocol.port", server.port.toString())
            put("mail.$protocol.connectiontimeout", "15000")
            put("mail.$protocol.timeout", "60000")
            put("mail.$protocol.fetchsize", "1048576")
            // Reading an email must never mark it as read.
            put("mail.$protocol.peek", "true")
            if (server.ssl) put("mail.$protocol.ssl.checkserveridentity", "true")
        }
        val store = Session.getInstance(props).getStore(protocol) as IMAPStore
        try {
            store.connect(server.host, server.port, address, password)
        } catch (e: AuthenticationFailedException) {
            throw e
        } catch (e: MessagingException) {
            throw MailError(friendly(e))
        }
        return store
    }

    /**
     * A turn on [address]: at most [PER_ACCOUNT] connections to one mailbox at a time. The agent's
     * checks wait for one; something you asked for (opening an email) waits a little, then goes ahead.
     */
    private fun turn(address: String, background: Boolean): () -> Unit {
        val gate = gates.computeIfAbsent(address.lowercase(Locale.ROOT)) { Semaphore(PER_ACCOUNT, true) }
        val wait = if (background) WAIT_FOR_TURN_S else WAIT_FOR_YOU_S
        if (gate.tryAcquire(wait, TimeUnit.SECONDS)) return { gate.release() }
        if (background) throw Busy("The mailbox was busy with another check. It'll try again shortly.")
        return {}
    }

    /**
     * Sends an email over SMTP with the same app password. [build] makes the email in the sending
     * session. Returns the email as sent, to file it in Sent.
     */
    fun send(server: MailServer, address: String, password: String, build: (Session) -> MimeMessage): MimeMessage {
        val protocol = if (server.ssl) "smtps" else "smtp"
        val props = Properties().apply {
            put("mail.transport.protocol", protocol)
            put("mail.$protocol.host", server.host)
            put("mail.$protocol.port", server.port.toString())
            put("mail.$protocol.auth", "true")
            put("mail.$protocol.connectiontimeout", "15000")
            put("mail.$protocol.timeout", "60000")
            put("mail.$protocol.writetimeout", "60000")
            if (server.ssl) put("mail.$protocol.ssl.checkserveridentity", "true")
            if (!server.ssl && server.port == 587) put("mail.smtp.starttls.required", "true")
        }
        val session = Session.getInstance(props)
        val message = build(session)
        val transport = session.getTransport(protocol)
        try {
            transport.connect(server.host, server.port, address, password)
            transport.sendMessage(message, message.allRecipients)
        } catch (e: AuthenticationFailedException) {
            throw MailError("The app password wasn't accepted for sending. Add the account again with a new app password.")
        } catch (e: MessagingException) {
            throw MailError(friendly(e))
        } finally {
            runCatching { transport.close() }
        }
        return message
    }

    /**
     * A signed-in connection. Close it when done, which gives the turn back. [background] is for the
     * agent's own checks: right after a refused sign-in they wait rather than ask the server again.
     */
    fun session(server: MailServer, address: String, password: String, provider: String, background: Boolean = false): MailSession {
        val done = turn(address, background)
        val store = try {
            connect(server, address, password, background)
        } catch (e: Exception) {
            done()
            throw e
        }
        return MailSession(store, provider, done)
    }

    /** Null when the account works, otherwise what went wrong in plain words. */
    fun check(server: MailServer, address: String, password: String): String? {
        val done = turn(address, background = false)
        return try {
            val store = connect(server, address, password)
            try {
                store.getFolder("INBOX").messageCount
            } finally {
                runCatching { store.close() }
            }
            null
        } catch (e: LoginRefused) {
            "The email or app password wasn't accepted (the server said: ${e.reason.take(120)}). It needs an app password, not your normal password."
        } catch (e: MailError) {
            e.message
        } catch (e: MessagingException) {
            friendly(e)
        } finally {
            done()
        }
    }

    /**
     * Reads who sent each of the newest [limit] emails, without opening them or marking them read.
     * Gmail is read from All Mail, so each email counts once; Yahoo from the inbox and the other folders,
     * leaving out spam, sent, drafts and bin.
     */
    fun tallySenders(
        server: MailServer,
        address: String,
        password: String,
        limit: Int,
        now: Long,
        keepGoing: () -> Boolean,
        progress: (done: Int, planned: Int) -> Unit,
    ): Map<String, SenderTally> {
        val own = address.lowercase(Locale.ROOT)
        val recentSince = now - TimeUnit.DAYS.toMillis(RECENT_DAYS)
        val tallies = HashMap<String, SenderTally>()
        val done = turn(address, background = false)
        val store = try {
            connect(server, address, password)
        } catch (e: Exception) {
            done()
            throw e
        }
        try {
            val folders = foldersToRead(store)
            val counts = folders.map { f -> runCatching { f.messageCount }.getOrDefault(0).coerceAtLeast(0) }
            var budget = limit
            val planned = counts.sumOf { it }.coerceAtMost(limit)
            var done = 0
            progress(0, planned)
            for ((index, folder) in folders.withIndex()) {
                if (budget <= 0 || !keepGoing()) break
                if (counts[index] == 0) continue
                folder.open(Folder.READ_ONLY)
                try {
                    val n = folder.messageCount
                    val take = minOf(n, budget)
                    if (take <= 0) continue
                    val messages = folder.getMessages(n - take + 1, n)
                    val profile = FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.FLAGS)
                        add("List-Unsubscribe")
                        add("List-Unsubscribe-Post")
                    }
                    // Newest first, so a stopped read still has the most useful part.
                    for (chunk in messages.reversed().chunked(CHUNK)) {
                        if (!keepGoing()) break
                        val batch = chunk.toTypedArray<Message>()
                        folder.fetch(batch, profile)
                        for (m in batch) tally(m, own, recentSince, tallies)
                        done += batch.size
                        progress(done, planned)
                    }
                    budget -= take
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        } catch (e: MessagingException) {
            throw MailError(friendly(e))
        } finally {
            runCatching { store.close() }
            done()
        }
        return tallies
    }

    private fun tally(m: Message, own: String, recentSince: Long, into: HashMap<String, SenderTally>) {
        try {
            val from = m.from?.firstOrNull() as? InternetAddress ?: return
            val sender = from.address?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.contains('@') } ?: return
            if (sender == own) return
            val at = (m.receivedDate ?: m.sentDate)?.time ?: return
            val unsubscribe = m.getHeader("List-Unsubscribe")?.firstOrNull()?.let { decodeHeader(it) }
            val post = m.getHeader("List-Unsubscribe-Post")?.firstOrNull().orEmpty()
            into.getOrPut(sender) { SenderTally(sender) }.add(
                name = from.personal?.trim().orEmpty(),
                at = at,
                seen = m.isSet(Flags.Flag.SEEN),
                subject = m.subject.orEmpty(),
                unsubscribe = unsubscribe,
                oneClick = post.contains("One-Click", ignoreCase = true),
                recentSince = recentSince,
            )
        } catch (e: MessagingException) {
            // One odd email is skipped rather than stopping the whole read.
        }
    }

    /** Header values can be folded over several lines. */
    private fun decodeHeader(value: String): String = value.replace(Regex("\\r?\\n[ \\t]+"), " ").trim()

    private fun foldersToRead(store: IMAPStore): List<IMAPFolder> {
        val all = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
        fun attributes(f: IMAPFolder): Set<String> =
            runCatching { f.attributes.map { it.lowercase(Locale.ROOT) }.toSet() }.getOrDefault(emptySet())
        all.firstOrNull { "\\all" in attributes(it) }?.let { return listOf(it) }
        val skip = setOf("\\junk", "\\trash", "\\sent", "\\drafts", "\\noselect", "\\nonexistent", "\\flagged", "\\important")
        val inbox = store.getFolder("INBOX") as IMAPFolder
        val others = all.filter { f ->
            !f.fullName.equals("INBOX", ignoreCase = true) &&
                attributes(f).none { it in skip } &&
                runCatching { (f.type and Folder.HOLDS_MESSAGES) != 0 }.getOrDefault(false) &&
                !NOT_MAIL.any { f.fullName.equals(it, ignoreCase = true) }
        }
        return listOf(inbox) + others
    }

    private fun friendly(e: Exception): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.net.UnknownHostException || cause is java.net.ConnectException ||
                cause is java.net.SocketTimeoutException
            ) {
                return "Could not reach the mail server. Check your internet and try again."
            }
            if (cause is javax.net.ssl.SSLException) return "Could not open a safe connection to the mail server."
            if (cause is IOException && cause.message?.contains("timed out", ignoreCase = true) == true) {
                return "The mail server took too long to answer. Try again."
            }
            cause = cause.cause
        }
        val text = e.message.orEmpty()
        return when {
            text.contains("Web login required", ignoreCase = true) || text.contains("WEBALERT", ignoreCase = true) ->
                "Google wants you to confirm this sign-in. Open Gmail on the web, then try again."
            text.contains("Too many", ignoreCase = true) -> "The mail server says too many tries. Wait a few minutes."
            else -> "The mail server said no: ${text.take(120)}"
        }
    }

    /** Sign-ins refused for one account in a row: since when, the last time, how many, and what was said. */
    data class Trouble(val firstAt: Long, val lastAt: Long, val count: Int, val reason: String)

    companion object {
        /** Shared by every part of the app that reads mail, so they take turns on each mailbox. */
        private val gates = ConcurrentHashMap<String, Semaphore>()
        private val trouble = ConcurrentHashMap<String, Trouble>()

        /** Refused sign-ins for [address] in a row, or null when the last one worked. */
        fun troubleFor(address: String): Trouble? = trouble[address.lowercase(Locale.ROOT)]

        private const val PER_ACCOUNT = 2
        private const val WAIT_FOR_TURN_S = 90L
        private const val WAIT_FOR_YOU_S = 15L
        private const val LOGIN_TRIES = 2
        private const val RETRY_GAP_MS = 4_000L
        private const val COOL_DOWN_MS = 3 * 60_000L

        const val RECENT_DAYS = 90L
        private const val CHUNK = 400
        /** Yahoo and Gmail folders that hold no real mail for this purpose. */
        private val NOT_MAIL = listOf("Bulk", "Bulk Mail", "Spam", "Junk", "Sent", "Draft", "Drafts", "Trash", "Bin", "Outbox")
    }
}
