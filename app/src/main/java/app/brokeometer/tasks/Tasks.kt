package app.brokeometer.tasks

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

/** Where a task came from. */
object TaskSource {
    const val YOU = "YOU"
    const val EMAIL = "EMAIL"
    const val CALENDAR = "CALENDAR"
    /** A note's own "Remind me" date. */
    const val NOTE_REMINDER = "NOTE_REMINDER"
    /** Found in a note's words by the AI. */
    const val NOTE = "NOTE"

    fun label(source: String): String = when (source) {
        YOU -> "Added by you"
        EMAIL -> "From an email"
        CALENDAR -> "From your calendar"
        NOTE_REMINDER -> "Note reminder"
        NOTE -> "From a note"
        else -> source
    }

    /** Ones you set yourself, which never need telling about as new. */
    fun yours(source: String): Boolean = source == YOU || source == NOTE_REMINDER
}

/**
 * One thing to do or go to, with a day (and maybe a time) or none. [sourceKey] is unique for its
 * [source], so the same task found again isn't added twice; [ref] points back to the email's list entry
 * or the note. A task you delete is kept as [dismissed], so it doesn't come back from where it came from.
 */
@Entity(tableName = "tasks", indices = [Index(value = ["source", "sourceKey"], unique = true)])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** A line more: who it's from, or where. */
    val detail: String = "",
    /** "2026-10-27", or null for no date. */
    val day: String? = null,
    /** Minutes after midnight, or null for no time. */
    val time: Int? = null,
    val source: String,
    val sourceKey: String,
    val ref: Long? = null,
    /** The email's account and Message-ID, for emails, so one with no list entry can still be opened. */
    val accountId: Long? = null,
    val messageId: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long,
    /** When its own reminder (or notice) went. */
    val remindedAt: Long? = null,
    val dismissed: Boolean = false,
)

@Dao
interface TasksDao {
    /** Still to do: dated ones by day and time, then the rest. */
    @Query("SELECT * FROM tasks WHERE done = 0 AND dismissed = 0 ORDER BY day IS NULL, day, time IS NULL, time, createdAt")
    fun observeOpen(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 1 AND dismissed = 0 ORDER BY doneAt DESC LIMIT 300")
    fun observeDone(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND dismissed = 0")
    suspend fun open(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE source = :source AND sourceKey = :key")
    suspend fun byKey(source: String, key: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE source = :source")
    suspend fun bySource(source: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE source IN (:sources) AND ref = :ref")
    suspend fun byRef(sources: List<String>, ref: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET done = :done, doneAt = :at WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, at: Long?)

    /** Ones whose day has gone by are put away: only what's coming matters. */
    @Query("UPDATE tasks SET dismissed = 1 WHERE done = 0 AND dismissed = 0 AND day IS NOT NULL AND day < :today")
    suspend fun putAwayPast(today: String)

    @Query("UPDATE tasks SET done = :done, doneAt = :at WHERE id IN (:ids)")
    suspend fun setDoneMany(ids: List<Long>, done: Boolean, at: Long?)

    @Query("UPDATE tasks SET remindedAt = :at WHERE id IN (:ids)")
    suspend fun setReminded(ids: List<Long>, at: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    /** Done or dismissed ones from long ago go, so the list doesn't grow for ever. */
    @Query("DELETE FROM tasks WHERE (done = 1 AND doneAt < :before) OR (dismissed = 1 AND createdAt < :before)")
    suspend fun trim(before: Long)
}

@Database(entities = [TaskEntity::class], version = 1, exportSchema = false)
abstract class TasksDatabase : RoomDatabase() {
    abstract fun dao(): TasksDao

    companion object {
        fun create(context: Context): TasksDatabase =
            Room.databaseBuilder(context.applicationContext, TasksDatabase::class.java, "tasks.db").build()
    }
}
