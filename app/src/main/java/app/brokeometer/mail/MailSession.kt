package app.brokeometer.mail

import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.util.Locale
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.HeaderTerm
import javax.mail.search.OrTerm

/** One new email, as much as the agent needs to judge it. */
data class MailItem(
    val uid: Long,
    val messageId: String?,
    val fromName: String,
    val fromAddress: String,
    val to: String,
    val subject: String,
    val receivedAt: Long,
    val seen: Boolean,
    /** The start of the email's text, plain words only. */
    val text: String,
)

/** A whole email, for reading in the app. */
data class FullEmail(
    val fromName: String,
    val fromAddress: String,
    /** Where a reply goes, when the sender asked for somewhere else. */
    val replyTo: String?,
    val to: String,
    val cc: String,
    val date: Long,
    val subject: String,
    /** The email's own HTML, with pictures carried inside the email already filled in. */
    val html: String?,
    val text: String?,
    val attachments: List<String>,
    val messageId: String?,
    val references: String?,
    /** Pictures or other files the email loads from the internet when shown. */
    val loadsFromWeb: Boolean,
)

/** Which folder a cursor belongs to. */
object MailFolders {
    const val INBOX = "inbox"
    const val SPAM = "spam"
}

/**
 * A signed-in connection to one account, for the agent's work on single emails.
 * Everything blocks; use it off the main thread and close it when done.
 */
class MailSession(private val store: IMAPStore, private val provider: String, private val onClose: () -> Unit = {}) : AutoCloseable {

    private val folders: List<IMAPFolder> by lazy {
        store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
    }

    private fun attributes(f: IMAPFolder): Set<String> =
        runCatching { f.attributes.map { it.lowercase(Locale.ROOT) }.toSet() }.getOrDefault(emptySet())

    private fun special(attribute: String, vararg names: String): IMAPFolder? =
        folders.firstOrNull { attribute in attributes(it) }
            ?: folders.firstOrNull { f -> names.any { f.fullName.equals(it, ignoreCase = true) || f.name.equals(it, ignoreCase = true) } }

    fun inbox(): IMAPFolder = store.getFolder("INBOX") as IMAPFolder

    fun spam(): IMAPFolder? = special("\\junk", "[Gmail]/Spam", "Spam", "Bulk", "Bulk Mail", "Junk")

    fun trash(): IMAPFolder? = special("\\trash", "[Gmail]/Bin", "[Gmail]/Trash", "Trash", "Bin", "Deleted Items")

    /** Gmail archives by taking the email out of the inbox into All Mail; Yahoo has an Archive folder. */
    fun archive(): IMAPFolder? = special("\\archive", "Archive") ?: special("\\all", "[Gmail]/All Mail")

    fun folder(kind: String): IMAPFolder? = if (kind == MailFolders.SPAM) spam() else inbox()

