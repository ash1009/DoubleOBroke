package app.brokeometer.mail

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** An email account the owner added. The app password is kept in the phone's secrets, not here. */
@Entity(tableName = "mail_accounts", indices = [Index(value = ["address"], unique = true)])
data class MailAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    /** "gmail", "yahoo" or "custom" (test servers only). */
    val provider: String,
    val host: String,
    val port: Int,
    val ssl: Boolean,
    val addedAt: Long,
    /** When the last full read of the mailbox finished, or 0. */
    val scannedAt: Long = 0,
    val scannedCount: Int = 0,
)

/** A kind of email, such as "Bills" or "Promotions". Made by the AI, then changed by the owner. */
@Entity(tableName = "mail_categories")
data class MailCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    /** On: emails like this get a notification. Off: marked read and archived. */
    val important: Boolean,
    val position: Int,
)

/** Everyone who has sent the owner email, with what the app knows about them. */
@Entity(
    tableName = "mail_senders",
    indices = [Index(value = ["accountId", "address"], unique = true), Index("categoryId")],
)
data class MailSenderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    /** Lower case. */
    val address: String,
    val name: String,
    val categoryId: Long?,
    /** "AI" or "USER". The owner's choice is never overwritten. */
    val categorySource: String,
    val total: Int,
    val unread: Int,
    /** Emails in the last 90 days, for "a month" figures. */
    val recent: Int,
    val firstAt: Long,
    val lastAt: Long,
    /** A few recent subjects, one per line, so the AI and the owner can see what they send. */
    val subjects: String,
    /** The List-Unsubscribe header, if the sender is a mailing list. */
    val unsubscribe: String?,
    /** True when the list allows one-click unsubscribe (RFC 8058). */
    val oneClick: Boolean,
    val unsubscribedAt: Long?,
)

data class CategoryCount(val categoryId: Long?, val senders: Int, val emails: Int)

/** What the agent did with one email, newest first on the Email page, so any choice can be undone. */
@Entity(tableName = "mail_log", indices = [Index("at")])
data class MailLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val accountId: Long,
    /** "inbox" or "spam". */
    val folder: String,
    val uid: Long,
    val messageId: String?,
    val fromName: String,
    val fromAddress: String,
    val subject: String,
    val categoryId: Long?,
    val categoryName: String,
    /** One of [MailActions]. */
    val action: String,
    /** The AI's one line about the email, or why nothing was done. */
    val summary: String,
    val undone: Boolean = false,
    /** True once the email app's own notification for this email was cleared. */
    @ColumnInfo(defaultValue = "0") val cleared: Boolean = false,
)

object MailActions {
    const val NOTIFIED = "NOTIFIED"
    const val ARCHIVED = "ARCHIVED"
    const val SPAM_DELETED = "SPAM_DELETED"
    const val SPAM_FLAGGED = "SPAM_FLAGGED"
    /** Already read on another device, so left as it was. */
    const val READ_ELSEWHERE = "READ_ELSEWHERE"
    /** The AI could not be reached and the sender has no category yet: left in the inbox, alert kept. */
    const val UNDECIDED = "UNDECIDED"
    /**
     * In spam and not judged yet: the AI couldn't answer, or it couldn't be moved to the bin. Tried again
     * on later checks; one the AI still can't judge after a few hours is left in spam for the owner, with
     * a "might not be spam" notification.
     */
    const val SPAM_UNCHECKED = "SPAM_UNCHECKED"
    /** The owner said it is not spam: moved to the inbox. */
    const val NOT_SPAM = "NOT_SPAM"
    /** From a sender the owner said is not spam: moved to the inbox for the agent to handle. */
    const val MOVED_FROM_SPAM = "MOVED_FROM_SPAM"
    /** Tidy-up: an older unread email left as it was, because its kind is switched on or it was unclear. */
    const val KEPT = "KEPT"
    /** Marked read by the owner, or because the owner said its sender isn't important. */
    const val MARKED_READ = "MARKED_READ"
    /** Marked unread by the owner. */
    const val MARKED_UNREAD = "MARKED_UNREAD"
    /** Deleted by the owner: in the bin, which the email service empties after about 30 days. */
    const val DELETED = "DELETED"
    /**
     * Only on the Important page: a starred email the agent never handled. Never saved in the list;
     * used to open it the same way as the others.
     */
    const val STARRED = "STARRED"
    /**
     * Only in a search of the whole mailbox: an email the agent never handled. Never saved in the list;
     * used to open it the same way as the others.
     */
    const val FOUND = "FOUND"

    private val unreadActions = setOf(NOTIFIED, KEPT, UNDECIDED, NOT_SPAM, MOVED_FROM_SPAM, MARKED_UNREAD)
    private val readActions = setOf(READ_ELSEWHERE, MARKED_READ)

