package app.brokeometer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brokeometer.BrokeApp
import app.brokeometer.mail.MailAccountEntity
import app.brokeometer.mail.MailCategoryEntity
import app.brokeometer.mail.MailLogEntity
import app.brokeometer.mail.MailProgress
import app.brokeometer.mail.MailSenderEntity
import app.brokeometer.mail.MailStarredEntity
import app.brokeometer.mail.StarredMail
import app.brokeometer.mail.UnsubscribeOutcome
import app.brokeometer.mail.MailActions
import app.brokeometer.ui.screens.ActivityFilters
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Email page: accounts, categories with their switches, and mailing lists. */
class MailViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as BrokeApp).container
    private val agent = container.mail
    private val dao = container.mailDatabase.dao()
    private val prefs = container.prefs

    val accounts: StateFlow<List<MailAccountEntity>> =
        dao.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Activity's filters, the same wherever the page is shown. */
    val activityFilters = ActivityFilters()

    /** Set from Home's "and N more": Activity opens showing only unread emails. */
    private val _showUnread = MutableStateFlow(false)
    val showUnread: StateFlow<Boolean> = _showUnread

    fun showUnread() {
        _showUnread.value = true
    }

    fun showUnreadDone() {
        _showUnread.value = false
    }
    val categories: StateFlow<List<MailCategoryEntity>> =
        dao.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val senders: StateFlow<List<MailSenderEntity>> =
        dao.observeSenders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress: StateFlow<MailProgress> = agent.progress
    val claudeKey: StateFlow<String> = prefs.claudeKey
    val geminiKey: StateFlow<String> = prefs.geminiKey

    /** Adding an account: null while idle, "" while checking, otherwise the problem. */
    private val _addState = MutableStateFlow<AddState>(AddState.Idle)
    val addState: StateFlow<AddState> = _addState

    sealed interface AddState {
        data object Idle : AddState
        data object Checking : AddState
        data object Added : AddState
        data class Problem(val message: String) : AddState
    }

    private val _unsubscribe = MutableStateFlow<Pair<MailSenderEntity, UnsubscribeOutcome>?>(null)
    val unsubscribeResult: StateFlow<Pair<MailSenderEntity, UnsubscribeOutcome>?> = _unsubscribe

    private val _keyStatus = MutableStateFlow<String?>(null)
    val keyStatus: StateFlow<String?> = _keyStatus

    fun addAccount(address: String, provider: String, password: String) = viewModelScope.launch {
        _addState.value = AddState.Checking
        val problem = agent.addAccount(address, provider, password)
        _addState.value = if (problem == null) AddState.Added else AddState.Problem(problem)
    }

    fun resetAdd() {
        _addState.value = AddState.Idle
    }

    fun removeAccount(account: MailAccountEntity) = viewModelScope.launch { agent.removeAccount(account) }

    fun readAndSort() = agent.readAndSort(fresh = false)

    /** Sorts the senders still without a category, without reading the mailboxes again. */
    fun sortRest() = agent.sortOnly(fresh = false)

    fun makeCategoriesAgain() = agent.sortOnly(fresh = true)

    fun stop() = agent.stop()

    fun tidyUp() = agent.tidyUp()

    fun dismissProgress() = agent.report(MailProgress())

    fun setImportant(category: MailCategoryEntity, on: Boolean) = viewModelScope.launch { agent.setImportant(category, on) }

    fun moveSender(address: String, categoryId: Long) = viewModelScope.launch { agent.moveSender(address, categoryId) }

    fun unsubscribe(sender: MailSenderEntity) = viewModelScope.launch {
        _unsubscribe.value = sender to agent.unsubscribe(sender)
    }

    /** After the owner left the list on its web page or by email. */
    fun markUnsubscribed(sender: MailSenderEntity) = viewModelScope.launch { agent.markUnsubscribed(sender.address) }

    fun dismissUnsubscribe() {
        _unsubscribe.value = null
    }

    fun setClaudeKey(value: String) {
        prefs.setClaudeKey(value)
        _keyStatus.value = null
    }

    fun testClaude() = viewModelScope.launch {
        _keyStatus.value = "Asking Claude..."
        val reply = container.claude.ask("Answer in one word.", "Say hello.", smart = false, maxTokens = 10)
        _keyStatus.value = when (reply) {
            is app.brokeometer.data.ClaudeClient.Reply.Text -> "Works. Using ${reply.model}."
            is app.brokeometer.data.ClaudeClient.Reply.Failed -> reply.message
        }
    }

    fun brainName(): String = agent.brainName()

    // The agent

    private val inbox = container.inboxAgent
    val agentOn: StateFlow<Boolean> = prefs.mailAgentOn
    val problem: StateFlow<String?> = agent.problem
    val log: StateFlow<List<MailLogEntity>> =
        dao.observeLog().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val quietSenders: StateFlow<Set<String>> = prefs.quietSenders

    /** A short note at the bottom of the screen after something is done, with an Undo when it can be undone. */
    data class Done(val message: String, val undo: (() -> Unit)? = null)

    private val _done = MutableSharedFlow<Done>(extraBufferCapacity = 8)
    val done: SharedFlow<Done> = _done

    private val _working = MutableStateFlow<Set<Long>>(emptySet())
    /** Activity rows being changed, each with its own spinner. */
    val working: StateFlow<Set<Long>> = _working

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    private val _refreshing = MutableStateFlow(false)
    /** Pull to refresh is checking. */
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _markingAll = MutableStateFlow(false)
    val markingAll: StateFlow<Boolean> = _markingAll

    private suspend fun <T> busy(ids: Collection<Long>, block: suspend () -> T): T {
        val set = ids.toSet()
        _working.update { it + set }
        try {
            return block()
        } finally {
            _working.update { it - set }
        }
    }

    fun setAgentOn(on: Boolean) = viewModelScope.launch { inbox.setOn(on) }

    fun checkNow() {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            agent.note(null)
            val before = dao.logCount()
            try {
                inbox.checkAll(matchNow = true)
            } finally {
                _checking.value = false
            }
            val added = dao.logCount() - before
            _done.tryEmit(Done(if (added <= 0) "Checked. Nothing new." else "Checked: ${count(added, "new email")}."))
        }
    }

    /** Pull to refresh: the inbox of the account picked on Activity (or all of them), nothing else. */
    fun refresh(accountId: Long?) {
        if (_refreshing.value || _checking.value) return
        viewModelScope.launch {
            _refreshing.value = true
            agent.note(null)
            val before = dao.logCount()
            try {
                inbox.checkInbox(accountId)
            } finally {
                _refreshing.value = false
            }
            val added = dao.logCount() - before
            _done.tryEmit(Done(if (added <= 0) "Nothing new." else "${count(added, "new email")}."))
        }
    }

    /**
     * Changes emails on the mail server and on the list, then offers Undo, which puts them back as
     * they were. [before] and [after] hold the same entries.
     */
    private fun change(before: List<MailLogEntity>, after: List<MailLogEntity>, message: String?, all: Boolean = false) {
        if (after.isEmpty()) return
        viewModelScope.launch {
            if (all) _markingAll.value = true
            val ok = try {
                busy(after.map { it.id }) { inbox.change(after) }
            } finally {
                if (all) _markingAll.value = false
            }
            if (!ok) {
                agent.note("Couldn't reach your email, so some weren't changed. Check your connection and try again.")
                return@launch
            }
            if (message != null) {
                _done.tryEmit(Done(message) {
                    viewModelScope.launch { busy(before.map { it.id }) { inbox.change(before) } }
                })
            }
        }
    }

    fun markRead(entry: MailLogEntity) =
        change(listOf(entry), listOf(entry.copy(action = MailActions.MARKED_READ, undone = false)), "Marked read")

    fun markUnread(entry: MailLogEntity) {
        if (MailActions.archived(entry)) {
            change(listOf(entry), listOf(entry.copy(undone = true)), "Back in your inbox, unread")
        } else {
            change(listOf(entry), listOf(entry.copy(action = MailActions.MARKED_UNREAD, undone = false)), "Marked unread")
        }
    }

    /** Opening an email in the app counts as reading it. */
    fun openedRead(entry: MailLogEntity) {
        if (MailActions.leftUnread(entry)) change(listOf(entry), listOf(entry.copy(action = MailActions.MARKED_READ, undone = false)), null)
    }

    enum class Pick { READ, UNREAD, ARCHIVE, DELETE }

    /** One change for several emails picked on the Activity list. Emails it doesn't apply to are left as they are. */
    fun pickMany(entries: List<MailLogEntity>, kind: Pick) {
        val chosen = entries.filter {
            when (kind) {
                Pick.READ -> MailActions.leftUnread(it)
                Pick.UNREAD -> MailActions.canMarkUnread(it)
                Pick.ARCHIVE -> MailActions.canArchive(it)
                Pick.DELETE -> MailActions.canDelete(it)
            }
        }
        if (chosen.isEmpty()) {
            _done.tryEmit(
                Done(
                    when (kind) {
                        Pick.READ -> "They're already read."
                        Pick.UNREAD -> "They're already unread."
                        Pick.ARCHIVE -> "Nothing there to archive."
                        Pick.DELETE -> "Nothing there to delete."
                    }
                )
            )
            return
        }
        val after = chosen.map {
            when (kind) {
                Pick.READ -> it.copy(action = MailActions.MARKED_READ, undone = false)
                Pick.UNREAD -> if (MailActions.archived(it)) it.copy(undone = true) else it.copy(action = MailActions.MARKED_UNREAD, undone = false)
                Pick.ARCHIVE -> it.copy(action = MailActions.ARCHIVED, undone = false)
                Pick.DELETE -> it.copy(action = MailActions.DELETED, undone = false)
            }
        }
        val n = count(chosen.size, "email")
        val message = when (kind) {
            Pick.READ -> "Marked $n read"
            Pick.UNREAD -> "Marked $n unread"
            Pick.ARCHIVE -> "Archived $n"
            Pick.DELETE -> "Deleted $n. The bin keeps them about 30 days."
        }
        change(chosen, after, message)
    }

    fun delete(entry: MailLogEntity) = pickMany(listOf(entry), Pick.DELETE)

    /** Takes a deleted email out of the bin, back into the inbox, unread. */
    fun restore(entry: MailLogEntity) {
        if (MailActions.binned(entry)) {
            change(listOf(entry), listOf(entry.copy(action = MailActions.MARKED_UNREAD, undone = false)), "Back in your inbox")
        }
    }

    /** Marks read the unread emails shown (after any filters). */
    fun markAllRead(entries: List<MailLogEntity>) {
        val unread = entries.filter { MailActions.leftUnread(it) }
        change(unread, unread.map { it.copy(action = MailActions.MARKED_READ, undone = false) }, "Marked ${count(unread.size, "email")} read", all = true)
    }

    fun markSpam(entry: MailLogEntity) = viewModelScope.launch {
        val ok = busy(listOf(entry.id)) { inbox.markSpam(entry.id) }
        if (!ok) agent.note("Couldn't move that one to the bin. It may have been moved or deleted in the email app.")
        else _done.tryEmit(Done("Moved to the bin. Their spam goes there from now on."))
    }

    fun notSpam(entry: MailLogEntity) = viewModelScope.launch {
        val ok = busy(listOf(entry.id)) { inbox.notSpam(entry.id) }
        if (!ok) agent.note("Couldn't move that one to your inbox. It may have been moved or deleted in the email app.")
        else _done.tryEmit(Done("Moved to your inbox. This sender won't go to spam again."))
    }

    fun quietSender(entry: MailLogEntity) = viewModelScope.launch {
        val who = entry.fromName.ifBlank { entry.fromAddress }
        val r = busy(listOf(entry.id)) { inbox.quietSender(entry.fromAddress) }
        if (r.failed) agent.note("Some of $who's emails couldn't be reached. New ones will still be marked read.")
        val what = if (r.count == 0) "$who isn't important now" else "$who isn't important now: ${count(r.count, "email")} marked read"
        _done.tryEmit(Done(what) {
            inbox.unquietSender(entry.fromAddress)
            viewModelScope.launch { busy(r.before.map { it.id }) { inbox.change(r.before) } }
        })
    }

    val importantSenders: StateFlow<Set<String>> = prefs.importantSenders

    /**
     * You said this sender is important: their emails always notify you from now on. This one goes
     * back to your inbox, unread, if the agent had put it away.
     */
    fun importantSender(entry: MailLogEntity) {
        val who = entry.fromName.ifBlank { entry.fromAddress }
        inbox.importantSender(entry.fromAddress, true)
        if (MailActions.archived(entry)) change(listOf(entry), listOf(entry.copy(undone = true)), null)
        _done.tryEmit(Done("$who is important now: their emails will always reach you") { inbox.importantSender(entry.fromAddress, false) })
    }

    fun notImportantSender(entry: MailLogEntity) {
        inbox.importantSender(entry.fromAddress, false)
        _done.tryEmit(Done("${entry.fromName.ifBlank { entry.fromAddress }} is back to normal"))
    }

    fun unquietSender(entry: MailLogEntity) {
        inbox.unquietSender(entry.fromAddress)
        _done.tryEmit(Done("${entry.fromName.ifBlank { entry.fromAddress }} matters again"))
    }

    fun dismissProblem() = agent.note(null)

    // The Important page: starred emails, and in Gmail ones marked important.

    private val starredMail = container.starredMail
    val starred: StateFlow<List<MailStarredEntity>> =
        dao.observeStarred().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _starring = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Stars being changed, shown at once while the mailbox catches up ([starKey] to on or off). */
    val starring: StateFlow<Map<String, Boolean>> = _starring

    private val _syncingStars = MutableStateFlow(false)
    val syncingStars: StateFlow<Boolean> = _syncingStars

    /** Matches the Important page with the mailboxes: every few minutes on opening it, or when pulled down ([force]). */
    fun syncStars(force: Boolean) {
        if (_syncingStars.value) return
        if (!force && System.currentTimeMillis() - starredMail.syncedAt < StarredMail.SYNC_EVERY_MS) return
        viewModelScope.launch {
            _syncingStars.value = true
            val problem = try {
                starredMail.sync()
            } finally {
                _syncingStars.value = false
            }
            if (problem != null) _done.tryEmit(Done(problem))
        }
    }

    /** Whether an email is starred (or important), counting a change still on its way. */
    fun isStarred(accountId: Long, messageId: String?, rows: List<MailStarredEntity>, changing: Map<String, Boolean>): Boolean {
        val id = messageId ?: return false
        return changing[starKey(accountId, id)] ?: rows.any { it.accountId == accountId && it.messageId == id }
    }

    /** Stars or unstars an email, in Gmail or Yahoo too. */
    fun setStar(accountId: Long, messageId: String?, on: Boolean, about: StarredMail.About) {
        val id = messageId ?: return
        val key = starKey(accountId, id)
        if (key in _starring.value) return
        _starring.update { it + (key to on) }
        viewModelScope.launch {
            val ok = try {
                starredMail.setStar(accountId, id, on, about)
            } finally {
                _starring.update { it - key }
            }
            _done.tryEmit(
                if (!ok) Done("Couldn't reach your email, so the star wasn't changed. Try again.")
                else Done(if (on) "Starred" else "Unstarred") { setStar(accountId, id, !on, about) },
            )
        }
    }

    companion object {
        fun starKey(accountId: Long, messageId: String) = "$accountId|$messageId"
    }

    private fun count(n: Int, one: String): String = "${"%,d".format(n)} ${if (n == 1) one else one + "s"}"
}
