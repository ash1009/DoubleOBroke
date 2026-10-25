package app.brokeometer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.brokeometer.BrokeApp
import app.brokeometer.notes.KeepImport
import app.brokeometer.notes.NoteEntity
import app.brokeometer.notes.NoteFiles
import app.brokeometer.notes.NoteRules
import app.brokeometer.notes.NoteText
import app.brokeometer.notes.isBlank
import app.brokeometer.notes.itemList
import app.brokeometer.notes.recordings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Notes: the list (searched and arranged), the Bin, and saving, pinning and deleting. */
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BrokeApp
    private val db = app.container.notesDatabase
    private val dao = db.dao()

    /** Saves, closes, moves and deletes happen one at a time, in the order asked. */
    private val lock = Mutex()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** Notes with something in them and not in the Bin, matching the search, pinned first then as arranged. */
    val notes: StateFlow<List<NoteEntity>> = combine(dao.observeAll(), _query) { all, q ->
        all.filter { !it.isBlank && NoteText.matches(q, it.title, it.body, it.itemList) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every note with something in it, whatever is searched: for Home. */
    val allNotes: StateFlow<List<NoteEntity>> = dao.observeAll().map { all -> all.filter { !it.isBlank } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The Bin, newest first. */
    val bin: StateFlow<List<NoteEntity>> = dao.observeBin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    /** What the last Keep import did, to show on Setup. */
    private val _imported = MutableStateFlow<String?>(null)
    val imported: StateFlow<String?> = _imported

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            lock.withLock {
                // Notes left empty (the app closed before they were written in) are cleared away.
                dao.all().filter { it.deletedAt == null && it.isBlank && now - it.updatedAt > 60_000 }.forEach { dao.delete(it.id) }
                // Notes in the Bin longer than a week go for good.
                dao.binnedBefore(now - NoteRules.BIN_DAYS * 86_400_000L).forEach { dao.delete(it.id) }
            }
            sweep()
        }
    }

    fun search(text: String) {
        _query.value = text
    }

    /** A new, empty note (or checklist) at the top; its id, for the editor. */
    suspend fun create(checklist: Boolean): Long = lock.withLock {
        val now = System.currentTimeMillis()
        dao.insert(NoteEntity(checklist = checklist, createdAt = now, updatedAt = now, position = (dao.firstPosition() ?: 0L) - 1))
    }

    suspend fun load(id: Long): NoteEntity? = dao.get(id)

    /**
     * Saves a note from the editor. Saves go one after another, in order, on the app's own scope, so
     * leaving the editor never loses what was just written.
     */
    fun save(note: NoteEntity) {
        app.scope.launch { lock.withLock { write(note) } }
    }

    /**
     * Leaving the editor: what's there is saved, and a note with nothing in it isn't kept. When its words
     * changed since it was opened ([start]), Tasks looks for dates in it again.
     */
    fun close(note: NoteEntity, start: NoteEntity? = null) {
        app.scope.launch {
            lock.withLock {
                val old = dao.get(note.id) ?: return@withLock
                if (old.deletedAt == null && note.isBlank) dao.delete(note.id) else write(note)
            }
            val changed = start == null || start.title != note.title || start.body != note.body || start.items != note.items
            if (changed && !note.isBlank && dao.get(note.id)?.deletedAt == null) {
                val text = if (note.checklist) note.itemList.filter { !it.checked }.joinToString("\n") { it.text } else NoteText.plain(note.body)
                tasks.scanNote(note.id, note.title, text)
            }
        }
    }

    private val tasks get() = app.container.tasks

    /**
     * The editor's note over the saved one. Its place and whether it's in the Bin are kept as they are.
     * A changed "Remind me" date (or the title of a note that has one) goes to Tasks.
     */
    private suspend fun write(note: NoteEntity) {
        val old = dao.get(note.id) ?: return
        if (old.deletedAt != null) return
        val merged = note.copy(position = old.position, deletedAt = null)
        if (old.copy(updatedAt = 0) != merged.copy(updatedAt = 0)) dao.update(merged.copy(updatedAt = System.currentTimeMillis()))
        val reminderMoved = old.remindDay != merged.remindDay || old.remindTime != merged.remindTime
        if (reminderMoved || (merged.remindDay != null && old.title != merged.title)) {
            tasks.noteReminder(merged.id, reminderTitle(merged), NoteRules.dayOf(merged.remindDay), merged.remindTime)
        }
    }

    /** A note's reminder in Tasks: its title, or the start of its words. */
    private fun reminderTitle(n: NoteEntity): String =
        n.title.ifBlank { (if (n.checklist) n.itemList.firstOrNull()?.text.orEmpty() else NoteText.plain(n.body)).lineSequence().firstOrNull().orEmpty().take(60) }

    /** A note just put in the Bin, and when, for the Notes page to offer Undo. */
    data class Binned(val note: NoteEntity, val at: Long)

    private val _binned = MutableStateFlow<Binned?>(null)
    val binned: StateFlow<Binned?> = _binned

    /** Puts a note in the Bin. One with nothing in it just goes. */
    fun delete(note: NoteEntity) {
        app.scope.launch {
            if (note.isBlank) {
                lock.withLock { dao.delete(note.id) }
                return@launch
            }
            lock.withLock { dao.setDeleted(note.id, System.currentTimeMillis()) }
            tasks.noteGone(note.id)
            _binned.value = Binned(note, System.currentTimeMillis())
        }
    }

    fun binnedShown() {
        _binned.value = null
    }

    /** Takes a note out of the Bin, back where it was. */
    fun restore(id: Long) {
        app.scope.launch {
            val n = lock.withLock {
                dao.setDeleted(id, null)
                dao.get(id)
            } ?: return@launch
            // Its reminder comes back with it.
            if (n.remindDay != null) tasks.noteReminder(n.id, reminderTitle(n), NoteRules.dayOf(n.remindDay), n.remindTime)
        }
    }

    fun deleteForever(id: Long) {
        app.scope.launch {
            lock.withLock { dao.delete(id) }
            sweep()
        }
    }

    fun emptyBin() {
        app.scope.launch {
            lock.withLock { dao.binnedBefore(Long.MAX_VALUE).forEach { dao.delete(it.id) } }
            sweep()
        }
    }

    fun setPinned(note: NoteEntity, pinned: Boolean) {
        app.scope.launch { lock.withLock { dao.setPinned(note.id, pinned) } }
    }

    /** A note's colour and background (a pattern, or a photo) from the Notes page. */
    fun setLook(note: NoteEntity, color: String, background: String, image: String?) {
        app.scope.launch {
            lock.withLock {
                val old = dao.get(note.id) ?: return@withLock
                dao.update(old.copy(color = color, background = background, image = image))
            }
        }
    }

    /** A copy of a note, at the top. Its photo and recordings are shared, not copied. */
    fun copy(note: NoteEntity) {
        app.scope.launch {
            lock.withLock {
                val old = dao.get(note.id) ?: return@withLock
                val now = System.currentTimeMillis()
                dao.insert(
                    old.copy(
                        id = 0, pinned = false, keepKey = null, createdAt = now, updatedAt = now, position = (dao.firstPosition() ?: 0L) - 1, deletedAt = null,
                        remindDay = null, remindTime = null,
                    ),
                )
            }
        }
    }

    /** The notes in the order they were dragged into. Pinned and other notes keep to their own groups. */
    fun arrange(ids: List<Long>) {
        app.scope.launch {
            lock.withLock {
                db.withTransaction { ids.forEachIndexed { i, id -> dao.setPosition(id, i.toLong()) } }
            }
        }
    }

    /** A photo picked for a note's background, kept on the phone. Its path, or null. */
    suspend fun savePhoto(uri: Uri): String? = withContext(Dispatchers.IO) { NoteFiles.savePhoto(getApplication(), uri) }

    /** A voice recording kept with a note. Its path, or null. */
    suspend fun saveRecording(uri: Uri): String? = withContext(Dispatchers.IO) { NoteFiles.saveRecording(getApplication(), uri) }

    /** Files no note uses any more go. */
    private suspend fun sweep() {
        val used = dao.all().flatMap { n -> listOfNotNull(n.image) + n.recordings }.toSet()
        withContext(Dispatchers.IO) { NoteFiles.sweep(getApplication(), used) }
    }

    fun importKeep(uri: Uri) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            _imported.value = null
            val result = runCatching { KeepImport.import(getApplication(), uri, dao) }.getOrNull()
            _importing.value = false
            _imported.value = when {
                result == null -> "Couldn't read that file. Pick the .zip from Google Takeout."
                result.notKeep -> "No Keep notes in that file. In Takeout, pick Keep and export as .zip."
                else -> buildString {
                    append("${result.added} ${if (result.added == 1) "note" else "notes"} brought in")
                    if (result.already > 0) append(", ${result.already} were here already")
                    if (result.archived > 0) append(", ${result.archived} archived left out")
                    append(".")
                }
            }
        }
    }
}