    /** Emails that are read and in the inbox, or archived, which the owner can mark unread. */
    fun canMarkUnread(e: MailLogEntity): Boolean =
        !e.undone && e.messageId != null && (e.action in readActions || e.action == ARCHIVED)

    /** Archived by the agent and not put back. */
    fun archived(e: MailLogEntity): Boolean = e.action == ARCHIVED && !e.undone

    /** Deleted by the owner and not put back. */
    fun binned(e: MailLogEntity): Boolean = e.action == DELETED && !e.undone

    /** Emails in the inbox or the archive (not spam, not the bin), which can be archived or deleted. */
    fun canDelete(e: MailLogEntity): Boolean =
        e.messageId != null && !binned(e) && (e.folder == MailFolders.INBOX || e.action == NOT_SPAM || e.action == MOVED_FROM_SPAM || e.undone)

    fun canArchive(e: MailLogEntity): Boolean = canDelete(e) && !archived(e)

    /**
     * How much an email needs the owner, for the top of the Activity list: lower comes first, null
     * means it is dealt with. Spam that might be real needs a yes or no, so it counts too.
     */
    fun attention(e: MailLogEntity): Int? = when {
        e.undone && (e.action == ARCHIVED || e.action == SPAM_DELETED) -> 5
        e.undone -> null
        e.action == NOTIFIED -> 0
        e.action == UNDECIDED -> 1
        e.action == SPAM_FLAGGED -> 2
        e.action == KEPT -> 3
        e.action == NOT_SPAM || e.action == MOVED_FROM_SPAM || e.action == MARKED_UNREAD -> 4
        else -> null
    }

    /** Emails left unread in the inbox, which the owner can mark read from the Activity list. */
    fun leftUnread(e: MailLogEntity): Boolean =
        if (e.undone) e.action == ARCHIVED || e.action == SPAM_DELETED else e.action in unreadActions

    /**
     * What an entry becomes so the list matches the email app, or null when it already does. Read
     * there: read here. Unread in the inbox there (also when archiving didn't go through): unread here.
     * Archived or deleted there: out of Needs you.
     */
    fun matching(e: MailLogEntity, place: MailPlace): String? {
        val unread = leftUnread(e)
        return when (place) {
            MailPlace.INBOX_UNREAD -> MARKED_UNREAD.takeIf { !unread }
            MailPlace.INBOX_READ -> MARKED_READ.takeIf { unread || archived(e) }
            MailPlace.ARCHIVED -> ARCHIVED.takeIf { unread }
            MailPlace.GONE -> DELETED.takeIf { unread }
        }
    }

    /** Emails that are in, or went to, the inbox, so their sender can be marked not important. */
    fun inInbox(e: MailLogEntity): Boolean =
        e.fromAddress.isNotEmpty() && (e.folder == MailFolders.INBOX || e.action == NOT_SPAM || e.action == MOVED_FROM_SPAM || e.undone)
}

/**
 * A starred email, or one Gmail marks important: the Important page. Copied from the mailbox now and
 * then (and when starred or unstarred in the app), so the page shows straight away.
 */
@Entity(tableName = "mail_starred", indices = [Index(value = ["accountId", "messageId"], unique = true)])
data class MailStarredEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val messageId: String,
    val fromName: String,
    val fromAddress: String,
    val subject: String,
    val at: Long,
    val seen: Boolean,
    val starred: Boolean,
    val important: Boolean,
)

/** The last email the agent looked at in each folder, so each email is handled once. */
@Entity(tableName = "mail_cursors", primaryKeys = ["accountId", "folder"])
data class MailCursorEntity(
    val accountId: Long,
    val folder: String,
    val uidValidity: Long,
    val lastUid: Long,
)

@Dao
interface MailDao {
    @Query("SELECT * FROM mail_accounts ORDER BY id")
    fun observeAccounts(): Flow<List<MailAccountEntity>>

    @Query("SELECT * FROM mail_accounts ORDER BY id")
    suspend fun accounts(): List<MailAccountEntity>

