package app.brokeometer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.brokeometer.BrokeApp
import app.brokeometer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** What a restore added, for the message afterwards. */
data class RestoreResult(val added: Int, val trips: Int, val categories: Int, val goals: Boolean, val vault: Boolean = false, val notes: Int = 0)

/**
 * Backups to a file: one now (anywhere, Google Drive included) or every week to a folder on the phone.
 * The file holds every entry, category, trip and goal, never the Gemini key or any other key.
 */
class BackupManager(
    private val context: Context,
    private val repository: Repository,
    private val prefs: Prefs,
    private val vault: app.brokeometer.vault.VaultManager,
) {
    private val lock = Mutex()

    /** Notes live in their own database, made after this. */
    private fun notesDao() = (context.applicationContext as app.brokeometer.BrokeApp).container.notesDatabase.dao()

    private val avatarFile: File get() = File(context.filesDir, AVATAR_FILE)

    suspend fun backupText(): String = withContext(Dispatchers.IO) {
        val goals = prefs.goals.value
        val settings = BackupSettings(
            username = prefs.username.value,
            hiddenRegulars = prefs.hiddenRegulars.value,
            goals = goals,
        )
        val avatar = avatarFile.takeIf { it.exists() && it.length() in 1..MAX_AVATAR_BYTES }
            ?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        val notes = app.brokeometer.notes.NotesBackup.write(notesDao().all())
        BackupCodec.write(repository.backupData(settings, avatar, BuildConfig.VERSION_NAME).copy(vault = vault.backupText(), notes = notes))
    }

    /** Saves a backup to a file the owner picked. */
    suspend fun writeTo(uri: Uri): Boolean = lock.withLock {
        try {
            val text = backupText()
            withContext(Dispatchers.IO) { write(uri, text) }
            prefs.markBackup(System.currentTimeMillis(), failed = false)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Some places, such as Google Drive, only take "w"; others need "wt" to replace an older file fully. */
    private fun write(uri: Uri, text: String) {
        val bytes = text.toByteArray()
        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: error("no stream")
        stream.use { it.write(bytes) }
    }

    suspend fun planRestore(text: String): RestorePlan {
        val backup = BackupCodec.read(text)
        return repository.planRestore(backup, prefs.goals.value).copy(restoresVault = backup.vault != null && !vault.exists)
    }

    suspend fun commitRestore(plan: RestorePlan): RestoreResult {
        val (added, subMap) = repository.commitRestore(plan)
        val backup = plan.backup
        val phoneCategories = repository.dao.categories()
        if (prefs.username.value.isBlank() && backup.settings.username.isNotBlank()) prefs.setUsername(backup.settings.username)
        if (plan.restoresGoals && prefs.goals.value.isEmpty) {
            val goals = backup.settings.goals
            prefs.setGoals(goals.copy(categoryCaps = RestorePlanner.mapCaps(goals.categoryCaps, backup.categories, phoneCategories)))
        }
        val hidden = RestorePlanner.mapHidden(backup.settings.hiddenRegulars, subMap)
        hidden.forEach { if (it !in prefs.hiddenRegulars.value) prefs.hideRegular(it) }
        val avatar = backup.avatar
        if (avatar != null && !avatarFile.exists()) {
            withContext(Dispatchers.IO) {
                runCatching { avatarFile.writeBytes(Base64.decode(avatar, Base64.DEFAULT)) }
            }.onSuccess { prefs.bumpAvatar() }
        }
        val vaultBack = plan.restoresVault && backup.vault?.let { runCatching { vault.restore(it) }.getOrDefault(false) } == true
        val notes = backup.notes?.let { runCatching { app.brokeometer.notes.NotesBackup.restore(notesDao(), it) }.getOrDefault(0) } ?: 0
        return RestoreResult(added, plan.away.size, plan.newCategories.size, plan.restoresGoals, vaultBack, notes)
    }

    // Weekly backup

    /** Remembers the folder, saves a backup there straight away and every week after. */
    suspend fun startWeekly(folder: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(folder, flags) }
        prefs.setBackupFolder(folder.toString())
        schedule(context)
        return backupToFolder()
    }

    fun stopWeekly() {
        val folder = prefs.backupFolder.value
        prefs.setBackupFolder(null)
        prefs.markBackup(null, failed = false)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        if (folder != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folder), flags) }
        }
    }

    /** Readable name of the weekly folder, such as "Documents". */
    fun folderName(): String? {
        val folder = prefs.backupFolder.value ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folder))?.name }.getOrNull()
            ?: Uri.parse(folder).lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
    }

    /** Saves this week's file and keeps the newest few. Returns false when the folder can no longer be used. */
    suspend fun backupToFolder(): Boolean = lock.withLock {
        val folder = prefs.backupFolder.value ?: return@withLock false
        val ok = try {
            val text = backupText()
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: error("no folder")
                if (!dir.canWrite()) error("cannot write")
                val name = "$FILE_PREFIX${LocalDate.now()}.json"
                val file = dir.findFile(name) ?: dir.createFile("application/json", name) ?: error("cannot create")
                write(file.uri, text)
                // Keep the newest few weekly files; older ones made by the app are removed.
                dir.listFiles()
                    .filter { f -> f.name?.let { WEEKLY_NAME.matches(it) } == true }
                    .sortedByDescending { it.name }
                    .drop(KEEP_WEEKLY)
                    .forEach { runCatching { it.delete() } }
            }
            true
        } catch (e: Exception) {
            false
        }
        prefs.markBackup(if (ok) System.currentTimeMillis() else null, failed = !ok)
        ok
    }

    companion object {
        const val AVATAR_FILE = "avatar.jpg"
        const val FILE_PREFIX = "DoubleOBroke-backup-"
        const val KEEP_WEEKLY = 4
        private const val WORK_NAME = "weekly-backup"
        private const val MAX_AVATAR_BYTES = 2L * 1024 * 1024
        private val WEEKLY_NAME = Regex("""(DoubleOBroke|BrokeOMeter)-backup-\d{4}-\d{2}-\d{2}( \(\d+\))?\.json""")

        /** Keeps the weekly job in place. Safe to call on every start: an existing job is left alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeeklyBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(7, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class WeeklyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BrokeApp).container
        if (container.prefs.backupFolder.value == null) return Result.success()
        return if (container.backups.backupToFolder()) Result.success() else Result.retry()
    }
}
