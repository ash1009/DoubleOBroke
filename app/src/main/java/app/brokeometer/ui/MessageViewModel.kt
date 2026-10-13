package app.brokeometer.ui

import android.app.Application
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.brokeometer.BrokeApp
import app.brokeometer.mail.FullEmail
import app.brokeometer.mail.MailAccountEntity
import app.brokeometer.mail.MailLogEntity
import app.brokeometer.mail.MailReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The emails shown on Activity, in order, when one was opened: the ones you can swipe through. */
object EmailBrowse {
    @Volatile var ids: List<Long> = emptyList()
}

/** Emails from the Activity list, opened in full, one page each. */
class MessageViewModel(application: Application, state: SavedStateHandle) : AndroidViewModel(application) {

    private val container = (application as BrokeApp).container
    private val dao = container.mailDatabase.dao()
    private val reader = container.mailReader

    /** The email opened first. */
    val id: Long = state.get<Long>("id") ?: -1L

    /** The emails you can swipe through, in the Activity list's order; just this one if that isn't known. */
    val ids: List<Long> = state.get<LongArray>("ids")?.toList()
        ?: (EmailBrowse.ids.takeIf { id in it } ?: listOf(id)).also { state["ids"] = it.toLongArray() }

    fun entry(id: Long): Flow<MailLogEntity?> = dao.observeLogEntry(id)

    suspend fun log(id: Long): MailLogEntity? = dao.log(id)

    val accounts: StateFlow<List<MailAccountEntity>> =
        dao.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Each email's state: null while it is being fetched. */
    private val emails = HashMap<Long, MutableStateFlow<MailReader.Opened?>>()

    fun email(id: Long): StateFlow<MailReader.Opened?> =
        emails.getOrPut(id) { MutableStateFlow<MailReader.Opened?>(null).also { fetch(id, it) } }

    fun load(id: Long) {
        emails[id]?.let { fetch(id, it) }
    }

    private fun fetch(id: Long, into: MutableStateFlow<MailReader.Opened?>) = viewModelScope.launch {
        into.value = null
        val log = dao.log(id)
        into.value = if (log == null) MailReader.Opened.Problem("This email isn't on the list any more.") else reader.open(log)
    }

    /**
     * Gets the emails two away ready too, so swiping on is quick. (The ones either side are already
     * opening, as they sit just off the screen.)
     */
    fun prefetchAround(index: Int) {
        val further = listOfNotNull(ids.getOrNull(index + 2), ids.getOrNull(index - 2))
        if (further.isNotEmpty()) reader.prefetch(further)
    }

    /** Fetches one attached file into the app's cache, to open with another app. Null if it couldn't. */
    suspend fun attachment(id: Long, index: Int): MailReader.Saved? {
        val log = dao.log(id) ?: return null
        return reader.attachment(log, index, java.io.File(getApplication<Application>().cacheDir, "attachments"))
    }
}

/** Writing an email: a new one, or a reply to one from the Activity list. */
class ComposeViewModel(application: Application, state: SavedStateHandle) : AndroidViewModel(application) {

    private val container = (application as BrokeApp).container
    private val dao = container.mailDatabase.dao()
    private val reader = container.mailReader

    /** The Activity entry being answered, or -1 for a new email. */
    val replyId: Long = state.get<Long>("reply") ?: -1L

    val accounts: StateFlow<List<MailAccountEntity>> =
        dao.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    sealed interface Original {
        data object None : Original
        data object Loading : Original
        data class Ready(val log: MailLogEntity, val email: FullEmail) : Original
        data class Problem(val message: String) : Original
    }

    private val _original = MutableStateFlow<Original>(if (replyId < 0) Original.None else Original.Loading)
    val original: StateFlow<Original> = _original

    sealed interface Sending {
        data object Idle : Sending
        data object Busy : Sending
        data object Sent : Sending
        data class Problem(val message: String) : Sending
    }

    private val _sending = MutableStateFlow<Sending>(Sending.Idle)
    val sending: StateFlow<Sending> = _sending

    // Files to attach

    /** A file picked to attach; it is read when the email is sent. */
    data class Picked(val uri: Uri, val name: String, val size: Long, val type: String)

    private val _attached = MutableStateFlow<List<Picked>>(emptyList())
    val attached: StateFlow<List<Picked>> = _attached

