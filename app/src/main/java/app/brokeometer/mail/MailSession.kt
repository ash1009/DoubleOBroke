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
import javax.mail.search.SubjectTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

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

/** An email found by a search of the whole mailbox. [uid] is its inbox UID when it's in the inbox, else 0. */
data class FoundItem(
    val uid: Long,
    val messageId: String,
    val fromName: String,
    val fromAddress: String,
    val subject: String,
    val date: Long,
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

/** A starred email (or, in Gmail, one marked important), for the Important page. */
data class MarkedItem(
    val messageId: String,
    val fromName: String,
    val fromAddress: String,
    val subject: String,
    val receivedAt: Long,
    val seen: Boolean,
    val starred: Boolean,
    val important: Boolean,
)

/** Where an email is now, so the app's list can match the email app. */
enum class MailPlace { INBOX_UNREAD, INBOX_READ, ARCHIVED, GONE }

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

    /**
     * One email as the agent sees it. Each part is read on its own, so an email with a broken part (an
     * odd header, a body that won't decode) still comes through with what could be read: no email is
     * ever skipped because of how it was written.
     */
    private fun item(folder: IMAPFolder, m: Message): MailItem? {
        val uid = runCatching { folder.getUID(m) }.getOrNull()?.takeIf { it > 0 } ?: return null
        val from = runCatching { m.from?.firstOrNull() as? InternetAddress }.getOrNull()
        return MailItem(
            uid = uid,
            messageId = runCatching { (m as? MimeMessage)?.messageID }.getOrNull(),
            fromName = runCatching { from?.personal?.trim() }.getOrNull().orEmpty(),
            fromAddress = from?.address?.trim()?.lowercase(Locale.ROOT).orEmpty(),
            to = runCatching {
                m.getRecipients(Message.RecipientType.TO)?.joinToString(", ") { a -> (a as? InternetAddress)?.address ?: a.toString() }
            }.getOrNull().orEmpty(),
            subject = runCatching { m.subject }.getOrNull().orEmpty(),
            receivedAt = runCatching { (m.receivedDate ?: m.sentDate)?.time }.getOrNull() ?: System.currentTimeMillis(),
            seen = runCatching { m.isSet(Flags.Flag.SEEN) }.getOrDefault(false),
            text = runCatching { MailText.of(m, TEXT_LIMIT) }.getOrDefault(""),
        )
    }

    /** These emails, by UID, as the agent sees them; ones no longer in the folder are left out. */
    fun itemsByUid(kind: String, uids: List<Long>): List<MailItem> {
        if (uids.isEmpty()) return emptyList()
        val folder = folder(kind) ?: return emptyList()
        folder.open(Folder.READ_ONLY)
        try {
            val found = folder.getMessagesByUID(uids.distinct().sorted().toLongArray()).filterNotNull().toTypedArray()
            if (found.isEmpty()) return emptyList()
            val profile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
                add(FetchProfile.Item.CONTENT_INFO)
            }
            folder.fetch(found, profile)
            return found.mapNotNull { item(folder, it) }
        } finally {
            runCatching { folder.close(false) }
        }
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

    /**
     * Where each of these emails is now, by Message-ID, without changing anything. [inboxUids] are
     * the inbox UIDs the app noted, with the Message-ID each should still have (one that doesn't match
     * is ignored). [search] are looked for by Message-ID when not found that way (newest first, at most
     * [MAX_SEARCHES]): in the inbox, then in the archive; one in neither was deleted.
     */
    fun places(inboxUids: Map<Long, String>, search: List<String>): Map<String, MailPlace> {
        val out = HashMap<String, MailPlace>()
        val notInInbox = ArrayList<String>()
        val inbox = inbox()
        inbox.open(Folder.READ_ONLY)
        try {
            if (inboxUids.isNotEmpty()) {
                val found = inbox.getMessagesByUID(inboxUids.keys.sorted().toLongArray()).filterNotNull().toTypedArray()
                if (found.isNotEmpty()) {
                    val profile = FetchProfile().apply {
                        add(FetchProfile.Item.FLAGS)
                        add(UIDFolder.FetchProfileItem.UID)
                        add("Message-ID")
                    }
                    inbox.fetch(found, profile)
                    for (m in found) {
                        val id = (m as? MimeMessage)?.messageID ?: continue
                        if (inboxUids[inbox.getUID(m)] != id) continue
                        out[id] = if (m.isSet(Flags.Flag.SEEN)) MailPlace.INBOX_READ else MailPlace.INBOX_UNREAD
                    }
                }
            }
            for (id in search.filter { it !in out }.distinct().take(MAX_SEARCHES)) {
                val m = inbox.search(HeaderTerm("Message-ID", id)).firstOrNull()
                if (m == null) notInInbox += id else out[id] = if (m.isSet(Flags.Flag.SEEN)) MailPlace.INBOX_READ else MailPlace.INBOX_UNREAD
            }
        } finally {
            runCatching { inbox.close(false) }
        }
        if (notInInbox.isEmpty()) return out
        val archive = archive() ?: return out
        archive.open(Folder.READ_ONLY)
        try {
            for (id in notInInbox) {
                out[id] = if (archive.search(HeaderTerm("Message-ID", id)).isNotEmpty()) MailPlace.ARCHIVED else MailPlace.GONE
            }
        } finally {
            runCatching { archive.close(false) }
        }
        return out
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
    fun full(messageId: String?, uid: Long, kind: String): FullEmail? = find(messageId, uid, kind) { read(it) }

    /** One attached file of an email (the [index]th, as listed in [FullEmail.attachments]): its name, type and bytes. */
    fun attachment(messageId: String?, uid: Long, kind: String, index: Int): Triple<String, String, ByteArray>? =
        find(messageId, uid, kind) { m ->
            val parts = MailParts()
            parts.collect(m)
            val part = parts.attachmentParts.getOrNull(index) ?: return@find null
            val name = parts.attachments[index]
            val type = runCatching { part.contentType.substringBefore(';').trim().lowercase(Locale.ROOT) }.getOrDefault("application/octet-stream")
            val bytes = part.inputStream.use { it.readBytes() }
            Triple(name, type, bytes)
        }

    /**
     * Finds an email the quick way first: by its UID where it came in, if it's still there; then, in Gmail,
     * by its Message-ID through Gmail's own search, which answers at once. Searching every folder's headers
     * (slow on a big mailbox) is the last resort, for other mailboxes or if Gmail's search doesn't answer.
     */
    private fun <T> find(messageId: String?, uid: Long, kind: String, use: (MimeMessage) -> T?): T? {
        val home = folder(kind)
        if (home != null && uid > 0) {
            home.open(Folder.READ_ONLY)
            try {
                val m = home.getMessageByUID(uid) as? MimeMessage
                if (m != null && (messageId == null || m.messageID == messageId)) return use(m)
            } finally {
                runCatching { home.close(false) }
            }
        }
        if (messageId != null && provider == MailProviders.GMAIL) {
            val id = messageId.trim().removePrefix("<").removeSuffix(">")
            var answered = true
            for (f in listOfNotNull(allMail(), spam(), trash()).distinctBy { it.fullName }) {
                f.open(Folder.READ_ONLY)
                try {
                    val uids = try {
                        gmailSearch(f, "rfc822msgid:$id")
                    } catch (e: MessagingException) {
                        answered = false
                        continue
                    }
                    for (u in uids) {
                        val m = f.getMessageByUID(u) as? MimeMessage ?: continue
                        return use(m)
                    }
                } finally {
                    runCatching { f.close(false) }
                }
            }
            // Gmail's search answered everywhere and it isn't there: it's gone.
            if (answered) return null
        }
        val places = if (kind == MailFolders.SPAM) listOfNotNull(spam(), inbox(), trash(), archive()) else listOfNotNull(inbox(), archive(), spam(), trash())
        for (f in places.distinctBy { it.fullName }) {
            f.open(Folder.READ_ONLY)
            try {
                val m: Message = when {
                    messageId != null -> f.search(HeaderTerm("Message-ID", messageId)).firstOrNull()
                    f.fullName == home?.fullName -> f.getMessageByUID(uid)
                    else -> null
                } ?: continue
                return use(m as MimeMessage)
            } finally {
                runCatching { f.close(false) }
            }
        }
        return null
    }

    /**
     * The newest emails, at most [max], matching [query] anywhere in the mailbox, leaving out spam and the
     * bin: in Gmail, Gmail's own search of All Mail (the same as searching in Gmail, words inside emails
     * too); elsewhere, the sender or the subject, in the inbox and the archive. Nothing is marked read.
     * Emails with no Message-ID are left out, as they can't be opened again.
     */
    fun search(query: String, max: Int): List<FoundItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val places = if (provider == MailProviders.GMAIL) listOfNotNull(allMail() ?: inbox())
        else listOfNotNull(inbox(), archive()).distinctBy { it.fullName }
        val inboxName = inbox().fullName
        val out = ArrayList<FoundItem>()
        for (f in places) {
            f.open(Folder.READ_ONLY)
            try {
                val messages: Array<Message> = if (provider == MailProviders.GMAIL) {
                    val uids = gmailSearch(f, q).sortedDescending().take(max)
                    if (uids.isEmpty()) emptyArray() else f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
                } else {
                    f.search(OrTerm(SubjectTerm(q), FromStringTerm(q))).takeLast(max).toTypedArray()
                }
                if (messages.isEmpty()) continue
                f.fetch(messages, FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(UIDFolder.FetchProfileItem.UID)
                })
                for (m in messages) {
                    val mm = m as? MimeMessage ?: continue
                    val id = runCatching { mm.messageID }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                    val from = runCatching { mm.from?.firstOrNull() as? InternetAddress }.getOrNull()
                    out += FoundItem(
                        uid = if (f.fullName == inboxName) runCatching { f.getUID(m) }.getOrDefault(0L) else 0L,
                        messageId = id,
                        fromName = from?.personal?.trim().orEmpty(),
                        fromAddress = from?.address?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                        subject = runCatching { mm.subject }.getOrNull().orEmpty(),
                        date = runCatching { (mm.receivedDate ?: mm.sentDate)?.time }.getOrNull() ?: 0L,
                    )
                }
            } finally {
                runCatching { f.close(false) }
            }
        }
        return out.distinctBy { it.messageId }.sortedByDescending { it.date }.take(max)
    }

    /**
     * Emails from the last [days] days, newest first, at most [max], as the agent sees them: in Gmail all
     * mail except promotions and social; elsewhere the inbox and the archive. Nothing is marked read.
     */
    fun recent(days: Long, max: Int): List<MailItem> {
        val places = if (provider == MailProviders.GMAIL) listOfNotNull(allMail() ?: inbox())
        else listOfNotNull(inbox(), archive()).distinctBy { it.fullName }
        val since = java.util.Date(System.currentTimeMillis() - days * 24 * 60 * 60_000L)
        val out = ArrayList<MailItem>()
        for (f in places) {
            f.open(Folder.READ_ONLY)
            try {
                val messages: Array<Message> = if (provider == MailProviders.GMAIL) {
                    val uids = gmailSearch(f, "newer_than:${days}d -category:promotions -category:social").sortedDescending().take(max)
                    if (uids.isEmpty()) emptyArray() else f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
                } else {
                    f.search(ReceivedDateTerm(ComparisonTerm.GE, since)).takeLast(max).toTypedArray()
                }
                if (messages.isEmpty()) continue
                f.fetch(messages, FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                    add(FetchProfile.Item.CONTENT_INFO)
                })
                out += messages.mapNotNull { item(f, it) }
            } finally {
                runCatching { f.close(false) }
            }
        }
        return out.distinctBy { it.messageId ?: "uid:${it.uid}" }.sortedByDescending { it.receivedAt }.take(max)
    }

    /** Whether this session can still be used (the server hasn't closed it). */
    val isOpen: Boolean get() = !closed.get() && runCatching { store.isConnected }.getOrDefault(false)

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

    private fun moveFromSpam(messageId: String?, uid: Long?, to: IMAPFolder?): Boolean =
        moveOutOfSpam(messageId, uid, to) == SpamMove.MOVED

    /** What happened when moving an email out of spam. */
    enum class SpamMove { MOVED, NOT_IN_SPAM, NO_FOLDER }

    /** Moves an email out of spam to the bin ([toBin]) or the inbox. Throws when the mailbox can't be reached. */
    fun moveOutOfSpam(messageId: String?, uid: Long?, toBin: Boolean): SpamMove = moveOutOfSpam(messageId, uid, if (toBin) trash() else inbox())

    private fun moveOutOfSpam(messageId: String?, uid: Long?, to: IMAPFolder?): SpamMove {
        val spam = spam() ?: return SpamMove.NO_FOLDER
        if (to == null) return SpamMove.NO_FOLDER
        spam.open(Folder.READ_WRITE)
        try {
            val found: Array<Message> = when {
                messageId != null -> spam.search(HeaderTerm("Message-ID", messageId))
                uid != null -> listOfNotNull(spam.getMessageByUID(uid)).toTypedArray()
                else -> emptyArray()
            }
            if (found.isEmpty()) return SpamMove.NOT_IN_SPAM
            spam.moveMessages(found, to)
            return SpamMove.MOVED
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

    /** Gmail's All Mail: every email but spam and the bin, whatever its labels. */
    private fun allMail(): IMAPFolder? = special("\\all", "[Gmail]/All Mail")

    /** Where starred emails are looked for: All Mail in Gmail; the inbox and the archive elsewhere. */
    private fun markedPlaces(): List<IMAPFolder> =
        if (provider == MailProviders.GMAIL) listOfNotNull(allMail() ?: inbox())
        else listOfNotNull(inbox(), archive()).distinctBy { it.fullName }

    /**
     * The newest starred emails, and in Gmail the newest marked important too, at most [max] of each,
     * newest first. Emails with no Message-ID are left out, as they can't be found again.
     */
    fun marked(max: Int): List<MarkedItem> {
        val out = LinkedHashMap<String, MarkedItem>()
        for (folder in markedPlaces()) {
            folder.open(Folder.READ_ONLY)
            try {
                val starred = folder.search(FlagTerm(Flags(Flags.Flag.FLAGGED), true)).sortedBy { it.messageNumber }.takeLast(max)
                val importantUids = if (provider == MailProviders.GMAIL) gmailSearch(folder, "is:important").sorted().takeLast(max) else emptyList()
                val important = if (importantUids.isEmpty()) emptyList() else folder.getMessagesByUID(importantUids.toLongArray()).filterNotNull()
                val all = (starred + important).distinctBy { it.messageNumber }.toTypedArray()
                if (all.isEmpty()) continue
                folder.fetch(all, FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                })
                val importantSet = importantUids.toSet()
                for (m in all) {
                    val id = runCatching { (m as? MimeMessage)?.messageID }.getOrNull() ?: continue
                    val from = runCatching { m.from?.firstOrNull() as? InternetAddress }.getOrNull()
                    val item = MarkedItem(
                        messageId = id,
                        fromName = runCatching { from?.personal?.trim() }.getOrNull().orEmpty(),
                        fromAddress = from?.address?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                        subject = runCatching { m.subject }.getOrNull().orEmpty(),
                        receivedAt = runCatching { (m.receivedDate ?: m.sentDate)?.time }.getOrNull() ?: 0L,
                        seen = runCatching { m.isSet(Flags.Flag.SEEN) }.getOrDefault(false),
                        starred = runCatching { m.isSet(Flags.Flag.FLAGGED) }.getOrDefault(false),
                        important = runCatching { folder.getUID(m) in importantSet }.getOrDefault(false),
                    )
                    val had = out[id]
                    out[id] = if (had == null) item else had.copy(starred = had.starred || item.starred, important = had.important || item.important)
                }
            } finally {
                runCatching { folder.close(false) }
            }
        }
        return out.values.sortedByDescending { it.receivedAt }
    }

    /**
     * Stars or unstars an email, found by its Message-ID. In Gmail it is also marked important, or
     * not, so it stays on (or leaves) the Important page the same way there. True when it was found.
     */
    fun setStar(messageId: String, on: Boolean): Boolean {
        var found = false
        for (folder in markedPlaces()) {
            folder.open(Folder.READ_WRITE)
            try {
                val messages = folder.search(HeaderTerm("Message-ID", messageId))
                if (messages.isEmpty()) continue
                found = true
                folder.setFlags(messages, Flags(Flags.Flag.FLAGGED), on)
                if (provider == MailProviders.GMAIL) {
                    folder.fetch(messages, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
                    val uids = messages.map { folder.getUID(it) }.filter { it > 0 }
                    if (uids.isNotEmpty()) gmailLabel(folder, uids, "\\Important", on)
                }
            } finally {
                runCatching { folder.close(false) }
            }
        }
        return found
    }

    /** UIDs in an open Gmail folder matching a Gmail search ("is:important"), using Gmail's own IMAP search. */
    private fun gmailSearch(folder: IMAPFolder, query: String): List<Long> {
        val result = folder.doCommand(IMAPFolder.ProtocolCommand { p ->
            val args = com.sun.mail.iap.Argument()
            args.writeAtom("X-GM-RAW")
            args.writeString(query)
            val r = p.command("UID SEARCH", args)
            val uids = ArrayList<Long>()
            for (resp in r) {
                if (resp is com.sun.mail.imap.protocol.IMAPResponse && resp.keyEquals("SEARCH")) {
                    while (true) {
                        val n = resp.readLong()
                        if (n < 0) break
                        uids += n
                    }
                }
            }
            p.notifyResponseHandlers(r)
            p.handleResult(r[r.size - 1])
            uids
        })
        return (result as? List<*>)?.filterIsInstance<Long>().orEmpty()
    }

    /** Adds a Gmail label (like "\\Important") to emails in an open folder, or takes it off. */
    private fun gmailLabel(folder: IMAPFolder, uids: List<Long>, label: String, on: Boolean) {
        folder.doCommand(IMAPFolder.ProtocolCommand { p ->
            val r = p.command("UID STORE ${uids.joinToString(",")} ${if (on) "+" else "-"}X-GM-LABELS ($label)", null)
            p.notifyResponseHandlers(r)
            p.handleResult(r[r.size - 1])
            null
        })
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { store.close() }
        onClose()
    }

    companion object {
        const val TEXT_LIMIT = 2500

        /** Emails looked for one by one (by Message-ID) in one go. */
        const val MAX_SEARCHES = 25
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
