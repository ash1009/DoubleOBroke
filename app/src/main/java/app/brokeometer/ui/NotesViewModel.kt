package app.brokeometer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brokeometer.BrokeApp
import app.brokeometer.notes.KeepImport
import app.brokeometer.notes.NoteEntity
import app.brokeometer.notes.NoteText
import app.brokeometer.notes.isBlank
import app.brokeometer.notes.itemList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Notes: the list (searched), and saving, pinning and deleting. */
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BrokeApp
    private val dao = app.container.notesDatabase.dao()

    /** Saves, closes and deletes happen one at a time, in the order asked. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** Notes with something in them, matching the search, pinned first then newest. */
    val notes: StateFlow<List<NoteEntity>> = combine(dao.observeAll(), _query) { all, q ->
        all.filter { !it.isBlank && NoteText.matches(q, it.title, it.body, it.itemList) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A line to show once something is done, with an Undo when it can be undone. */
    data class Done(val message: String, val undo: (() -> Unit)? = null)

    private val _done = MutableSharedFlow<Done>(extraBufferCapacity = 4)
    val done: SharedFlow<Done> = _done

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    init {
        // Notes left empty (the app closed before they were written in) are cleared away.
        viewModelScope.launch { dao.all().filter { it.isBlank && System.currentTimeMillis() - it.updatedAt > 60_000 }.forEach { dao.delete(it.id) } }
    }

    fun search(text: String) {
        _query.value = text
    }

    /** A new, empty note (or checklist); its id, for the editor. */
    suspend fun create(checklist: Boolean): Long {
        val now = System.currentTimeMillis()
        return dao.insert(NoteEntity(checklist = checklist, createdAt = now, updatedAt = now))
    }

    suspend fun load(id: Long): NoteEntity? = dao.get(id)

    /**
     * Saves a note from the editor. Saves go one after another, in order, on the app's own scope, so
     * leaving the editor never loses what was just written.
     */
    fun save(note: NoteEntity) {
        app.scope.launch { lock.withLock { write(note) } }
    }

    /** Leaving the editor: what's there is saved, and a note with nothing in it isn't kept. */
    fun close(note: NoteEntity) {
        app.scope.launch {
            lock.withLock {
                if (note.isBlank) dao.delete(note.id) else write(note)
            }
        }
    }

    private suspend fun write(note: NoteEntity) {
        val old = dao.get(note.id) ?: return
        if (old.copy(updatedAt = 0) != note.copy(updatedAt = 0)) dao.update(note.copy(updatedAt = System.currentTimeMillis()))
    }

    private val _deleted = MutableStateFlow<NoteEntity?>(null)
    /** A note just deleted from its page, for the Notes page to offer Undo. */
    val deleted: StateFlow<NoteEntity?> = _deleted

    fun delete(note: NoteEntity) {
        app.scope.launch {
            val old = lock.withLock { dao.get(note.id)?.also { dao.delete(it.id) } } ?: return@launch
            _deleted.value = old
        }
    }

    fun deletedShown() {
        _deleted.value = null
    }

    fun restore(note: NoteEntity) {
        app.scope.launch { lock.withLock { dao.insert(note) } }
    }

    fun importKeep(uri: Uri) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            val result = runCatching { KeepImport.import(getApplication(), uri, dao) }.getOrNull()
            _importing.value = false
            _done.tryEmit(
                Done(
                    when {
                        result == null -> "Couldn't read that file. Pick the .zip from Google Takeout."
                        result.notKeep -> "No Keep notes in that file. In Takeout, pick Keep and export as .zip."
                        else -> buildString {
                            append("${result.added} ${if (result.added == 1) "note" else "notes"} brought in")
                            if (result.already > 0) append(", ${result.already} were here already")
                            if (result.archived > 0) append(", ${result.archived} archived left out")
                            append(".")
                        }
                    },
                ),
            )
        }
    }
}
