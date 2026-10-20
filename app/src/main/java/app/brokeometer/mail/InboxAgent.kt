package app.brokeometer.mail

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.brokeometer.BrokeApp
import app.brokeometer.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Emails the agent dealt with, so the email app's own notification for them can be cleared. */
data class Handled(val provider: String, val items: List<MailItem>)

/**
 * The email agent. Every email that comes into an account's inbox or spam goes into the list. For the
 * inbox it asks the AI what the email is, then either notifies (switch on, or about you) or marks it
 * read and archives it. In spam, real spam goes to the bin and anything that might matter is flagged
 * with a notification. One email the AI can't judge never holds up the rest: it's listed as waiting
 * and tried again. Every step is logged so it can be undone from the Email page.
 */
class InboxAgent(
    private val context: Context,
    private val dao: MailDao,
    private val prefs: Prefs,
    private val mail: MailAgent,
    private val notifier: MailNotifier,
    private val scope: CoroutineScope,
    private val repository: app.brokeometer.data.Repository,
    /** An email you were just told about (its list entry), to fetch ahead so it opens at once. */
    private val onTold: (Long) -> Unit = {},
    private val client: MailClient = MailClient(),
) {
    private val lock = Mutex()

    /** Turns the agent on or off. Turning it on starts from now: older email is never touched. */
    suspend fun setOn(on: Boolean) {
        prefs.setMailAgentOn(on)
        if (on) {
            for (account in dao.accounts()) dao.deleteCursorsOf(account.id)
            schedule(context)
            // Sets the inbox starting point straight away.
            scope.launch { runCatching { checkAll(inbox = true, spam = false) } }
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_INBOX)
        }
    }

    /** An email app's notification the agent took over, and when. */
    private data class TakenAlert(val alert: AppAlert, val at: Long)

    /** Taken-over notifications whose email hasn't been found in the mailbox yet. */
    private val waitingAlerts = ArrayList<TakenAlert>()

    /** Ones told about as "couldn't check yet", until their email is found. */
    private val uncheckedShown = ArrayList<TakenAlert>()

    /** Each new email-app notification asks for a turn; turns asked for during one become one more. */
    private val alertTurns = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (turn in alertTurns) {
                try {
                    takeOverTurn()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The next notification or check picks it up.
                }
            }
        }
    }

    /**
     * Called when an email app posts a notification for a new email. For an account the app has, the
     * agent takes over straight away: the email app's notification is cleared before anything else, then
     * the inbox is checked. You then hear from the app: important emails, and ones the AI couldn't sort,
     * get a notification; the rest are marked read and archived quietly. If the email can't be found in
     * the mailbox at all, you're told from the email app's own notification instead, so none is missed.
     * The email app can hold any kind of account (Gmail often shows Yahoo accounts too), so the account
     * comes from the notification itself.
     */
    fun onMailAppNotification(alert: AppAlert) {
        if (!prefs.mailAgentOn.value) return
        scope.launch {
            // An address the app has not been given: its notification is left alone.
            if (accountsFor(alert).isEmpty()) return@launch
            app.brokeometer.capture.MailAppAlerts.takeOver(alert)
            synchronized(waitingAlerts) {
                waitingAlerts.removeAll { it.alert.key == alert.key }
                waitingAlerts += TakenAlert(alert, System.currentTimeMillis())
            }
            alertTurns.trySend(Unit)
        }
    }

    /** The accounts an email app's notification can be for. */
    private suspend fun accountsFor(alert: AppAlert): List<MailAccountEntity> {
        val all = dao.accounts()
        val hint = alert.account
        val hinted = hint?.let { h -> all.filter { h.contains(it.address, ignoreCase = true) } }.orEmpty()
        return when {
            hinted.isNotEmpty() -> hinted
            hint != null && hint.contains('@') -> emptyList()
            alert.packageName == app.brokeometer.capture.MailAppAlerts.YAHOO ->
                all.filter { it.provider == MailProviders.YAHOO }.ifEmpty { all }
            else -> all
        }
    }

    private fun waiting(): List<TakenAlert> = synchronized(waitingAlerts) { waitingAlerts.toList() }

    /** One go at the waiting notifications: check their inboxes, then once more a little later for any not found. */
    private suspend fun takeOverTurn() {
        val first = waiting()
        if (first.isEmpty()) return
        lock.withLock {
            first.flatMap { accountsFor(it.alert) }.distinctBy { it.id }.forEach { inbox(it) }
            clearAppAlerts()
        }
        if (waiting().isEmpty()) return
        // The email app can be a moment ahead of the mail server.
        delay(ALERT_GRACE_MS)
        lock.withLock {
            val left = waiting()
            left.flatMap { accountsFor(it.alert) }.distinctBy { it.id }.forEach { inbox(it) }
            clearAppAlerts()
            // Still not in the mailbox: told from the email app's own notification, so it isn't missed.
            val now = System.currentTimeMillis()
            val stale = waiting().filter { now - it.at >= ALERT_GRACE_MS }
            stale.forEach {
                notifier.unchecked(it.alert)
                repository.log(it.alert.packageName, it.alert.title, "", "Email: not in the mailbox yet, so you were told from the email app's notification")
            }
            synchronized(waitingAlerts) { waitingAlerts.removeAll { w -> stale.any { it.alert.key == w.alert.key } } }
            synchronized(uncheckedShown) { uncheckedShown += stale }
        }
    }

    /**
     * Matches taken-over notifications with emails now in the list: those waiting stop waiting, and a
     * "couldn't check yet" notification goes once its email is found (the email's own outcome tells you).
     */
    private suspend fun settleTaken() {
        val waitingNow = waiting()
        val shown = synchronized(uncheckedShown) { uncheckedShown.toList() }
        if (waitingNow.isEmpty() && shown.isEmpty()) return
        val oldest = (waitingNow + shown).minOf { it.at }
        val entries = dao.inboxLogSince(oldest - TAKEN_MATCH_WINDOW_MS)
        if (entries.isEmpty()) return
        val accounts = dao.accounts().associate { it.id to it.address }
        val foundIds = ArrayList<Long>()
        fun found(t: TakenAlert): Boolean {
            val matched = AlertMatcher.entriesFor(t.alert, entries.filter { it.at >= t.at - TAKEN_MATCH_WINDOW_MS }, accounts)
            foundIds += matched.map { it.id }
            return matched.isNotEmpty()
        }
        val settled = waitingNow.filter { found(it) }
        synchronized(waitingAlerts) { waitingAlerts.removeAll { w -> settled.any { it.alert.key == w.alert.key } } }
        val told = shown.filter { found(it) }
        told.forEach { notifier.cancelUnchecked(it.alert) }
        synchronized(uncheckedShown) { uncheckedShown.removeAll { w -> told.any { it.alert.key == w.alert.key } } }
        if (foundIds.isNotEmpty()) dao.markCleared(foundIds.distinct())
    }

    /**
     * Checks every account's inbox and spam (every 15 minutes, on opening the app, and on Check now).
     * [matchNow] makes the list match the email app straight away, not just every few minutes.
     */
    suspend fun checkAll(inbox: Boolean = true, spam: Boolean = true, matchNow: Boolean = false) {
        if (!prefs.mailAgentOn.value) return
        lock.withLock {
            for (account in dao.accounts()) {
                if (inbox) inbox(account, matchNow)
                if (spam) spam(account)
            }
            clearAppAlerts()
        }
    }

    /**
     * Pull to refresh: only the inbox, of one account ([accountId]) or of all of them when null, and
     * matched with the email app straight away. Check now stays the full check.
     */
    suspend fun checkInbox(accountId: Long?) {
        if (!prefs.mailAgentOn.value) return
        lock.withLock {
            for (account in dao.accounts()) {
                if (accountId == null || account.id == accountId) inbox(account, matchNow = true)
            }
            clearAppAlerts()
        }
    }

    /**
     * Clears the email apps' notifications for emails the agent dealt with in the last few hours (ones
     * that came in while it wasn't listening, say), and settles the ones it took over.
     */
    private suspend fun clearAppAlerts() {
        settleTaken()
        val alerts = app.brokeometer.capture.MailAppAlerts.active(prefs.testMode)
        if (alerts.isEmpty()) return
        val entries = dao.inboxLogSince(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6))
        if (entries.isEmpty()) return
        val accounts = dao.accounts().associate { it.id to it.address }
        val result = AlertMatcher.match(alerts, entries, accounts)
        val byKey = alerts.associateBy { it.key }
        val cleared = result.clear.filter { app.brokeometer.capture.MailAppAlerts.cancel(it) }
        if (cleared.isNotEmpty() && result.logIds.isNotEmpty()) dao.markCleared(result.logIds)
        cleared.mapNotNull { byKey[it] }.filter { !it.summary }.forEach {
            repository.log(it.packageName, it.title, "", "Email: notification cleared, the agent dealt with it")
        }
    }

    /**
     * The inbox: every new email goes into the list, and the AI's answer decides what happens to it.
     * Nothing stops at one email: one the AI can't decide on is listed as undecided (left unread with its
     * notification) and the rest carry on; it is tried again on later checks for [UNDECIDED_RETRY_MS].
     */
    private suspend fun inbox(account: MailAccountEntity, matchNow: Boolean = false): List<MailItem> = withContext(Dispatchers.IO) {
        val password = prefs.mailPassword(account.address)
        if (password.isEmpty()) return@withContext emptyList()
        val categories = dao.categories()
        if (categories.isEmpty()) return@withContext emptyList()
        val handled = ArrayList<MailItem>()
        try {
            client.session(mail.server(account), account.address, password, account.provider, background = true).use { s ->
                // Signed in: an old warning about this account no longer applies.
                mail.clearNoteAbout(account.address)
                val cursor = dao.cursor(account.id, MailFolders.INBOX)
                var (items, next) = s.newSince(MailFolders.INBOX, cursor?.uidValidity, cursor?.lastUid, MAX_PER_CHECK)
                    ?: return@withContext emptyList()
                if (cursor == null || cursor.uidValidity != next.first) {
                    // First look at this inbox: start from now.
                    dao.saveCursor(MailCursorEntity(account.id, MailFolders.INBOX, next.first, next.second))
                    return@withContext emptyList()
                }
                // Emails the AI couldn't decide on before get another go first.
                retryUndecided(account, s, categories, handled)
                var lastDone = cursor.lastUid
                var batches = 0
                while (true) {
                    for (item in items.sortedBy { it.uid }) {
                        if (handleInboxItem(account, s, item, categories, retry = null)) handled += item
                        // Saved after each email, so a check cut short never loses one.
                        lastDone = item.uid
                        dao.saveCursor(MailCursorEntity(account.id, MailFolders.INBOX, next.first, lastDone))
                    }
                    if (items.isEmpty()) {
                        lastDone = maxOf(lastDone, next.second)
                        dao.saveCursor(MailCursorEntity(account.id, MailFolders.INBOX, next.first, lastDone))
                        break
                    }
                    batches++
                    if (items.size < MAX_PER_CHECK || batches >= MAX_BATCHES) break
                    val more = s.newSince(MailFolders.INBOX, next.first, lastDone, MAX_PER_CHECK) ?: break
                    items = more.first
                    next = more.second
                }
                try {
                    matchEmailApp(account, s, matchNow)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Tried again on the next check.
                }
            }
        } catch (e: MailClient.LoginRefused) {
            loginTrouble(account.address)
            return@withContext handled
        } catch (e: MailClient.Busy) {
            // Another check had the mailbox: the next one picks these up.
            return@withContext handled
        } catch (e: MailClient.MailError) {
            mail.note("Agent could not check ${account.address}: ${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            mail.note("Agent problem with ${account.address}: ${e.javaClass.simpleName}")
        }
        dao.trimLog()
        handled
    }

    /** Inbox emails the AI couldn't decide on: asked again while they're still unread in the inbox. */
    private suspend fun retryUndecided(account: MailAccountEntity, s: MailSession, categories: List<MailCategoryEntity>, handled: MutableList<MailItem>) {
        val waiting = dao.undecidedSince(account.id, System.currentTimeMillis() - UNDECIDED_RETRY_MS, MAX_PER_CHECK)
        if (waiting.isEmpty()) return
        val found = s.itemsByUid(MailFolders.INBOX, waiting.map { it.uid }).associateBy { it.uid }
        for (w in waiting) {
            // Gone from the inbox, or another email under that UID: matching with the email app sorts it out.
            val item = found[w.uid]?.takeIf { it.messageId == w.messageId } ?: continue
            if (handleInboxItem(account, s, item, categories, retry = w)) handled += item
        }
    }

    /**
     * One inbox email, start to finish. It always ends up in the list: decided (notified, archived, read),
     * or undecided when the AI can't answer or the mailbox won't take the change. True when the agent
     * dealt with it, so the email app's own notification can go.
     */
    private suspend fun handleInboxItem(
        account: MailAccountEntity,
        s: MailSession,
        item: MailItem,
        categories: List<MailCategoryEntity>,
        retry: MailLogEntity?,
    ): Boolean {
        if (retry == null) {
            val before = item.messageId?.let { dao.handledCount(it) } ?: 0
            // Put back in the inbox with Undo, or taken out of spam by you: leave it.
            if (before > 0) return false
        }
        val waiting = retry ?: item.messageId?.let { dao.undecided(it) }
        fun entry(category: MailCategoryEntity?, action: String, summary: String) = MailLogEntity(
            id = waiting?.id ?: 0,
            at = System.currentTimeMillis(),
            accountId = account.id,
            folder = MailFolders.INBOX,
            uid = item.uid,
            messageId = item.messageId,
            fromName = item.fromName,
            fromAddress = item.fromAddress,
            subject = item.subject,
            categoryId = category?.id,
            categoryName = category?.name.orEmpty(),
            action = action,
            summary = summary,
        )
        suspend fun save(row: MailLogEntity): Long = if (waiting != null) {
            dao.updateLog(row)
            row.id
        } else {
            dao.insertLog(row)
        }
        // Undecided: listed (keeping when it first came in, which is how long it's tried for), and tried again later.
        // [tell]: you get a notification the first time (the AI couldn't sort it). Not when only the mailbox
        // refused a change to an email that doesn't matter.
        suspend fun undecided(why: String, tell: Boolean) {
            if (waiting != null && waiting.action == MailActions.UNDECIDED) {
                dao.setLogOutcome(waiting.id, MailActions.UNDECIDED, why)
            } else {
                val row = entry(null, MailActions.UNDECIDED, why)
                val id = save(row)
                if (tell) {
                    notifier.undecided(row.copy(id = id), account)
                    onTold(id)
                }
            }
        }
        // Sorted on a later try: a "couldn't sort" notification from before goes.
        fun sortedNow() {
            if (waiting != null) notifier.cancel(waiting.id)
        }
        if (item.seen) {
            save(entry(null, MailActions.READ_ELSEWHERE, "You had already read it, so it was left as it was."))
            sortedNow()
            return false
        }
        val code = MailCodes.find(item.subject, item.text)
        if (code != null) {
            // A code (OTP) always notifies, at once and with no AI, whatever its kind.
            val usual = dao.sender(account.id, item.fromAddress)?.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
            val row = entry(usual, MailActions.NOTIFIED, "Has a code, so you were told straight away.")
            val id = save(row)
            notifier.important(row.copy(id = id), account, code)
            onTold(id)
            return true
        }
        if (item.fromAddress.isNotEmpty() && item.fromAddress.lowercase() in prefs.quietSenders.value) {
            // A sender you said isn't important: no AI, just marked read.
            if (runCatching { s.markRead(MailFolders.INBOX, item.uid) }.isFailure) {
                undecided("Left unread: your mailbox didn't take the change. Tried again on the next check.", tell = false)
                return false
            }
            save(entry(null, MailActions.MARKED_READ, QUIET_SUMMARY))
            sortedNow()
            return true
        }
        // A sender you said is important always reaches you.
        val vip = item.fromAddress.isNotEmpty() && item.fromAddress.lowercase() in prefs.importantSenders.value
        return when (val decision = decide(item, categories)) {
            is Decision.Undecided -> {
                if (vip) {
                    val row = entry(null, MailActions.NOTIFIED, "${item.subject.take(80)}. You said this sender is important.")
                    val id = save(row)
                    notifier.important(row.copy(id = id), account)
                    onTold(id)
                    true
                } else {
                    // Left unread in the inbox, you're told, and it's tried again on later checks.
                    undecided(decision.why, tell = true)
                    false
                }
            }
            is Decision.Made -> {
                val category = decision.category
                // What the email says decides: anything about you reaches you, whatever its kind.
                val why = when {
                    category.important -> ""
                    vip -> " You said this sender is important."
                    decision.personal -> " It's about you, so you were told even though ${category.name} is off."
                    else -> null
                }
                if (why != null) {
                    val summary = decision.summary + why
                    val id = save(entry(category, MailActions.NOTIFIED, summary))
                    notifier.important(entry(category, MailActions.NOTIFIED, summary).copy(id = id), account)
                    onTold(id)
                } else {
                    val archived = runCatching { s.archive(item.uid) }.getOrDefault(false)
                    val read = archived || runCatching { s.markRead(MailFolders.INBOX, item.uid) }.isSuccess
                    when {
                        archived -> save(entry(category, MailActions.ARCHIVED, decision.summary))
                        read -> save(entry(category, MailActions.MARKED_READ, decision.summary + " It couldn't be archived, so it was marked read in your inbox."))
                        else -> {
                            undecided("Left unread: your mailbox didn't take the change. Tried again on the next check.", tell = false)
                            return false
                        }
                    }
                    sortedNow()
                }
                learn(account, item, category)
                true
            }
        }
    }

    /** When each account's list was last matched with the email app. */
    private val matchedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    @Volatile private var openedAt = 0L

    /** The app came to the front: a quick check, so the list matches what the email app shows. */
    fun onAppOpened() {
        if (!prefs.mailAgentOn.value) return
        val now = System.currentTimeMillis()
        if (now - openedAt < OPEN_CHECK_EVERY_MS) return
        openedAt = now
        scope.launch { runCatching { checkAll(inbox = true, spam = true, matchNow = true) } }
    }

    /**
     * Makes the list match the email app (Gmail or Yahoo Mail): an email read there shows as read
     * here, one unread there shows as unread (also one whose archiving didn't go through), and one
     * archived or deleted there leaves Needs you. Looks at the last [MATCH_DAYS] days and at every
     * email the list shows as unread. Every few minutes, or straight away when asked.
     */
    private suspend fun matchEmailApp(account: MailAccountEntity, s: MailSession, now: Boolean) {
        val at = System.currentTimeMillis()
        if (!now && at - (matchedAt[account.id] ?: 0L) < MATCH_EVERY_MS) return
        matchedAt[account.id] = at
        val since = at - MATCH_DAYS * 24 * 60 * 60_000L
        val latest = (dao.inboxLogSince(since) + dao.leftUnread())
            .filter { it.accountId == account.id && it.messageId != null && !MailActions.binned(it) }
            // In the inbox, or moved there (out of spam, or put back), not ones still in spam.
            .filter { it.folder == MailFolders.INBOX || it.undone || it.action == MailActions.NOT_SPAM || it.action == MailActions.MOVED_FROM_SPAM }
            .groupBy { it.messageId!! }
            .mapValues { (_, list) -> list.maxBy { it.id } }
            .values
        if (latest.isEmpty()) return
        // Put back in the inbox (Undo, or out of spam) gives an email a new UID, so those are found by Message-ID.
        val inboxUids = latest.filter { it.folder == MailFolders.INBOX && !it.undone }.associate { it.uid to it.messageId!! }
        val search = latest.filter { MailActions.leftUnread(it) }.sortedByDescending { it.at }.map { it.messageId!! }
        val places = s.places(inboxUids, search)
        for (e in latest) {
            val place = places[e.messageId] ?: continue
            val to = MailActions.matching(e, place) ?: continue
            dao.setLogState(e.id, to, false)
            // Dealt with in the email app: the agent's own notification for it goes too.
            if (to != MailActions.MARKED_UNREAD) notifier.cancel(e.id)
        }
    }

    /**
     * A refused sign-in. Yahoo turns some down now and then even with the right app password, so the
     * agent only says something once they've kept being refused for a while.
     */
    private fun loginTrouble(address: String) {
        val t = MailClient.troubleFor(address) ?: return
        if (t.count < WARN_AFTER_REFUSALS || System.currentTimeMillis() - t.firstAt < WARN_AFTER_MS) return
        mail.note(
            "$address has refused the agent's sign-ins since ${app.brokeometer.util.Dates.clock(t.firstAt)} " +
                "(it said: ${t.reason.take(100)}). If this keeps up, the app password may be wrong or revoked: add a new one in Email setup.",
        )
    }

    /**
     * The spam folder: every email in it goes into the list. Real spam goes to the bin; anything the AI
     * isn't sure about stays in spam with a "might not be spam" notification. One the AI can't judge, or
     * that won't move, is listed as not checked yet and tried again on later checks, and the rest carry
     * on. Read from the oldest the first time, so what is there already gets checked too.
     */
    private suspend fun spam(account: MailAccountEntity) = withContext(Dispatchers.IO) {
        val password = prefs.mailPassword(account.address)
        if (password.isEmpty()) return@withContext
        try {
            client.session(mail.server(account), account.address, password, account.provider, background = true).use { s ->
                retrySpam(account, s)
                val cursor = dao.cursor(account.id, MailFolders.SPAM)
                var (items, next) = s.newSince(
                    MailFolders.SPAM, cursor?.uidValidity, cursor?.lastUid, MAX_SPAM_PER_CHECK, startAtOldest = true,
                ) ?: run {
                    mail.note("Couldn't find the spam folder in ${account.address}, so its spam isn't being checked.")
                    return@withContext
                }
                val sameFolder = cursor != null && cursor.uidValidity == next.first
                var lastDone = if (sameFolder) cursor!!.lastUid else 0L
                var batches = 0
                while (true) {
                    for (item in items.sortedBy { it.uid }) {
                        judgeSpam(account, s, item, retry = null)
                        // Saved after each email, so a check cut short never loses one.
                        lastDone = item.uid
                        dao.saveCursor(MailCursorEntity(account.id, MailFolders.SPAM, next.first, lastDone))
                    }
                    if (items.isEmpty()) {
                        lastDone = maxOf(lastDone, next.second)
                        dao.saveCursor(MailCursorEntity(account.id, MailFolders.SPAM, next.first, lastDone))
                        break
                    }
                    batches++
                    if (items.size < MAX_SPAM_PER_CHECK || batches >= MAX_SPAM_BATCHES) break
                    val more = s.newSince(MailFolders.SPAM, next.first, lastDone, MAX_SPAM_PER_CHECK, startAtOldest = true) ?: break
                    items = more.first
                    next = more.second
                }
            }
        } catch (e: MailClient.LoginRefused) {
            loginTrouble(account.address)
        } catch (e: MailClient.Busy) {
            // Another check had the mailbox: the next one looks again.
        } catch (e: MailClient.MailError) {
            mail.note("Spam check could not reach ${account.address}: ${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            mail.note("Spam check problem with ${account.address}: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Spam not checked yet gets another go. One no longer in spam was moved or deleted in the email app;
     * one the AI still can't judge after [GIVE_UP_MS] is left in spam with a "might not be spam"
     * notification, since nobody could say it is spam.
     */
    private suspend fun retrySpam(account: MailAccountEntity, s: MailSession) {
        val waiting = dao.uncheckedSpam(account.id, MAX_SPAM_PER_CHECK)
        if (waiting.isEmpty()) return
        val found = s.itemsByUid(MailFolders.SPAM, waiting.map { it.uid }).associateBy { it.uid }
        val now = System.currentTimeMillis()
        for (w in waiting) {
            val item = found[w.uid]?.takeIf { it.messageId == w.messageId }
            if (item == null) {
                dao.setLogOutcome(w.id, MailActions.MOVED_FROM_SPAM, GONE_FROM_SPAM)
                continue
            }
            if (w.summary.startsWith(NOT_CHECKED) && now - w.at > GIVE_UP_MS) {
                val summary = "The AI couldn't judge it for hours, so it was left in spam for you to look at."
                dao.setLogOutcome(w.id, MailActions.SPAM_FLAGGED, summary)
                notifier.maybeNotSpam(w.copy(action = MailActions.SPAM_FLAGGED, summary = summary), account)
                continue
            }
            judgeSpam(account, s, item, retry = w)
        }
    }

    /** One spam email, start to finish. It always ends up in the list, whatever happens. */
    private suspend fun judgeSpam(account: MailAccountEntity, s: MailSession, item: MailItem, retry: MailLogEntity?) {
        // Already listed (the folder was read again from the start): left as it is.
        if (retry == null && item.messageId != null && dao.logsFor(listOf(item.messageId)).any { it.folder == MailFolders.SPAM }) return
        suspend fun put(action: String, summary: String): MailLogEntity {
            val row = MailLogEntity(
                id = retry?.id ?: 0,
                at = System.currentTimeMillis(),
                accountId = account.id,
                folder = MailFolders.SPAM,
                uid = item.uid,
                messageId = item.messageId,
                fromName = item.fromName,
                fromAddress = item.fromAddress,
                subject = item.subject,
                categoryId = null,
                categoryName = "Spam",
                action = action,
                summary = summary,
            )
            return if (retry != null) {
                dao.updateLog(row)
                row
            } else {
                row.copy(id = dao.insertLog(row))
            }
        }
        // Not checked yet: listed (keeping when it first came in), and tried again later.
        suspend fun notYet(summary: String) {
            if (retry == null) put(MailActions.SPAM_UNCHECKED, summary) else dao.setLogOutcome(retry.id, MailActions.SPAM_UNCHECKED, summary)
        }
        /** Moves it out of spam; [done] is what happened when it moved. */
        suspend fun move(toBin: Boolean, done: suspend () -> Unit) {
            val r = runCatching { s.moveOutOfSpam(item.messageId, item.uid, toBin) }
            when (r.getOrNull()) {
                MailSession.SpamMove.MOVED -> done()
                MailSession.SpamMove.NOT_IN_SPAM -> put(MailActions.MOVED_FROM_SPAM, GONE_FROM_SPAM)
                MailSession.SpamMove.NO_FOLDER -> {
                    notYet("${if (toBin) NOT_MOVED_TO_BIN else NOT_MOVED_TO_INBOX}: couldn't find the ${if (toBin) "bin" else "inbox"} folder. Tried again on the next check.")
                    mail.note("Couldn't find the ${if (toBin) "bin" else "inbox"} folder in ${account.address}, so spam can't be moved.")
                }
                null -> notYet("${if (toBin) NOT_MOVED_TO_BIN else NOT_MOVED_TO_INBOX}: your mailbox didn't take the change. Tried again on the next check.")
            }
        }
        val sender = item.fromAddress.lowercase()
        // Senders you have already judged skip the AI.
        if (sender.isNotEmpty() && sender in prefs.notSpamSenders.value) {
            move(toBin = false) { put(MailActions.MOVED_FROM_SPAM, "You said this sender isn't spam, so it went to your inbox.") }
            return
        }
        if (sender.isNotEmpty() && sender in prefs.spamSenders.value) {
            move(toBin = true) { put(MailActions.SPAM_DELETED, "You marked this sender as spam before.") }
            return
        }
        // Spam it already judged, that only failed to move: no need to ask the AI again.
        if (retry != null && retry.summary.startsWith(NOT_MOVED_TO_BIN)) {
            move(toBin = true) { put(MailActions.SPAM_DELETED, "Spam, moved to the bin.") }
            return
        }
        val reply = mail.ask(MailPrompts.checkSpam(item), maxTokens = 200)
        val verdict = (reply as? Brain.Answer.Text)?.let { MailPrompts.parseSpam(it.text) }
        if (verdict == null) {
            val why = when (reply) {
                is Brain.Answer.Failed -> reply.message
                is Brain.Answer.Text -> "its answer couldn't be read"
            }
            if (reply is Brain.Answer.Failed) aiTrouble(reply.message)
            notYet("$NOT_CHECKED: the AI couldn't judge it ($why). Tried again on the next check.")
            return
        }
        clearAiTrouble()
        if (verdict.spam) {
            move(toBin = true) { put(MailActions.SPAM_DELETED, verdict.why) }
        } else {
            notifier.maybeNotSpam(put(MailActions.SPAM_FLAGGED, verdict.why), account)
        }
    }

    /** The AI couldn't answer: said on the Email page until it answers again. */
    private fun aiTrouble(message: String) {
        mail.note("$AI_TROUBLE ($message). They're listed, and tried again on the next check.")
    }

    private fun clearAiTrouble() = mail.clearNoteStarting(AI_TROUBLE)

    private sealed interface Decision {
        data class Made(val category: MailCategoryEntity, val summary: String, val personal: Boolean) : Decision
        data class Undecided(val why: String) : Decision
    }

    /**
     * The AI's category for one email, from what the email says. Nothing is decided from the sender
     * alone: if the AI can't be reached, the email stays unread with its notification until it can.
     */
    private suspend fun decide(item: MailItem, categories: List<MailCategoryEntity>): Decision {
        val reply = mail.ask(MailPrompts.decideEmail(categories, item), maxTokens = 300)
        val decision = (reply as? Brain.Answer.Text)?.let { MailPrompts.parseDecision(it.text, categories.size) }
        if (decision != null) {
            clearAiTrouble()
            return Decision.Made(categories[decision.category], decision.summary.ifBlank { item.subject }, decision.personal)
        }
        if (reply is Brain.Answer.Failed) aiTrouble(reply.message)
        val why = when (reply) {
            is Brain.Answer.Failed -> reply.message
            is Brain.Answer.Text -> "The AI's answer could not be read."
        }
        return Decision.Undecided("Left unread: the AI couldn't read it to decide ($why). Tried again on the next check.")
    }

    /** New senders are remembered with the category of their first email. */
    private suspend fun learn(account: MailAccountEntity, item: MailItem, category: MailCategoryEntity) {
        if (item.fromAddress.isEmpty()) return
        val sender = dao.sender(account.id, item.fromAddress)
        if (sender == null) {
            dao.upsertSenders(
                listOf(
                    MailSenderEntity(
                        accountId = account.id,
                        address = item.fromAddress,
                        name = item.fromName,
                        categoryId = category.id,
                        categorySource = MailAgent.SOURCE_AI,
                        total = 1,
                        unread = 1,
                        recent = 1,
                        firstAt = item.receivedAt,
                        lastAt = item.receivedAt,
                        subjects = item.subject.take(120),
                        unsubscribe = null,
                        oneClick = false,
                        unsubscribedAt = null,
                    )
                )
            )
        } else if (sender.categoryId == null) {
            dao.setSenderCategory(sender.id, category.id, MailAgent.SOURCE_AI)
        }
    }

    /**
     * One-off tidy-up of every unread email already in the inboxes. Kinds switched off are marked read
     * and archived; kinds switched on, and anything unclear, are left unread. No notifications, since
     * these are old. Every email is listed under Activity, archived ones with an Undo.
     */
    suspend fun tidy(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val categories = dao.categories()
            if (categories.isEmpty()) {
                mail.report(MailProgress(MailProgress.Stage.FAILED, message = "Make the categories first: Setup, then Read my email."))
                return@withContext
            }
            val names = categories.map { it.name }
            var archived = 0
            var kept = 0
            var unclear = 0
            var quietRead = 0
            val problems = ArrayList<String>()
            for (account in dao.accounts()) {
                if (!coroutineContext.isActive) break
                val password = prefs.mailPassword(account.address)
                if (password.isEmpty()) continue
                try {
                    client.session(mail.server(account), account.address, password, account.provider).use { s ->
                        mail.report(MailProgress(MailProgress.Stage.TIDYING, account.address, 0, 0, "Finding unread emails"))
                        val uids = s.unreadInbox()
                        var done = 0
                        for (chunk in uids.chunked(TIDY_CHUNK)) {
                            if (!coroutineContext.isActive) break
                            mail.report(MailProgress(MailProgress.Stage.TIDYING, account.address, done, uids.size))
                            val unread = s.headers(chunk).filter { item ->
                                !item.seen && (item.messageId == null || dao.listedCount(item.messageId) == 0)
                            }
                            // Senders you said aren't important skip the AI and are just marked read.
                            val (quiet, todo) = unread.partition { it.fromAddress.isNotEmpty() && it.fromAddress in prefs.quietSenders.value }
                            if (quiet.isNotEmpty() && runCatching { s.markReadInbox(quiet.map { it.uid }, emptyList()) }.isSuccess) {
                                quiet.forEach { dao.insertLog(tidyEntry(account, it, null, MailActions.MARKED_READ, QUIET_SUMMARY)) }
                                quietRead += quiet.size
                            }
                            if (todo.isNotEmpty()) {
                                val reply = mail.askPatiently(MailPrompts.tidyBatch(categories, todo), smart = false, maxTokens = 2000)
                                val answer = (reply as? Brain.Answer.Text)?.text
                                val picked = answer?.let { MailPrompts.parseAssignments(it, todo.size, categories.size, names) }.orEmpty()
                                val personal = answer?.let { MailPrompts.parsePersonal(it, todo.size) }.orEmpty()
                                val toArchive = ArrayList<Pair<MailItem, MailCategoryEntity>>()
                                for ((i, item) in todo.withIndex()) {
                                    // Only what the AI made of the email itself counts, never the sender's usual kind.
                                    val category = picked[i]?.let { categories[it] }
                                    val vip = item.fromAddress.lowercase() in prefs.importantSenders.value
                                    when {
                                        category == null -> {
                                            dao.insertLog(tidyEntry(account, item, null, MailActions.KEPT, "Tidy-up: not sure what this is, so it was left unread."))
                                            unclear++
                                        }
                                        vip -> {
                                            dao.insertLog(tidyEntry(account, item, category, MailActions.KEPT, "Tidy-up: you said this sender is important, so it was left unread."))
                                            kept++
                                        }
                                        i in personal && !category.important -> {
                                            dao.insertLog(tidyEntry(account, item, category, MailActions.KEPT, "Tidy-up: it's about you, so it was left unread."))
                                            kept++
                                        }
                                        category.important -> {
                                            dao.insertLog(tidyEntry(account, item, category, MailActions.KEPT, "Tidy-up: ${category.name} is switched on, so it was left unread."))
                                            kept++
                                        }
                                        else -> toArchive += item to category
                                    }
                                }
                                if (toArchive.isNotEmpty() && runCatching { s.archiveMany(toArchive.map { it.first.uid }) }.getOrDefault(false)) {
                                    for ((item, category) in toArchive) {
                                        dao.insertLog(tidyEntry(account, item, category, MailActions.ARCHIVED, "Tidy-up: ${category.name} is switched off."))
                                    }
                                    archived += toArchive.size
                                }
                            }
                            done += chunk.size
                        }
                    }
                } catch (e: MailClient.MailError) {
                    problems += "${account.address}: ${e.message}"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problems += "${account.address}: ${e.javaClass.simpleName}"
                }
            }
            dao.trimLog()
            val summary = buildList {
                add("Tidied up: ${"%,d".format(archived)} marked read and archived, ${"%,d".format(kept)} left unread because they matter.")
                if (unclear > 0) add("${"%,d".format(unclear)} were unclear and left unread.")
                if (quietRead > 0) add("${"%,d".format(quietRead)} from senders you said aren't important were marked read.")
                addAll(problems.map { "Could not finish $it" })
            }.joinToString("\n")
            mail.report(MailProgress(if (problems.isEmpty()) MailProgress.Stage.DONE else MailProgress.Stage.FAILED, message = summary))
        }
    }

    private fun tidyEntry(account: MailAccountEntity, item: MailItem, category: MailCategoryEntity?, action: String, summary: String) = MailLogEntity(
        at = System.currentTimeMillis(),
        accountId = account.id,
        folder = MailFolders.INBOX,
        uid = item.uid,
        messageId = item.messageId,
        fromName = item.fromName,
        fromAddress = item.fromAddress,
        subject = item.subject,
        categoryId = category?.id,
        categoryName = category?.name.orEmpty(),
        action = action,
        summary = summary,
    )

    // Buttons on the notifications and on the Activity list

    /** You said it is spam: off to the bin, and that sender's spam goes straight there from now on. */
    suspend fun markSpam(logId: Long) = act(logId) { s, log ->
        val ok = s.spamToBin(log.messageId, log.uid)
        if (ok) {
            dao.setLogOutcome(log.id, MailActions.SPAM_DELETED, "You said it's spam, so it went to the bin.")
            log.fromAddress.takeIf { it.isNotEmpty() }?.let { prefs.addSpamSender(it) }
            notifier.cancel(log.id)
        }
        ok
    }

    /** You said it is not spam: into the inbox, and that sender is never treated as spam again. */
    suspend fun notSpam(logId: Long) = act(logId) { s, log ->
        val id = log.messageId
        val ok = when (log.action) {
            MailActions.SPAM_DELETED -> id != null && s.unbin(id)
            else -> s.spamToInbox(id, log.uid)
        }
        if (ok) {
            dao.setLogOutcome(log.id, MailActions.NOT_SPAM, "You said it isn't spam, so it went to your inbox.")
            log.fromAddress.takeIf { it.isNotEmpty() }?.let { prefs.addNotSpamSender(it) }
            notifier.cancel(log.id)
        }
        ok
    }

    /** The notification's Mark read button. The email stays in the inbox. */
    suspend fun markRead(logId: Long): Boolean {
        val log = dao.log(logId) ?: return false
        return change(listOf(log.copy(action = MailActions.MARKED_READ, undone = false)))
    }

    /**
     * Puts emails into the states asked for: read, unread, archived, or back in the inbox. The mail
     * server changes first, then the Activity list, which keeps the AI's line about each email. Passing
     * the entries as they were undoes a change. An email no longer where it was (deleted or moved in the
     * email app) only changes on the list. False when an account could not be reached.
     */
    suspend fun change(targets: List<MailLogEntity>): Boolean = withContext(Dispatchers.IO) {
        if (targets.isEmpty()) return@withContext true
        val current = targets.map { it.id }.chunked(500).flatMap { dao.logs(it) }.associateBy { it.id }
        var ok = true
        for ((accountId, list) in targets.groupBy { it.accountId }) {
            try {
                val account = dao.account(accountId)
                if (account != null) {
                    val password = prefs.mailPassword(account.address)
                    client.session(mail.server(account), account.address, password, account.provider).use { s ->
                        val read = ArrayList<MailLogEntity>()
                        val unread = ArrayList<MailLogEntity>()
                        for (target in list) {
                            val now = current[target.id] ?: continue
                            val wasArchived = MailActions.archived(now)
                            val willArchive = MailActions.archived(target)
                            val wasBinned = MailActions.binned(now)
                            val willBin = MailActions.binned(target)
                            when {
                                !wasBinned && willBin -> now.messageId?.let { s.binById(it) }
                                wasBinned && !willBin -> {
                                    // Out of the bin into the inbox, unread; then where it was asked to be.
                                    val id = now.messageId
                                    if (id != null && s.unbin(id)) {
                                        if (willArchive) s.archiveById(id) else if (!MailActions.leftUnread(target)) read += now
                                    }
                                }
                                wasBinned -> Unit
                                wasArchived && !willArchive -> {
                                    // Back to the inbox, unread; then read if that is what was asked.
                                    now.messageId?.let { s.unarchive(it) }
                                    if (!MailActions.leftUnread(target)) read += now
                                }
                                !wasArchived && willArchive -> {
                                    val id = now.messageId
                                    if (id != null) s.archiveById(id) else if (now.folder == MailFolders.INBOX) s.archive(now.uid)
                                }
                                MailActions.leftUnread(now) && !MailActions.leftUnread(target) -> read += now
                                !MailActions.leftUnread(now) && MailActions.leftUnread(target) -> unread += now
                            }
                        }
                        if (read.isNotEmpty()) s.setSeenInbox(uidsOf(read), idsOf(read), true)
                        if (unread.isNotEmpty()) s.setSeenInbox(uidsOf(unread), idsOf(unread), false)
                    }
                }
                // An account removed since has nothing left to change, so only the list changes.
                for (target in list) {
                    dao.setLogState(target.id, target.action, target.undone)
                    if (!MailActions.leftUnread(target)) notifier.cancel(target.id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ok = false
            }
        }
        clearAppAlerts()
        ok
    }

    /** Found by UID only when there is no Message-ID; a UID changes when an email moves. */
    private fun uidsOf(list: List<MailLogEntity>) = list.filter { it.messageId == null && it.folder == MailFolders.INBOX }.map { it.uid }

    private fun idsOf(list: List<MailLogEntity>) = list.mapNotNull { it.messageId }

    /** What "sender not important" did, and how to put it back. */
    data class Quieted(val count: Int, val failed: Boolean, val before: List<MailLogEntity>)

    /**
     * You said this sender isn't important: their unread emails are marked read now, in every account,
     * and from now on as they arrive. [Quieted.before] holds those emails as they were, for Undo.
     */
    suspend fun quietSender(address: String): Quieted = withContext(Dispatchers.IO) {
        val sender = address.lowercase()
        prefs.setQuietSender(sender, true)
        var count = 0
        var failed = false
        val before = ArrayList<MailLogEntity>()
        for (account in dao.accounts()) {
            val password = prefs.mailPassword(account.address)
            if (password.isEmpty()) continue
            try {
                client.session(mail.server(account), account.address, password, account.provider).use { s ->
                    val items = s.headers(s.unreadFrom(sender)).filter { it.fromAddress == sender && !it.seen }
                    if (items.isEmpty()) return@use
                    s.markReadInbox(items.map { it.uid }, emptyList())
                    val known = items.mapNotNull { it.messageId }.chunked(500).flatMap { dao.logsFor(it) }
                    val unread = known.filter { MailActions.leftUnread(it) }
                    before += unread
                    if (unread.isNotEmpty()) dao.markReadLogs(unread.map { it.id })
                    unread.forEach { notifier.cancel(it.id) }
                    val knownIds = known.mapNotNull { it.messageId }.toSet()
                    items.filter { it.messageId == null || it.messageId !in knownIds }.forEach {
                        val row = tidyEntry(account, it, null, MailActions.MARKED_READ, QUIET_SUMMARY)
                        val id = dao.insertLog(row)
                        // These were unread before, though not on the list.
                        before += row.copy(id = id, action = MailActions.MARKED_UNREAD)
                    }
                    count += items.size
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
        }
        if (!failed) {
            // Their entries still listed as unread had been read, archived or deleted in the email app already.
            val left = dao.leftUnread().filter { it.fromAddress.equals(sender, ignoreCase = true) }
            if (left.isNotEmpty()) dao.markReadLogs(left.map { it.id })
            left.forEach { notifier.cancel(it.id) }
        }
        clearAppAlerts()
        Quieted(count, failed, before)
    }

    /** The sender matters again: their emails go back to being sorted like any other. */
    fun unquietSender(address: String) = prefs.setQuietSender(address, false)

    /** You said this sender is important: every email from them notifies you, whatever its kind. */
    fun importantSender(address: String, on: Boolean) {
        prefs.setImportantSender(address, on)
        if (on) prefs.setQuietSender(address, false)
    }

    suspend fun archive(logId: Long) = act(logId) { s, log ->
        s.archive(log.uid).also { if (it) dao.setLogAction(log.id, MailActions.ARCHIVED) }
    }

    /** Puts an archived email back in the inbox, or a binned one back out of the bin. */
    suspend fun undo(logId: Long) = act(logId) { s, log ->
        val id = log.messageId ?: return@act false
        val ok = when (log.action) {
            MailActions.ARCHIVED -> s.unarchive(id)
            MailActions.SPAM_DELETED -> s.unbin(id)
            else -> false
        }
        if (ok) dao.markUndone(log.id)
        ok
    }

    private suspend fun act(logId: Long, block: suspend (MailSession, MailLogEntity) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val log = dao.log(logId) ?: return@withContext false
            val account = dao.account(log.accountId) ?: return@withContext false
            val password = prefs.mailPassword(account.address)
            try {
                client.session(mail.server(account), account.address, password, account.provider).use { block(it, log) }
            } catch (e: Exception) {
                false
            }
        }

    companion object {
        /** Spam (and a catch-up of the inbox) every 3 hours. */
        private const val WORK_NAME = "mail-agent"
        /** The inbox every 15 minutes, for emails that arrive without a notification. */
        private const val WORK_INBOX = "mail-inbox"
        const val KIND = "kind"
        const val KIND_INBOX = "inbox"
        /** Refused sign-ins in a row, over at least this long, before the agent warns about an account. */
        private const val WARN_AFTER_REFUSALS = 3
        private const val WARN_AFTER_MS = 30 * 60_000L
        private const val MAX_PER_CHECK = 25
        /** How long the email app may be ahead of the mail server before you're told from its notification. */
        private const val ALERT_GRACE_MS = 15_000L
        /** How far back a taken-over notification looks for its email in the list. */
        private const val TAKEN_MATCH_WINDOW_MS = 24 * 60 * 60_000L

        /** Up to this many lots of new emails in one check, so a busy inbox or spam folder keeps up. */
        private const val MAX_BATCHES = 4
        private const val MAX_SPAM_BATCHES = 2

        /** An inbox email the AI couldn't decide on is asked about again for this long (it stays listed after). */
        private const val UNDECIDED_RETRY_MS = 24 * 60 * 60_000L

        /** Spam the AI still can't judge after this long is left in spam with a "might not be spam" notification. */
        private const val GIVE_UP_MS = 6 * 60 * 60_000L

        /** How the agent's notes and lines start, so it can tell them apart later. */
        private const val AI_TROUBLE = "The AI couldn't check some emails"
        private const val NOT_CHECKED = "Not checked yet"
        private const val NOT_MOVED_TO_BIN = "Spam, not moved to the bin yet"
        private const val NOT_MOVED_TO_INBOX = "Not moved to your inbox yet"
        private const val GONE_FROM_SPAM = "No longer in spam: moved or deleted in your email app."

        /** Matching the list with the email app: how often in the background, and how far back. */
        private const val MATCH_EVERY_MS = 5 * 60_000L
        private const val MATCH_DAYS = 30L

        /** Opening the app checks at most this often. */
        private const val OPEN_CHECK_EVERY_MS = 60_000L
        private const val MAX_SPAM_PER_CHECK = 40
        private const val TIDY_CHUNK = 40
        const val QUIET_SUMMARY = "You said this sender isn't important, so it was marked read."

        fun schedule(context: Context) {
            val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val spam = PeriodicWorkRequestBuilder<MailWorker>(3, TimeUnit.HOURS).setConstraints(online).build()
            // 15 minutes is the shortest Android allows for repeating work.
            val inbox = PeriodicWorkRequestBuilder<MailWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .setInputData(androidx.work.workDataOf(KIND to KIND_INBOX))
                .build()
            val work = WorkManager.getInstance(context)
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, spam)
            work.enqueueUniquePeriodicWork(WORK_INBOX, ExistingPeriodicWorkPolicy.KEEP, inbox)
        }
    }
}

class MailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BrokeApp).container
        // Inbox and spam both, every time: spam has no notification to say it arrived.
        container.inboxAgent.checkAll()
        return Result.success()
    }
}
