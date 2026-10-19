package app.brokeometer.notes

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A note, like Google Keep's: a title and either text (with **bold**, *italic* and "# " headings, see
 * [NoteText]) or a checklist, a colour, a background and a pin.
 */
@Entity(tableName = "notes", indices = [Index(value = ["keepKey"], unique = true)])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    /** The text, with its marks. Empty for a checklist. */
    val body: String = "",
    /** The checklist, one "[x] done" or "[ ] to do" per line ([NoteText.encodeItems]). */
    val items: String = "",
    val checklist: Boolean = false,
    /** One of [NoteColors] (Google Keep's names, so imported colours carry over). */
    val color: String = NoteColors.DEFAULT,
    /** One of [NoteBackgrounds]. */
    val background: String = NoteBackgrounds.NONE,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** Which Google Keep note it came from, so importing again doesn't add it twice. */
    val keepKey: String? = null,
)

/** The checklist's items. */
val NoteEntity.itemList: List<NoteItem> get() = NoteText.decodeItems(items)

/** Nothing written in it: such a note isn't kept. */
val NoteEntity.isBlank: Boolean get() = title.isBlank() && body.isBlank() && itemList.none { it.text.isNotBlank() }

/** Note colours, by Google Keep's names for them. */
object NoteColors {
    const val DEFAULT = "DEFAULT"
    val ALL = listOf(DEFAULT, "RED", "ORANGE", "YELLOW", "GREEN", "TEAL", "BLUE", "CERULEAN", "PURPLE", "PINK", "BROWN", "GRAY")

    /** What each is called in the colour picker. */
    val NAMES = mapOf(
        DEFAULT to "Default", "RED" to "Coral", "ORANGE" to "Peach", "YELLOW" to "Sand", "GREEN" to "Mint", "TEAL" to "Sage",
        "BLUE" to "Fog", "CERULEAN" to "Storm", "PURPLE" to "Dusk", "PINK" to "Blossom", "BROWN" to "Clay", "GRAY" to "Chalk",
    )

    /** ARGB for the dark theme and the light theme; null for the default (the card's own colour). */
    fun argb(key: String, dark: Boolean): Long? = (if (dark) DARK else LIGHT)[key]

    private val DARK = mapOf(
        "RED" to 0xFF77172EL, "ORANGE" to 0xFF692B17L, "YELLOW" to 0xFF7C4A03L, "GREEN" to 0xFF264D3BL, "TEAL" to 0xFF0C625DL,
        "BLUE" to 0xFF256377L, "CERULEAN" to 0xFF284255L, "PURPLE" to 0xFF472E5BL, "PINK" to 0xFF6C394FL, "BROWN" to 0xFF4B443AL,
        "GRAY" to 0xFF3C3F43L,
    )
    private val LIGHT = mapOf(
        "RED" to 0xFFFAAFA8L, "ORANGE" to 0xFFF39F76L, "YELLOW" to 0xFFFFF8B8L, "GREEN" to 0xFFE2F6D3L, "TEAL" to 0xFFB4DDD3L,
        "BLUE" to 0xFFD4E4EDL, "CERULEAN" to 0xFFAECCDCL, "PURPLE" to 0xFFD3BFDBL, "PINK" to 0xFFF6E2DDL, "BROWN" to 0xFFE9E3D4L,
        "GRAY" to 0xFFEFEFF1L,
    )
}

/** Note backgrounds: simple patterns drawn over the colour. */
object NoteBackgrounds {
    const val NONE = "NONE"
    val ALL = listOf(NONE, "DOTS", "GRID", "LINES", "DIAGONAL", "WAVES", "CIRCLES")
    val NAMES = mapOf(NONE to "None", "DOTS" to "Dots", "GRID" to "Grid", "LINES" to "Lined", "DIAGONAL" to "Stripes", "WAVES" to "Waves", "CIRCLES" to "Circles")
}

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY id")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?

    @Query("SELECT keepKey FROM notes WHERE keepKey IS NOT NULL")
    suspend fun keepKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}

@Database(entities = [NoteEntity::class], version = 1, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun dao(): NotesDao

    companion object {
        fun create(context: Context): NotesDatabase =
            Room.databaseBuilder(context.applicationContext, NotesDatabase::class.java, "notes.db").build()
    }
}
