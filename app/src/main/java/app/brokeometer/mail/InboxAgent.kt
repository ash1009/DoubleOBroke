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
 * The email agent. When Gmail or Yahoo shows a new email it reads it, asks the AI which category it
 * is, then either notifies (switch on) or marks it read and archives it (switch off). Every few hours
 * it also reads the spam folder: real spam goes to the bin, anything that might matter is flagged.
 * Every step is logged so it can be undone from the Email page.
 */
class InboxAgent(
    private val context: Context,
    private val dao: MailDao,
    private val prefs: Prefs,
    private val mail: MailAgent,
    private val notifier: MailNotifier,
    private val scope: CoroutineScope,
    private val repository: app.brokeometer.data.Repository,
    private val client: MailClient = MailClient(),
) {
    private val lock = Mutex()
    @Volatile private var again = false

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

    /**
     * Called when an email app posts a notification. The app can hold any kind of account (Gmail
     * often shows Yahoo accounts too), so the account comes from the notification itself. Several
     * notifications in a row lead to one check, plus one more if new ones arrived while it ran.
     */
    fun onMailAppNotification(packageName: String, accountHint: String?, key: String) {
        if (!prefs.mailAgentOn.value) return
        scope.launch {
            if (lock.isLocked) {
                again = true
                return@launch
            }
            lock.withLock {
                do {
                    again = false
                    val all = dao.accounts()
                    val hinted = accountHint?.let { hint -> all.filter { hint.contains(it.address, ignoreCase = true) } }.orEmpty()
                    val accounts = when {
                        hinted.isNotEmpty() -> hinted
                        // An address the app has not been given: nothing to check.
                        accountHint != null && accountHint.contains('@') -> emptyList()
                        packageName == app.brokeometer.capture.MailAppAlerts.YAHOO ->
                            all.filter { it.provider == MailProviders.YAHOO }.ifEmpty { all }
                        else -> all
                    }
                    // An email a check already dealt with may only now get its notification.
                    clearAppAlerts()
                    if (accounts.isNotEmpty() && app.brokeometer.capture.MailAppAlerts.isActive(key)) {
                        accounts.forEach { inbox(it) }
                        clearAppAlerts()
                        if (app.brokeometer.capture.MailAppAlerts.isActive(key)) {
                            // The email app can be a moment ahead of the mail server.
                            delay(15_000)
                            accounts.forEach { inbox(it) }
                            clearAppAlerts()
                        }
                    }
                } while (again)
            }
        }
    }

    /** The regular checks: the inbox every 15 minutes, spam every 3 hours. */
    suspend fun checkAll(inbox: Boolean = true, spam: Boolean = true) {
        if (!prefs.mailAgentOn.value) return
        lock.withLock {
            for (account in dao.accounts()) {
                if (inbox) inbox(account)
                if (spam) spam(account)
            }
            clearAppAlerts()
        }
    }

    /**
     * Clears the email apps' notifications for emails the agent dealt with in the last few hours,
     * keeping any for emails it could not decide on.
     */
    private suspend fun clearAppAlerts() {
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

    private suspend fun inbox(account: MailAccountEntity): List<MailItem> = withContext(Dispatchers.IO) {
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
                val (items, next) = s.newSince(MailFolders.INBOX, cursor?.uidValidity, cursor?.lastUid, MAX_PER_CHECK)
                    ?: return@withContext emptyList()
                if (cursor == null || cursor.uidValidity != next.first) {
                    // First look at this inbox: start from now.
                    dao.saveCursor(MailCursorEntity(account.id, MailFolders.INBOX, next.first, next.second))
                    return@withContext emptyList()
                }
                var lastDone = cursor.lastUid
                for (item in items.sortedBy { it.uid }) {
                    val before = item.messageId?.let { dao.handledCount(it) } ?: 0
                    if (before > 0) {
                        // Put back in the inbox with Undo, or taken out of spam by you: leave it.
                        lastDone = item.uid
                        continue
                    }
                    val waiting = item.messageId?.let { dao.undecided(it) }
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
                    if (item.seen) {
                        save(entry(null, MailActions.READ_ELSEWHERE, "You had already read it, so it was left as it was."))
                        lastDone = item.uid
                        continue
                    }
                    val code = MailCodes.find(item.subject, item.text)
                    if (code != null) {
                        // A code (OTP) always notifies, at once and with no AI, whatever its kind.
                        val usual = dao.sender(account.id, item.fromAddress)?.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
                        val row = entry(usual, MailActions.NOTIFIED, "Has a code, so you were told straight away.")
                        val id = save(row)
                        notifier.important(row.copy(id = id), account, code)
                        handled += item
                        lastDone = item.uid
                        continue
                    }
                    if (item.fromAddress.isNotEmpty() && item.fromAddress.lowercase() in prefs.quietSenders.value) {
                        // A sender you said isn't important: no AI, just marked read.
                        runCatching { s.markRead(MailFolders.INBOX, item.uid) }
                        save(entry(null, MailActions.MARKED_READ, QUIET_SUMMARY))
                        handled += item
                        lastDone = item.uid
                        continue
                    }
                    // A sender you said is important always reaches you.
                    val vip = item.fromAddress.isNotEmpty() && item.fromAddress.lowercase() in prefs.importantSenders.value
                    when (val decision = decide(item, categories)) {
                        is Decision.Undecided -> {
                            if (vip) {
                                val row = entry(null, MailActions.NOTIFIED, "${item.subject.take(80)}. You said this sender is important.")
                                val id = save(row)
                                notifier.important(row.copy(id = id), account)
                                handled += item
                                lastDone = item.uid
                                continue
                            }
                            // Left in the inbox with its notification, and tried again on the next check.
                            if (waiting == null) save(entry(null, MailActions.UNDECIDED, decision.why))
                            break
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
                            } else {
                                val archived = runCatching { s.archive(item.uid) }.getOrDefault(false)
                                if (!archived) runCatching { s.markRead(MailFolders.INBOX, item.uid) }
                                save(entry(category, MailActions.ARCHIVED, decision.summary))
                            }
                            learn(account, item, category)
                            handled += item
                        }
                    }
                    lastDone = item.uid
                }
                if (items.isEmpty()) lastDone = maxOf(lastDone, next.second)
                dao.saveCursor(MailCursorEntity(account.id, MailFolders.INBOX, next.first, lastDone))
            }
        } catch (e: MailClient.LoginRefused) {
            loginTrouble(account.address)
            return@withContext handled
        } catch (e: MailClient.Busy) {
            // Another check had the mailbox: the next one picks these up.
            return@withContext handled
        } catch (e: MailClient.MailError) {
            mail.note("Agent could not check ${account.address}: ${e.message}")
        } catch (e: Exception) {
            mail.note("Agent problem with ${account.address}: ${e.javaClass.simpleName}")
        }
        dao.trimLog()
        handled
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

    private suspend fun spam(account: MailAccountEntity) = withContext(Dispatchers.IO) {
        val password = prefs.mailPassword(account.address)
        if (password.isEmpty()) return@withContext
        try {
            client.session(mail.server(account), account.address, password, account.provider, background = true).use { s ->
                val cursor = dao.cursor(account.id, MailFolders.SPAM)
                // The spam folder is read from the oldest email the first time, so what is there now gets checked too.
                val (items, next) = s.newSince(
                    MailFolders.SPAM, cursor?.uidValidity, cursor?.lastUid, MAX_SPAM_PER_CHECK, startAtOldest = true,
                ) ?: return@withContext
                val sameFolder = cursor != null && cursor.uidValidity == next.first
                var lastDone = if (sameFolder) cursor!!.lastUid else 0L
                for (item in items.sortedBy { it.uid }) {
                    val sender = item.fromAddress.lowercase()
                    // Senders you have already judged skip the AI.
                    if (sender.isNotEmpty() && sender in prefs.notSpamSenders.value) {
                        if (!runCatching { s.spamToInbox(item.messageId, item.uid) }.getOrDefault(false)) break
                        dao.insertLog(
                            spamEntry(account, item, MailActions.MOVED_FROM_SPAM, "You said this sender isn't spam, so it went to your inbox.")
                        )
                        lastDone = item.uid
                        continue
                    }
                    if (sender.isNotEmpty() && sender in prefs.spamSenders.value) {
                        if (!runCatching { s.spamToBin(item.messageId, item.uid) }.getOrDefault(false)) break
                        dao.insertLog(spamEntry(account, item, MailActions.SPAM_DELETED, "You marked this sender as spam before."))
                        lastDone = item.uid
                        continue
                    }
                    val verdict = when (val r = mail.ask(MailPrompts.checkSpam(item), maxTokens = 200)) {
                        is Brain.Answer.Text -> MailPrompts.parseSpam(r.text)
                        is Brain.Answer.Failed -> null
                    } ?: break
                    val log = MailLogEntity(
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
                        action = if (verdict.spam) MailActions.SPAM_DELETED else MailActions.SPAM_FLAGGED,
                        summary = verdict.why,
                    )
                    if (verdict.spam) {
                        if (!runCatching { s.spamToBin(item.messageId, item.uid) }.getOrDefault(false)) break
                        dao.insertLog(log)
                    } else {
                        val id = dao.insertLog(log)
                        notifier.maybeNotSpam(log.copy(id = id), account)
                    }
                    lastDone = item.uid
                }
                if (items.isEmpty()) lastDone = maxOf(lastDone, next.second)
                dao.saveCursor(MailCursorEntity(account.id, MailFolders.SPAM, next.first, lastDone))
            }
        } catch (e: MailClient.LoginRefused) {
            loginTrouble(account.address)
        } catch (e: MailClient.Busy) {
            // Another check had the mailbox: the next one looks again.
        } catch (e: MailClient.MailError) {
            mail.note("Spam check could not reach ${account.address}: ${e.message}")
        } catch (e: Exception) {
            mail.note("Spam check problem with ${account.address}: ${e.javaClass.simpleName}")
        }
    }

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
        if (decision != null) return Decision.Made(categories[decision.category], decision.summary.ifBlank { item.subject }, decision.personal)
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
                                !item.seen && (item.messageId == null || dao.handledCount(item.messageId) == 0)
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

    private fun spamEntry(account: MailAccountEntity, item: MailItem, action: String, summary: String) = MailLogEntity(
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
        if (inputData.getString(InboxAgent.KIND) == InboxAgent.KIND_INBOX) {
            container.inboxAgent.checkAll(inbox = true, spam = false)
        } else {
            container.inboxAgent.checkAll()
        }
        return Result.success()
    }
}