    /**
     * Emails that arrived after [lastUid], oldest first, at most [max]. The pair is the folder's
     * UIDVALIDITY and the UID to remember. With no cursor (or when the server renumbered the folder)
     * nothing is returned and the cursor is set to now, so old email is never touched, unless
     * [startAtOldest] asks to go through what is already there.
     */
    fun newSince(
        kind: String,
        uidValidity: Long?,
        lastUid: Long?,
        max: Int,
        startAtOldest: Boolean = false,
    ): Pair<List<MailItem>, Pair<Long, Long>>? {
        val folder = folder(kind) ?: return null
        folder.open(Folder.READ_ONLY)
        try {
            val validity = folder.uidValidity
            val next = folder.uidNext.takeIf { it > 0 }
            val reset = lastUid == null || uidValidity == null || uidValidity != validity
            if (reset && !startAtOldest) {
                val now = next?.minus(1) ?: folder.messageCount.takeIf { it > 0 }?.let { folder.getUID(folder.getMessage(it)) } ?: 0L
                return emptyList<MailItem>() to (validity to now)
            }
            val from = if (reset) 0L else lastUid!!
            if (next != null && next - 1 <= from) return emptyList<MailItem>() to (validity to from)
            val messages = folder.getMessagesByUID(from + 1, UIDFolder.LASTUID)
                .filterNotNull()
                .filter { folder.getUID(it) > from }
            if (messages.isEmpty()) return emptyList<MailItem>() to (validity to maxOf(from, (next ?: 1L) - 1))
            val batch = messages.take(max)
            val profile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
                add(FetchProfile.Item.CONTENT_INFO)
            }
            folder.fetch(batch.toTypedArray(), profile)
            val items = batch.mapNotNull { m -> item(folder, m) }
            return items to (validity to folder.getUID(batch.last()))
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun item(folder: IMAPFolder, m: Message): MailItem? = try {
        val from = m.from?.firstOrNull() as? InternetAddress
        MailItem(
            uid = folder.getUID(m),
            messageId = (m as? MimeMessage)?.messageID,
            fromName = from?.personal?.trim().orEmpty(),
            fromAddress = from?.address?.trim()?.lowercase(Locale.ROOT).orEmpty(),
            to = m.getRecipients(Message.RecipientType.TO)?.joinToString(", ") { a -> (a as? InternetAddress)?.address ?: a.toString() }.orEmpty(),
            subject = m.subject.orEmpty(),
            receivedAt = (m.receivedDate ?: m.sentDate)?.time ?: System.currentTimeMillis(),
            seen = m.isSet(Flags.Flag.SEEN),
            text = MailText.of(m, TEXT_LIMIT),
        )
    } catch (e: MessagingException) {
        null
    }

    fun markRead(kind: String, uid: Long, read: Boolean = true) {
        val folder = folder(kind) ?: return
        folder.open(Folder.READ_WRITE)
        try {
            folder.getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, read)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /** Marks the email read and takes it out of the inbox. False when there is nowhere to archive to. */
    fun archive(uid: Long): Boolean = move(MailFolders.INBOX, uid, archive(), markRead = true)

    /** Moves an email from spam to the bin, which Gmail and Yahoo empty by themselves after 30 days. */
    fun binSpam(uid: Long): Boolean = move(MailFolders.SPAM, uid, trash(), markRead = true)

    private fun move(kind: String, uid: Long, to: IMAPFolder?, markRead: Boolean): Boolean {
        val from = folder(kind) ?: return false
        if (to == null || to.fullName == from.fullName) return false
        from.open(Folder.READ_WRITE)
        try {
            val m = from.getMessageByUID(uid) ?: return true
            if (markRead) m.setFlag(Flags.Flag.SEEN, true)
            from.moveMessages(arrayOf(m), to)
            return true
        } finally {
            runCatching { from.close(true) }
        }
    }

    /** Puts an archived email back in the inbox, unread. Found by its Message-ID. */
    fun unarchive(messageId: String): Boolean {
        val source = archive() ?: return false
        source.open(Folder.READ_WRITE)
        try {
            val found = source.search(HeaderTerm("Message-ID", messageId))
            if (found.isEmpty()) return false
            found.forEach { it.setFlag(Flags.Flag.SEEN, false) }
            if (provider == MailProviders.GMAIL) {
                // In Gmail, copying back to the inbox adds the Inbox label to the same email.
                source.copyMessages(found, inbox())
            } else {
                source.moveMessages(found, inbox())
            }
            return true
        } finally {
            runCatching { source.close(true) }
        }
    }

    /** UIDs of every unread email in the inbox, oldest first. */
    fun unreadInbox(): List<Long> {
        val inbox = inbox()
        inbox.open(Folder.READ_ONLY)
        try {
            val found = inbox.search(javax.mail.search.FlagTerm(Flags(Flags.Flag.SEEN), false))
            if (found.isEmpty()) return emptyList()
            inbox.fetch(found, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
            return found.map { inbox.getUID(it) }.filter { it > 0 }.sorted()
        } finally {
            runCatching { inbox.close(false) }
        }
    }

    /** Who sent these emails and what about, without opening them. */
    fun headers(uids: List<Long>): List<MailItem> {
        if (uids.isEmpty()) return emptyList()
        val inbox = inbox()
        inbox.open(Folder.READ_ONLY)
        try {
            val messages = inbox.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            inbox.fetch(messages, FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
            })
            return messages.mapNotNull { m ->
                try {
                    val from = m.from?.firstOrNull() as? InternetAddress
                    MailItem(
                        uid = inbox.getUID(m),
                        messageId = (m as? MimeMessage)?.messageID,
                        fromName = from?.personal?.trim().orEmpty(),
                        fromAddress = from?.address?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                        to = "",
                        subject = m.subject.orEmpty(),
                        receivedAt = (m.receivedDate ?: m.sentDate)?.time ?: 0L,
                        seen = m.isSet(Flags.Flag.SEEN),
                        text = "",
                    )
                } catch (e: MessagingException) {
                    null
                }
            }
        } finally {
            runCatching { inbox.close(false) }
        }
    }

    /**
     * Marks inbox emails read, found by UID, or by Message-ID for ones that came back from spam, the
     * archive or the bin (their UID changed on the way). They stay in the inbox. Returns how many were found.
     */
    fun markReadInbox(uids: List<Long>, messageIds: List<String>): Int = setSeenInbox(uids, messageIds, true)

    /** Marks inbox emails read or unread, found by UID or by Message-ID. Returns how many were found. */
    fun setSeenInbox(uids: List<Long>, messageIds: List<String>, seen: Boolean): Int {
        if (uids.isEmpty() && messageIds.isEmpty()) return 0
        val inbox = inbox()
        inbox.open(Folder.READ_WRITE)
        try {
            val found = LinkedHashSet<Message>()
            uids.chunked(BATCH).forEach { chunk -> found += inbox.getMessagesByUID(chunk.toLongArray()).filterNotNull() }
            messageIds.chunked(ID_BATCH).forEach { chunk ->
                val terms = chunk.map { HeaderTerm("Message-ID", it) }
                val term = if (terms.size == 1) terms[0] else OrTerm(terms.toTypedArray())
                found += inbox.search(term)
            }
            if (found.isNotEmpty()) inbox.setFlags(found.toTypedArray(), Flags(Flags.Flag.SEEN), seen)
            return found.size
        } finally {
            runCatching { inbox.close(false) }
        }
    }

    /** Moves an email to the bin from the inbox or the archive, found by its Message-ID. */
    fun binById(messageId: String): Boolean {
        val bin = trash() ?: return false
        for (from in listOfNotNull(inbox(), archive()).distinctBy { it.fullName }) {
            if (from.fullName == bin.fullName) continue
            from.open(Folder.READ_WRITE)
            try {
                val found = from.search(HeaderTerm("Message-ID", messageId))
                if (found.isNotEmpty()) {
                    from.moveMessages(found, bin)
                    return true
                }
            } finally {
                runCatching { from.close(true) }
            }
        }
        return false
    }

    /** Archives an inbox email found by its Message-ID, marking it read. False when it isn't in the inbox. */
    fun archiveById(messageId: String): Boolean {
        val from = inbox()
        val to = archive() ?: return false
        if (to.fullName == from.fullName) return false
        from.open(Folder.READ_WRITE)
        try {
            val found = from.search(HeaderTerm("Message-ID", messageId))
            if (found.isEmpty()) return false
            from.setFlags(found, Flags(Flags.Flag.SEEN), true)
            from.moveMessages(found, to)
            return true
        } finally {
            runCatching { from.close(true) }
        }
    }

    fun sent(): IMAPFolder? = special("\\sent", "[Gmail]/Sent Mail", "Sent", "Sent Messages", "Sent Items")

    /**
     * Finds an email by its Message-ID (or, with none, by UID in [kind]) wherever it is now: the inbox,
     * the archive, spam or the bin. Reading it does not mark it read.
     */
    fun full(messageId: String?, uid: Long, kind: String): FullEmail? {
        val places = if (kind == MailFolders.SPAM) listOfNotNull(spam(), inbox(), trash(), archive()) else listOfNotNull(inbox(), archive(), spam(), trash())
        val home = folder(kind)?.fullName
        for (f in places.distinctBy { it.fullName }) {
            f.open(Folder.READ_ONLY)
            try {
                val m: Message = when {
                    messageId != null -> f.search(HeaderTerm("Message-ID", messageId)).firstOrNull()
                    f.fullName == home -> f.getMessageByUID(uid)
                    else -> null
                } ?: continue
                return read(m as MimeMessage)
            } finally {
                runCatching { f.close(false) }
            }
        }
        return null
    }

    /** One attached file of an email (the [index]th, as listed in [FullEmail.attachments]): its name, type and bytes. */
    fun attachment(messageId: String?, uid: Long, kind: String, index: Int): Triple<String, String, ByteArray>? {
        val home = folder(kind)?.fullName
        val places = if (kind == MailFolders.SPAM) listOfNotNull(spam(), inbox(), trash(), archive()) else listOfNotNull(inbox(), archive(), spam(), trash())
        for (f in places.distinctBy { it.fullName }) {
            f.open(Folder.READ_ONLY)
            try {
                val m: Message = when {
                    messageId != null -> f.search(HeaderTerm("Message-ID", messageId)).firstOrNull()
                    f.fullName == home -> f.getMessageByUID(uid)
                    else -> null
                } ?: continue
                val parts = MailParts()
                parts.collect(m)
                val part = parts.attachmentParts.getOrNull(index) ?: return null
                val name = parts.attachments[index]
                val type = runCatching { part.contentType.substringBefore(';').trim().lowercase(Locale.ROOT) }.getOrDefault("application/octet-stream")
                val bytes = part.inputStream.use { it.readBytes() }
                return Triple(name, type, bytes)
            } finally {
                runCatching { f.close(false) }
            }
        }
        return null
    }

    private fun read(m: MimeMessage): FullEmail {
        val parts = MailParts()
        parts.collect(m)
        val from = m.from?.firstOrNull() as? InternetAddress
        fun list(type: Message.RecipientType) =
            runCatching { m.getRecipients(type)?.joinToString(", ") { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() } }.getOrNull().orEmpty()
        var html = parts.html
        if (html != null) for ((cid, data) in parts.inline) html = html!!.replace("cid:$cid", data, ignoreCase = true)
        val web = html != null && Regex("""(?i)(src|background)\s*=\s*["']?https?:|url\(\s*["']?https?:""").containsMatchIn(html!!)
        return FullEmail(
            fromName = from?.personal?.trim().orEmpty(),
            fromAddress = from?.address?.trim()?.lowercase(Locale.ROOT).orEmpty(),
            replyTo = runCatching { (m.replyTo?.firstOrNull() as? InternetAddress)?.address }.getOrNull()?.takeIf { !it.equals(from?.address, ignoreCase = true) },
            to = list(Message.RecipientType.TO),
            cc = list(Message.RecipientType.CC),
            date = (m.sentDate ?: m.receivedDate)?.time ?: 0L,
            subject = m.subject.orEmpty(),
            html = html,
            text = parts.text,
            attachments = parts.attachments,
            messageId = m.messageID,
            references = m.getHeader("References", " "),
            loadsFromWeb = web,
        )
    }

    /** Files a sent email in Sent, unless the server already did (Gmail does it by itself). */
    fun fileSent(message: MimeMessage) {
        val folder = sent() ?: return
        val id = message.messageID
        folder.open(Folder.READ_WRITE)
        try {
            if (id != null && folder.search(HeaderTerm("Message-ID", id)).isNotEmpty()) return
            message.setFlag(Flags.Flag.SEEN, true)
            folder.appendMessages(arrayOf(message))
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /** UIDs of unread inbox emails from one address. */
    fun unreadFrom(address: String): List<Long> {
        val inbox = inbox()
        inbox.open(Folder.READ_ONLY)
        try {
            val found = inbox.search(AndTerm(FlagTerm(Flags(Flags.Flag.SEEN), false), FromStringTerm(address)))
            if (found.isEmpty()) return emptyList()
            inbox.fetch(found, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
            return found.map { inbox.getUID(it) }.filter { it > 0 }.sorted()
        } finally {
            runCatching { inbox.close(false) }
        }
    }

    /** Marks these read and takes them out of the inbox in one go. */
    fun archiveMany(uids: List<Long>): Boolean {
        if (uids.isEmpty()) return true
        val from = inbox()
        val to = archive()
        from.open(Folder.READ_WRITE)
        try {
            val messages = from.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            if (messages.isEmpty()) return true
            from.setFlags(messages, Flags(Flags.Flag.SEEN), true)
            if (to == null || to.fullName == from.fullName) return true
            from.moveMessages(messages, to)
            return true
        } finally {
            runCatching { from.close(true) }
        }
    }

    /** Moves an email from spam to the inbox. Found by Message-ID, or by UID when it has none. */
    fun spamToInbox(messageId: String?, uid: Long?): Boolean = moveFromSpam(messageId, uid, inbox())

    /** Moves an email from spam to the bin. */
    fun spamToBin(messageId: String?, uid: Long?): Boolean = moveFromSpam(messageId, uid, trash())

    private fun moveFromSpam(messageId: String?, uid: Long?, to: IMAPFolder?): Boolean {
        val spam = spam() ?: return false
        if (to == null) return false
        spam.open(Folder.READ_WRITE)
        try {
            val found: Array<Message> = when {
                messageId != null -> spam.search(HeaderTerm("Message-ID", messageId))
                uid != null -> listOfNotNull(spam.getMessageByUID(uid)).toTypedArray()
                else -> emptyArray()
            }
            if (found.isEmpty()) return false
            spam.moveMessages(found, to)
            return true
        } finally {
            runCatching { spam.close(true) }
        }
    }

    /** Takes an email back out of the bin into the inbox, unread. */
    fun unbin(messageId: String): Boolean {
        val bin = trash() ?: return false
        bin.open(Folder.READ_WRITE)
        try {
            val found = bin.search(HeaderTerm("Message-ID", messageId))
            if (found.isEmpty()) return false
            found.forEach { it.setFlag(Flags.Flag.SEEN, false) }
            bin.moveMessages(found, inbox())
            return true
        } finally {
            runCatching { bin.close(true) }
        }
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { store.close() }
        onClose()
    }

    companion object {
        const val TEXT_LIMIT = 2500
        private const val BATCH = 200
        private const val ID_BATCH = 25
    }
}

/** Plain words from an email: the text part if there is one, otherwise the HTML with the tags taken out. */
/** The pieces of an email: its HTML, its plain text, pictures inside it, and the names of attached files. */
class MailParts {
    var html: String? = null
    var text: String? = null
    val attachments = ArrayList<String>()
    /** The attached files themselves, in the same order as [attachments]. */
    val attachmentParts = ArrayList<Part>()
    /** Content-ID to a data: address, so pictures sent inside the email show without going online. */
    val inline = LinkedHashMap<String, String>()
    private var inlineBytes = 0

    fun collect(part: Part) {
        try {
            val disposition = part.disposition
            val name = part.fileName
            when {
                part.isMimeType("multipart/*") -> {
                    val multi = part.content as? Multipart ?: return
                    for (i in 0 until multi.count) collect(multi.getBodyPart(i))
                }
                part.isMimeType("image/*") && part is javax.mail.internet.MimeBodyPart && part.contentID != null -> {
                    val bytes = part.inputStream.use { it.readBytes() }
                    if (inlineBytes + bytes.size <= INLINE_LIMIT) {
                        inlineBytes += bytes.size
                        val type = part.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
                        val cid = part.contentID.trim().removePrefix("<").removeSuffix(">")
                        inline[cid] = "data:$type;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                    if (Part.ATTACHMENT.equals(disposition, ignoreCase = true) && name != null) {
                        attachments += name
                        attachmentParts += part
                    }
                }
                Part.ATTACHMENT.equals(disposition, ignoreCase = true) || (name != null && !part.isMimeType("text/*")) -> {
                    attachments += (name ?: "Attachment")
                    attachmentParts += part
                }
                part.isMimeType("text/html") -> if (html == null) html = part.content as? String
                part.isMimeType("text/plain") -> if (text == null) text = part.content as? String
                part.isMimeType("message/rfc822") -> {
                    attachments += (name ?: "Forwarded email.eml")
                    attachmentParts += part
                }
            }
        } catch (e: Exception) {
            // A broken part is left out; the rest of the email still shows.
        }
    }

    companion object {
        private const val INLINE_LIMIT = 4_000_000
    }
}

object MailText {

    fun of(part: Part, limit: Int): String {
        val text = runCatching { find(part, "text/plain") }.getOrNull()
            ?: runCatching { find(part, "text/html") }.getOrNull()?.let { fromHtml(it) }
            ?: ""
        return tidy(text).take(limit)
    }

    private fun find(part: Part, type: String): String? {
        if (part.isMimeType(type) && !Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) {
            return part.content as? String
        }
        if (part.isMimeType("multipart/*")) {
            val multi = part.content as? Multipart ?: return null
            for (i in 0 until multi.count) find(multi.getBodyPart(i), type)?.let { return it }
        }
        return null
    }

    fun fromHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</h[1-6]>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("&#?\\w+;"), " ")

    fun tidy(text: String): String = text
        .replace("\r", "")
        .lines()
        .map { it.replace(Regex("[ \\t\\u00A0\\u200C\\u200B]+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}