    @Query("SELECT * FROM mail_accounts WHERE id = :id")
    suspend fun account(id: Long): MailAccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: MailAccountEntity): Long

    @Update
    suspend fun updateAccount(account: MailAccountEntity)

    @Query("DELETE FROM mail_accounts WHERE id = :id")
    suspend fun deleteAccount(id: Long)

    @Query("DELETE FROM mail_senders WHERE accountId = :accountId")
    suspend fun deleteSendersOf(accountId: Long)

    @Query("SELECT * FROM mail_categories ORDER BY position")
    fun observeCategories(): Flow<List<MailCategoryEntity>>

    @Query("SELECT * FROM mail_categories ORDER BY position")
    suspend fun categories(): List<MailCategoryEntity>

    @Insert
    suspend fun insertCategory(category: MailCategoryEntity): Long

    @Update
    suspend fun updateCategory(category: MailCategoryEntity)

    @Query("DELETE FROM mail_categories")
    suspend fun deleteCategories()

    @Query("SELECT * FROM mail_senders ORDER BY total DESC")
    fun observeSenders(): Flow<List<MailSenderEntity>>

    @Query("SELECT * FROM mail_senders WHERE accountId = :accountId")
    suspend fun sendersOf(accountId: Long): List<MailSenderEntity>

    @Query("SELECT * FROM mail_senders")
    suspend fun senders(): List<MailSenderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSenders(rows: List<MailSenderEntity>)

    @Query("UPDATE mail_senders SET categoryId = :categoryId, categorySource = :source WHERE id = :id")
    suspend fun setSenderCategory(id: Long, categoryId: Long?, source: String)

    @Query("UPDATE mail_senders SET categoryId = NULL WHERE categorySource = 'AI'")
    suspend fun clearAiCategories()

    @Query("UPDATE mail_senders SET unsubscribedAt = :at WHERE id = :id")
    suspend fun markUnsubscribed(id: Long, at: Long)

    @Query("SELECT * FROM mail_senders WHERE accountId = :accountId AND address = :address")
    suspend fun sender(accountId: Long, address: String): MailSenderEntity?

    @Insert
    suspend fun insertLog(row: MailLogEntity): Long

    @Query("SELECT * FROM mail_log WHERE id = :id")
    suspend fun log(id: Long): MailLogEntity?

    @Query("SELECT * FROM mail_log WHERE id = :id")
    fun observeLogEntry(id: Long): Flow<MailLogEntity?>

    /** The newest list entry for an email, by its Message-ID. */
    @Query("SELECT id FROM mail_log WHERE messageId = :messageId ORDER BY id DESC LIMIT 1")
    suspend fun logIdFor(messageId: String): Long?

    /**
     * How often the agent has dealt with this email before; an email put back with Undo, or one the
     * owner took out of spam, is left alone. Emails still waiting for a decision, or moved out of spam
     * by the agent, are dealt with as new.
     */
    @Query("SELECT COUNT(*) FROM mail_log WHERE messageId = :messageId AND action NOT IN ('UNDECIDED', 'MOVED_FROM_SPAM')")
    suspend fun handledCount(messageId: String): Int

    /** Whether this email is in the list at all, whatever happened to it (the tidy-up leaves those alone). */
    @Query("SELECT COUNT(*) FROM mail_log WHERE messageId = :messageId")
    suspend fun listedCount(messageId: String): Int

    /**
     * This email's entry still waiting for the inbox agent: one the AI couldn't decide on, or one that
     * came out of spam. The agent's answer goes into the same entry, so each email is listed once.
     */
    @Query("SELECT * FROM mail_log WHERE messageId = :messageId AND undone = 0 AND action IN ('UNDECIDED', 'MOVED_FROM_SPAM') ORDER BY id DESC LIMIT 1")
    suspend fun undecided(messageId: String): MailLogEntity?

    /** Inbox emails the AI couldn't decide on yet, since [since], newest first: tried again on each check. */
    @Query("SELECT * FROM mail_log WHERE accountId = :accountId AND folder = 'inbox' AND action = 'UNDECIDED' AND undone = 0 AND at >= :since ORDER BY id DESC LIMIT :limit")
    suspend fun undecidedSince(accountId: Long, since: Long, limit: Int): List<MailLogEntity>

    /** Spam not judged yet, oldest first: tried again on each check. */
    @Query("SELECT * FROM mail_log WHERE accountId = :accountId AND folder = 'spam' AND action = 'SPAM_UNCHECKED' AND undone = 0 ORDER BY id LIMIT :limit")
    suspend fun uncheckedSpam(accountId: Long, limit: Int): List<MailLogEntity>

    @Update
    suspend fun updateLog(row: MailLogEntity)

    /** Recent entries, to match the email apps' notifications against. */
    @Query("SELECT * FROM mail_log WHERE at >= :since AND folder = 'inbox'")
    suspend fun inboxLogSince(since: Long): List<MailLogEntity>

    @Query("UPDATE mail_log SET cleared = 1 WHERE id IN (:ids)")
    suspend fun markCleared(ids: List<Long>)

    @Query("UPDATE mail_log SET action = :action, summary = :summary WHERE id = :id")
    suspend fun setLogOutcome(id: Long, action: String, summary: String)

    /** Every email the Activity list shows as left unread. Matches [MailActions.leftUnread]. */
    @Query(
        "SELECT * FROM mail_log WHERE (undone = 0 AND action IN ('NOTIFIED', 'KEPT', 'UNDECIDED', 'NOT_SPAM', 'MOVED_FROM_SPAM', 'MARKED_UNREAD')) " +
            "OR (undone = 1 AND action IN ('ARCHIVED', 'SPAM_DELETED'))"
    )
    suspend fun leftUnread(): List<MailLogEntity>

    /** Puts an entry in a state, keeping the AI's line about the email. */
    @Query("UPDATE mail_log SET action = :action, undone = :undone WHERE id = :id")
    suspend fun setLogState(id: Long, action: String, undone: Boolean)

    @Query("SELECT COUNT(*) FROM mail_log")
    suspend fun logCount(): Int

    @Query("SELECT * FROM mail_log WHERE id IN (:ids)")
    suspend fun logs(ids: List<Long>): List<MailLogEntity>

    @Query("SELECT * FROM mail_log WHERE messageId IN (:messageIds)")
    suspend fun logsFor(messageIds: List<String>): List<MailLogEntity>

    /** Keeps the AI's line about the email; only what happened to it changes. */
    @Query("UPDATE mail_log SET action = 'MARKED_READ', undone = 0 WHERE id IN (:ids)")
    suspend fun markReadLogs(ids: List<Long>)

    @Query("UPDATE mail_log SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)

    @Query("UPDATE mail_log SET action = :action WHERE id = :id")
    suspend fun setLogAction(id: Long, action: String)

    @Query("SELECT * FROM mail_log ORDER BY at DESC, id DESC LIMIT 1500")
    fun observeLog(): Flow<List<MailLogEntity>>

    @Query("SELECT * FROM mail_log ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recentLogs(limit: Int): List<MailLogEntity>

    @Query("DELETE FROM mail_log WHERE id NOT IN (SELECT id FROM mail_log ORDER BY id DESC LIMIT 10000)")
    suspend fun trimLog()

    @Query("SELECT * FROM mail_cursors WHERE accountId = :accountId AND folder = :folder")
    suspend fun cursor(accountId: Long, folder: String): MailCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(cursor: MailCursorEntity)

    @Query("DELETE FROM mail_cursors WHERE accountId = :accountId")
    suspend fun deleteCursorsOf(accountId: Long)

    @Query("SELECT * FROM mail_starred ORDER BY at DESC")
    fun observeStarred(): Flow<List<MailStarredEntity>>

    @Query("SELECT * FROM mail_starred WHERE id = :id")
    fun observeStarredEntry(id: Long): Flow<MailStarredEntity?>

    @Query("SELECT * FROM mail_starred WHERE id = :id")
    suspend fun starredEntry(id: Long): MailStarredEntity?

    @Query("SELECT * FROM mail_starred WHERE accountId = :accountId")
    suspend fun starredOf(accountId: Long): List<MailStarredEntity>

    @Query("SELECT * FROM mail_starred WHERE accountId = :accountId AND messageId = :messageId")
    suspend fun starredFor(accountId: Long, messageId: String): MailStarredEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStarred(row: MailStarredEntity): Long

    @Update
    suspend fun updateStarred(row: MailStarredEntity)

    @Query("DELETE FROM mail_starred WHERE id IN (:ids)")
    suspend fun deleteStarred(ids: List<Long>)

    @Query("DELETE FROM mail_starred WHERE accountId = :accountId")
    suspend fun deleteStarredOf(accountId: Long)
}

@Database(
    entities = [
        MailAccountEntity::class,
        MailCategoryEntity::class,
        MailSenderEntity::class,
        MailLogEntity::class,
        MailCursorEntity::class,
        MailStarredEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun dao(): MailDao

    companion object {
        /** Version 2 adds what the agent did and where it got to. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mail_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `folder` TEXT NOT NULL, `uid` INTEGER NOT NULL, " +
                        "`messageId` TEXT, `fromName` TEXT NOT NULL, `fromAddress` TEXT NOT NULL, `subject` TEXT NOT NULL, " +
                        "`categoryId` INTEGER, `categoryName` TEXT NOT NULL, `action` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                        "`undone` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_log_at` ON `mail_log` (`at`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mail_cursors` (`accountId` INTEGER NOT NULL, `folder` TEXT NOT NULL, " +
                        "`uidValidity` INTEGER NOT NULL, `lastUid` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `folder`))"
                )
            }
        }

        /** Version 3 remembers whether the email app's own notification was cleared. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `mail_log` ADD COLUMN `cleared` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 4 keeps starred and important emails for the Important page. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mail_starred` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, `messageId` TEXT NOT NULL, `fromName` TEXT NOT NULL, " +
                        "`fromAddress` TEXT NOT NULL, `subject` TEXT NOT NULL, `at` INTEGER NOT NULL, `seen` INTEGER NOT NULL, " +
                        "`starred` INTEGER NOT NULL, `important` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mail_starred_accountId_messageId` ON `mail_starred` (`accountId`, `messageId`)")
            }
        }

        fun create(context: Context): MailDatabase =
            Room.databaseBuilder(context.applicationContext, MailDatabase::class.java, "mail.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