    fun attach(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val added = uris.mapNotNull { uri ->
            runCatching {
                var name = uri.lastPathSegment ?: "file"
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                Picked(uri, name, size, resolver.getType(uri) ?: "application/octet-stream")
            }.getOrNull()
        }
        val all = (_attached.value + added).distinctBy { it.uri }
        if (all.sumOf { it.size.coerceAtLeast(0) } > MAX_ATTACH) {
            _sending.value = Sending.Problem("That's too big to email. Emails can carry about 18 MB of files in all.")
            return
        }
        _attached.value = all
    }

    fun detach(picked: Picked) {
        _attached.value = _attached.value - picked
    }

    // Suggested addresses: people who have emailed you, and your phone's contacts if you allow it.

    data class Contact(val name: String, val address: String)

    private var contacts: List<Contact> = emptyList()

    fun loadContacts(withPhone: Boolean) = viewModelScope.launch {
        contacts = withContext(Dispatchers.IO) {
            val phone = if (withPhone) runCatching { phoneContacts() }.getOrDefault(emptyList()) else emptyList()
            val mail = dao.senders().map { Contact(it.name, it.address) }
            (phone + mail).filter { it.address.contains('@') }.distinctBy { it.address.lowercase() }
        }
    }

    private fun phoneContacts(): List<Contact> {
        val out = ArrayList<Contact>()
        getApplication<Application>().contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf("display_name", ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) out += Contact(c.getString(0).orEmpty(), c.getString(1).orEmpty().trim())
        }
        return out
    }

    /** Up to six people whose name or address starts with (or has) what is being typed. */
    fun suggest(typed: String): List<Contact> {
        val q = typed.trim().lowercase()
        if (q.length < 2) return emptyList()
        return contacts.mapNotNull { c ->
            val n = c.name.lowercase()
            val a = c.address.lowercase()
            val rank = when {
                n.startsWith(q) -> 0
                n.split(' ', '.', '-').any { it.startsWith(q) } -> 1
                a.startsWith(q) -> 2
                n.contains(q) || a.contains(q) -> 3
                else -> null
            }
            rank?.let { it to c }
        }.sortedBy { it.first }.take(6).map { it.second }
    }

    init {
        if (replyId >= 0) loadOriginal()
        if (app.brokeometer.BuildConfig.DEBUG) DebugHooks.takeAttach()?.let { attach(listOf(it)) }
    }

    fun loadOriginal() = viewModelScope.launch {
        _original.value = Original.Loading
        val log = dao.log(replyId)
        _original.value = when {
            log == null -> Original.Problem("The email you're answering isn't on the list any more.")
            else -> when (val r = reader.open(log)) {
                is MailReader.Opened.Email -> Original.Ready(log, r.email)
                is MailReader.Opened.Problem -> Original.Problem(r.message)
            }
        }
    }

    fun send(accountId: Long?, to: String, cc: String, subject: String, body: String) {
        if (_sending.value == Sending.Busy) return
        if (accountId == null) {
            _sending.value = Sending.Problem("Pick the account to send from.")
            return
        }
        viewModelScope.launch {
            _sending.value = Sending.Busy
            val replyTo = (original.value as? Original.Ready)?.email
            val resolver = getApplication<Application>().contentResolver
            val files = ArrayList<MailReader.Attachment>()
            for (p in _attached.value) {
                val bytes = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(p.uri)?.use { it.readBytes() } }.getOrNull() }
                if (bytes == null) {
                    _sending.value = Sending.Problem("Couldn't read ${p.name}. Take it off and attach it again.")
                    return@launch
                }
                files += MailReader.Attachment(p.name, p.type, bytes)
            }
            if (files.sumOf { it.bytes.size.toLong() } > MAX_ATTACH) {
                _sending.value = Sending.Problem("That's too big to email. Emails can carry about 18 MB of files in all.")
                return@launch
            }
            val problem = reader.send(MailReader.Draft(accountId, to, cc, subject, body, replyTo, files))
            _sending.value = if (problem == null) Sending.Sent else Sending.Problem(problem)
        }
    }

    fun dismissProblem() {
        if (_sending.value is Sending.Problem) _sending.value = Sending.Idle
    }

    companion object {
        /** Gmail takes 25 MB, and files grow by a third on the way, so about 18 MB of files fits. */
        private const val MAX_ATTACH = 18_000_000L
    }
}
