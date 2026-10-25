package app.brokeometer.tasks

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.brokeometer.BrokeApp
import app.brokeometer.R
import app.brokeometer.mail.MailAgent
import app.brokeometer.mail.MailDao
import app.brokeometer.mail.MailItem
import app.brokeometer.mail.MailPrompts
import app.brokeometer.mail.MailReader
import app.brokeometer.mail.TaskSink
import app.brokeometer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Tasks: things to do or go to, from emails (the AI picks them out as each email comes in, and once
 * from the last two weeks), the phone's calendar, notes (their "Remind me" date, and dates the AI finds
 * in them) and ones you add. Each evening one notification lists tomorrow's; things with a time also
 * get their own a day before. Calendar events never leave the phone; emails and notes go to the same
 * AI the email agent uses.
 */
class TaskAgent(
    private val context: Context,
    private val dao: TasksDao,
    private val mailDao: MailDao,
    private val mail: MailAgent,
    private val reader: MailReader,
    private val scope: CoroutineScope,
) : TaskSink {

    private val sp = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _fromEmail = MutableStateFlow(sp.getBoolean(KEY_EMAIL, true))
    val fromEmail: StateFlow<Boolean> = _fromEmail
    private val _fromCalendar = MutableStateFlow(sp.getBoolean(KEY_CALENDAR, false))
    val fromCalendar: StateFlow<Boolean> = _fromCalendar
    private val _fromNotes = MutableStateFlow(sp.getBoolean(KEY_NOTES, true))
    val fromNotes: StateFlow<Boolean> = _fromNotes
    private val _summaryAt = MutableStateFlow(sp.getInt(KEY_SUMMARY_AT, TaskRules.DEFAULT_SUMMARY_AT))
    val summaryAt: StateFlow<Int> = _summaryAt
    private val _ownReminders = MutableStateFlow(sp.getBoolean(KEY_OWN, true))
    val ownReminders: StateFlow<Boolean> = _ownReminders

    /** The look through the last two weeks of email: what happened, in a line, or null before it ran. */
    private val _lookBack = MutableStateFlow(sp.getString(KEY_LOOK_BACK, null))
    val lookBack: StateFlow<String?> = _lookBack
    private val _lookingBack = MutableStateFlow(false)
    val lookingBack: StateFlow<Boolean> = _lookingBack

    val open = dao.observeOpen()
    val done = dao.observeDone()

    // Settings

    fun setFromEmail(on: Boolean) = put(KEY_EMAIL, on).also { _fromEmail.value = on }
    fun setFromNotes(on: Boolean) = put(KEY_NOTES, on).also { _fromNotes.value = on }
    fun setOwnReminders(on: Boolean) = put(KEY_OWN, on).also { _ownReminders.value = on }

    fun setFromCalendar(on: Boolean) {
        put(KEY_CALENDAR, on)
        _fromCalendar.value = on
        scope.launch {
            if (on) syncCalendar() else lock.withLock { dao.bySource(TaskSource.CALENDAR).filter { !it.done }.forEach { dao.delete(it.id) } }
        }
    }

    fun setSummaryAt(minutes: Int) {
        sp.edit().putInt(KEY_SUMMARY_AT, minutes.coerceIn(0, 24 * 60 - 1)).apply()
        _summaryAt.value = minutes
    }

    private fun put(key: String, on: Boolean) = sp.edit().putBoolean(key, on).apply()

    fun canReadCalendar(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // Tasks you add and change

    fun add(title: String, day: LocalDate?, time: Int?) {
        val t = title.trim()
        if (t.isEmpty()) return
        scope.launch {
            lock.withLock {
                dao.insert(TaskEntity(title = t, day = day?.toString(), time = time?.takeIf { day != null }, source = TaskSource.YOU, sourceKey = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis()))
            }
        }
    }

    /** New words or a new day: its reminder can go again. */
    fun change(task: TaskEntity, title: String, day: LocalDate?, time: Int?) {
        scope.launch {
            lock.withLock {
                val old = dao.get(task.id) ?: return@withLock
                val newDay = day?.toString()
                val newTime = time?.takeIf { day != null }
                dao.update(old.copy(title = title.trim().ifEmpty { old.title }, day = newDay, time = newTime, remindedAt = if (newDay != old.day || newTime != old.time) null else old.remindedAt))
            }
        }
    }

    fun setDone(task: TaskEntity, done: Boolean) {
        scope.launch { lock.withLock { dao.setDone(task.id, done, if (done) System.currentTimeMillis() else null) } }
    }

    /** Several at once, picked on the list. */
    fun setDone(tasks: List<TaskEntity>, done: Boolean) {
        if (tasks.isEmpty()) return
        scope.launch { lock.withLock { dao.setDoneMany(tasks.map { it.id }, done, if (done) System.currentTimeMillis() else null) } }
    }

    fun delete(tasks: List<TaskEntity>) = tasks.forEach { delete(it) }

    /** Yours go; ones from an email, the calendar or a note are kept hidden, so they don't come back. */
    fun delete(task: TaskEntity) {
        scope.launch {
            lock.withLock {
                if (task.source == TaskSource.YOU) dao.delete(task.id) else dao.get(task.id)?.let { dao.update(it.copy(dismissed = true)) }
            }
        }
    }

    // From emails

    override fun wantsEmailTasks(): Boolean = _fromEmail.value

    /**
     * An email the agent dealt with without asking the AI (one you'd already read, or from a sender you
     * said isn't important): asked about on its own, so every email is looked at for tasks.
     */
    override fun checkEmail(accountId: Long, logId: Long, item: MailItem) {
        if (!_fromEmail.value) return
        scope.launch {
            val answer = mail.askText(TaskPrompts.SYSTEM, TaskPrompts.emails(listOf(item), LocalDate.now()), 500) ?: return@launch
            val task = TaskPrompts.parse(answer, 1).firstOrNull()?.task ?: return@launch
            if (keepFromEmail(accountId, logId, item, task)) check()
        }
    }

    override fun fromEmail(accountId: Long, logId: Long, item: MailItem, task: MailPrompts.TaskHint) {
        scope.launch {
            if (keepFromEmail(accountId, logId, item, task)) check()
        }
    }

    /**
     * The email's task, its day worked out from the day the email came when it's a time limit. One task
     * per email, so finding it again (on the look back, say) never adds it twice.
     */
    private suspend fun keepFromEmail(accountId: Long, logId: Long?, item: MailItem, task: MailPrompts.TaskHint): Boolean {
        val came = Instant.ofEpochMilli(item.receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val day = task.dueDay(came) ?: return false
        // Only what's still to come.
        if (day.isBefore(LocalDate.now())) return false
        val key = "$accountId:${item.messageId ?: "uid${item.uid}"}"
        val from = item.fromName.ifBlank { item.fromAddress }
        return lock.withLock {
            dao.insert(
                TaskEntity(
                    title = task.what,
                    detail = listOf(from, item.subject.take(120)).filter { it.isNotBlank() }.joinToString(": "),
                    day = day.toString(),
                    time = TaskRules.time(task.time),
                    source = TaskSource.EMAIL,
                    sourceKey = key,
                    ref = logId,
                    accountId = accountId,
                    messageId = item.messageId,
                    createdAt = System.currentTimeMillis(),
                ),
            ) > 0
        }
    }

    /**
     * Once, when Tasks starts: the last two weeks of email, in batches, for anything still to come. Run
     * again with [again] (after it couldn't finish, say).
     */
    fun lookBackOnce(again: Boolean = false) {
        if (!_fromEmail.value || _lookingBack.value) return
        val version = sp.getInt(KEY_LOOKED_BACK_VERSION, 0)
        if (!again && sp.getBoolean(KEY_LOOKED_BACK, false) && version >= LOOK_BACK_VERSION) return
        scope.launch {
            _lookingBack.value = true
            try {
                // Read with older rules: what it found is found again, the new way.
                if (version < LOOK_BACK_VERSION) {
                    lock.withLock { dao.bySource(TaskSource.EMAIL).filter { !it.done && !it.dismissed }.forEach { dao.delete(it.id) } }
                    sp.edit().putInt(KEY_LOOKED_BACK_VERSION, LOOK_BACK_VERSION).apply()
                }
                lookBack()
            } finally {
                _lookingBack.value = false
            }
        }
    }

    private suspend fun lookBack() {
        val accounts = mailDao.accounts()
        if (accounts.isEmpty()) return
        val today = LocalDate.now()
        var read = 0
        var found = 0
        var failed = false
        for (account in accounts) {
            val items = runCatching { reader.recent(account, TaskRules.BACKFILL_DAYS, LOOK_BACK_MAX) }.getOrElse {
                failed = true
                emptyList()
            }
            for (batch in items.chunked(BATCH)) {
                val answer = mail.askText(TaskPrompts.SYSTEM, TaskPrompts.emails(batch, today), 1500)
                if (answer == null) {
                    failed = true
                    break
                }
                read += batch.size
                for (f in TaskPrompts.parse(answer, batch.size)) {
                    val item = batch[f.index ?: continue]
                    val logId = item.messageId?.let { mailDao.logIdFor(it) }
                    if (keepFromEmail(account.id, logId, item, f.task)) found++
                }
            }
        }
        val line = when {
            failed && read == 0 -> "Couldn't look through your emails (no connection, or the AI didn't answer). Try again."
            failed -> "Looked through $read emails and found $found ${if (found == 1) "task" else "tasks"}, but couldn't finish. Try again for the rest."
            else -> "Looked through $read emails from the last two weeks and found $found ${if (found == 1) "task" else "tasks"}."
        }
        sp.edit().putString(KEY_LOOK_BACK, line).putBoolean(KEY_LOOKED_BACK, !failed).apply()
        _lookBack.value = line
        if (found > 0) check()
    }

    // From notes

    /** A note's own "Remind me" date: set, changed, or taken off (null [day]). */
    fun noteReminder(noteId: Long, title: String, day: LocalDate?, time: Int?) {
        scope.launch {
            lock.withLock {
                val key = "note:$noteId"
                val old = dao.byKey(TaskSource.NOTE_REMINDER, key)
                when {
                    day == null -> old?.let { dao.delete(it.id) }
                    old == null -> dao.insert(TaskEntity(title = title.ifBlank { "Note reminder" }, day = day.toString(), time = time, source = TaskSource.NOTE_REMINDER, sourceKey = key, ref = noteId, createdAt = System.currentTimeMillis()))
                    else -> {
                        val moved = old.day != day.toString() || old.time != time
                        dao.update(old.copy(title = title.ifBlank { old.title }, day = day.toString(), time = time, done = if (moved) false else old.done, remindedAt = if (moved) null else old.remindedAt, dismissed = false))
                    }
                }
            }
        }
    }

    /** A note's words changed: the AI looks for dates in it again, and the tasks it found before are replaced. */
    fun scanNote(noteId: Long, title: String, text: String) {
        if (!_fromNotes.value) return
        scope.launch {
            val words = (title + "\n" + text).trim()
            val today = LocalDate.now()
            val found: List<Pair<MailPrompts.TaskHint, LocalDate>> = if (words.length < 6) emptyList() else {
                val answer = mail.askText(TaskPrompts.SYSTEM, TaskPrompts.note(title, text, today), 600) ?: return@launch
                TaskPrompts.parse(answer).mapNotNull { f -> f.task.dueDay(today)?.takeIf { !it.isBefore(today) }?.let { f.task to it } }
            }
            lock.withLock {
                val keys = found.map { "note:$noteId:${TaskRules.keyOf(it.first.what)}" }.toSet()
                dao.byRef(listOf(TaskSource.NOTE), noteId).filter { !it.done && it.sourceKey !in keys }.forEach { dao.delete(it.id) }
                found.forEach { (t, day) ->
                    dao.insert(
                        TaskEntity(
                            title = t.what, detail = title.take(120), day = day.toString(), time = TaskRules.time(t.time), source = TaskSource.NOTE,
                            sourceKey = "note:$noteId:${TaskRules.keyOf(t.what)}", ref = noteId, createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            if (found.isNotEmpty()) check()
        }
    }

    /** A note gone to the Bin: its tasks still to do go too. */
    fun noteGone(noteId: Long) {
        scope.launch {
            lock.withLock { dao.byRef(listOf(TaskSource.NOTE, TaskSource.NOTE_REMINDER), noteId).filter { !it.done }.forEach { dao.delete(it.id) } }
        }
    }

    // From the calendar

    /**
     * Events you added to your own calendar, for the next [TaskRules.CALENDAR_DAYS] days, read on the
     * phone: new ones are added, moved ones follow, and ones taken off the calendar go. Ones Gmail adds
     * (flights, bookings) count; holidays, birthdays, calendars you follow and invitations from others don't.
     */
    suspend fun syncCalendar() {
        if (!_fromCalendar.value || !canReadCalendar()) return
        // Read with older rules: what came from the calendar goes, and is read again the new way.
        if (sp.getInt(KEY_CALENDAR_VERSION, 0) < CALENDAR_VERSION) {
            lock.withLock { dao.bySource(TaskSource.CALENDAR).filter { !it.done }.forEach { dao.delete(it.id) } }
            sp.edit().putInt(KEY_CALENDAR_VERSION, CALENDAR_VERSION).putBoolean(KEY_CALENDAR_SYNCED, false).apply()
        }
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = start + TaskRules.CALENDAR_DAYS * 24 * 60 * 60_000L
        val events = try {
            readCalendar(start, end, zone)
        } catch (e: Exception) {
            _calendarProblem.value = "Couldn't read your calendar (${e.javaClass.simpleName}). It's tried again in 15 minutes."
            return
        }
        _calendarProblem.value = null
        val first = !sp.getBoolean(KEY_CALENDAR_SYNCED, false)
        val now = System.currentTimeMillis()
        lock.withLock {
            val had = dao.bySource(TaskSource.CALENDAR).associateBy { it.sourceKey }
            for (e in events) {
                val old = had[e.key]
                if (old == null) {
                    // The first time, nothing already close is announced as if it were new.
                    val soon = first && TaskRules.dueAt(e.day, e.time, zone).toInstant().toEpochMilli() - now < 24 * 60 * 60_000L
                    dao.insert(TaskEntity(title = e.title, detail = e.detail, day = e.day.toString(), time = e.time, source = TaskSource.CALENDAR, sourceKey = e.key, createdAt = now, remindedAt = if (soon) now else null))
                } else if (!old.done && (old.title != e.title || old.detail != e.detail)) {
                    dao.update(old.copy(title = e.title, detail = e.detail))
                }
            }
            // Anything no longer on your calendar (or no longer counted) goes.
            val keys = events.map { it.key }.toSet()
            had.values.filter { !it.done && it.sourceKey !in keys }.forEach { dao.delete(it.id) }
        }
        sp.edit().putBoolean(KEY_CALENDAR_SYNCED, true).apply()
    }

    private val _calendarProblem = MutableStateFlow<String?>(null)
    /** Why the calendar couldn't be read last time, or null. */
    val calendarProblem: StateFlow<String?> = _calendarProblem

    private data class CalendarEvent(val key: String, val title: String, val detail: String, val day: LocalDate, val time: Int?)

    /**
     * Your own calendars: each Google account's main calendar (the one that belongs to the account
     * itself). Calendars you follow (sports, holidays), birthdays, and calendars made by other apps on
     * the phone belong to something else, so they're left out.
     */
    private fun ownCalendars(): Map<Long, String> {
        val out = HashMap<Long, String>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
        )
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val account = c.getString(1).orEmpty().trim()
                val type = c.getString(2).orEmpty()
                val owner = c.getString(3).orEmpty().trim()
                if (type == GOOGLE && account.isNotEmpty() && owner.equals(account, ignoreCase = true)) out[c.getLong(0)] = account
            }
        }
        return out
    }

    /** Events in your own calendars that count (see [TaskRules.keepEvent]). */
    private fun readCalendar(start: Long, end: Long, zone: ZoneId): List<CalendarEvent> {
        val own = ownCalendars()
        if (own.isEmpty()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.ORGANIZER,
            CalendarContract.Instances.RRULE,
        )
        val out = ArrayList<CalendarEvent>()
        val where = "${CalendarContract.Instances.CALENDAR_ID} IN (${own.keys.joinToString(",")})"
        context.contentResolver.query(uri, projection, where, null, null)?.use { c ->
            while (c.moveToNext()) {
                val account = own[c.getLong(5)] ?: continue
                val title = c.getString(1)?.trim().orEmpty().ifEmpty { "(No title)" }
                val begin = c.getLong(2)
                val allDay = c.getInt(3) == 1
                if (!TaskRules.keepEvent(title, allDay, c.getString(7), c.getString(6).orEmpty(), account)) continue
                // All-day events are stored at midnight in UTC.
                val at = Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else zone)
                out += CalendarEvent(
                    key = "${c.getLong(0)}:$begin",
                    title = title,
                    detail = c.getString(4)?.trim().orEmpty(),
                    day = at.toLocalDate(),
                    time = if (allDay) null else at.hour * 60 + at.minute,
                )
            }
        }
        return out
    }

    // From Google Tasks

    private val google = GoogleTasks(context)

    private val _googleAccounts = MutableStateFlow(googleAccountSet().sorted())
    /** The Google accounts whose Google Tasks are read. */
    val googleAccounts: StateFlow<List<String>> = _googleAccounts

    private val _googleProblems = MutableStateFlow<Map<String, String>>(emptyMap())
    /** What went wrong reading an account's Google Tasks last time, by account. */
    val googleProblems: StateFlow<Map<String, String>> = _googleProblems

    private val _googleProblem = MutableStateFlow<String?>(null)
    /** Why adding an account didn't work, or null. */
    val googleProblem: StateFlow<String?> = _googleProblem

    private fun googleAccountSet(): Set<String> = sp.getStringSet(KEY_GOOGLE_ACCOUNTS, null).orEmpty().toSet()

    /**
     * Adds [account]'s Google Tasks. Returns Google's screen when you need to agree first: show it, and
     * when you've said yes, call this again.
     */
    suspend fun connectGoogle(account: String): android.content.Intent? {
        val name = account.trim()
        if (name.isEmpty()) return null
        _googleProblem.value = null
        try {
            google.token(name)
        } catch (e: GoogleTasks.NeedsYou) {
            if (e.intent == null) _googleProblem.value = "Google couldn't show its sign-in screen. Try again."
            return e.intent
        } catch (e: GoogleTasks.Refused) {
            _googleProblem.value = GoogleTasks.refusedText(e.reason)
            return null
        } catch (e: IOException) {
            _googleProblem.value = "Couldn't reach Google. Check the internet and try again."
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Google sign-in failed", e)
            _googleProblem.value = "Couldn't sign in to Google (${e.javaClass.simpleName})."
            return null
        }
        val set = googleAccountSet() + name
        sp.edit().putStringSet(KEY_GOOGLE_ACCOUNTS, set).apply()
        _googleAccounts.value = set.sorted()
        _googleProblems.value = _googleProblems.value - name
        syncGoogleTasks()
        return null
    }

    /** Stops reading [account]'s Google Tasks; its tasks still to do go. */
    fun removeGoogle(account: String) {
        val set = googleAccountSet() - account
        sp.edit().putStringSet(KEY_GOOGLE_ACCOUNTS, set).apply()
        _googleAccounts.value = set.sorted()
        _googleProblems.value = _googleProblems.value - account
        scope.launch {
            lock.withLock {
                dao.bySource(TaskSource.GOOGLE_TASK).filter { !it.done && it.sourceKey.startsWith("$account:") }.forEach { dao.delete(it.id) }
            }
        }
    }

    /**
     * Your Google Tasks with a date, today or later: new ones are added, changed ones follow, and ones
     * finished or deleted in Google Tasks go. An account that couldn't be read keeps what it had.
     */
    suspend fun syncGoogleTasks() {
        val accounts = googleAccountSet()
        if (accounts.isEmpty()) return
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val problems = HashMap(_googleProblems.value)
        val read = HashMap<String, List<GoogleTasksJson.Item>>()
        for (a in accounts) {
            try {
                read[a] = google.read(a)
                problems -= a
            } catch (e: GoogleTasks.NeedsYou) {
                problems[a] = "Google needs you to sign in again."
            } catch (e: GoogleTasks.Refused) {
                problems[a] = GoogleTasks.refusedText(e.reason)
            } catch (e: IOException) {
                problems[a] = "Couldn't reach Google. It's tried again in 15 minutes."
            } catch (e: Exception) {
                Log.w(TAG, "Google Tasks read failed", e)
                problems[a] = "Couldn't read Google Tasks (${e.javaClass.simpleName})."
            }
        }
        _googleProblems.value = problems.filterKeys { it in accounts }
        if (read.isEmpty()) return
        lock.withLock {
            val had = dao.bySource(TaskSource.GOOGLE_TASK).associateBy { it.sourceKey }
            val keys = HashSet<String>()
            for ((account, items) in read) {
                for (g in items) {
                    val day = g.day ?: continue
                    if (day.isBefore(today)) continue
                    val key = "$account:${g.id}"
                    keys += key
                    val detail = listOf(g.list, g.notes.lineSequence().firstOrNull().orEmpty().take(200)).filter { it.isNotBlank() }.joinToString(": ")
                    val old = had[key]
                    if (old == null) {
                        dao.insert(TaskEntity(title = g.title, detail = detail, day = day.toString(), source = TaskSource.GOOGLE_TASK, sourceKey = key, accountId = null, createdAt = now))
                    } else if (!old.done && (old.title != g.title || old.detail != detail || old.day != day.toString())) {
                        dao.update(old.copy(title = g.title, detail = detail, day = day.toString(), remindedAt = if (old.day != day.toString()) null else old.remindedAt))
                    }
                }
            }
            // Finished, deleted or undated in Google Tasks: gone here too (only for accounts that were read).
            had.values.filter { t -> !t.done && t.sourceKey !in keys && read.keys.any { t.sourceKey.startsWith("$it:") } }.forEach { dao.delete(it.id) }
        }
    }

    // Telling you

    /**
     * Sends what's due: the evening summary of tomorrow, a reminder a day before anything with a time,
     * and a notice for a task that turned up after the summary that would have covered it.
     */
    suspend fun check() {
        lock.withLock {
            val now = ZonedDateTime.now()
            val nowMs = now.toInstant().toEpochMilli()
            dao.putAwayPast(now.toLocalDate().toString())
            val open = dao.open()
            val summaryAt = _summaryAt.value
            if (TaskRules.summaryDue(now, summaryAt, lastSummaryAt())) {
                val tomorrow = now.toLocalDate().plusDays(1)
                val list = open.filter { TaskRules.day(it.day) == tomorrow }
                if (list.isNotEmpty()) summary(list)
                sp.edit().putLong(KEY_LAST_SUMMARY, nowMs).apply()
            }
            val told = ArrayList<Long>()
            val lastSummary = lastSummaryAt()
            for (t in open) {
                val day = TaskRules.day(t.day) ?: continue
                when {
                    _ownReminders.value && TaskRules.ownReminderDue(day, t.time, t.remindedAt, now) -> {
                        tell(t, if (day == now.toLocalDate()) "Today" else "Tomorrow")
                        told += t.id
                    }
                    TaskRules.lateNoticeDue(day, t.time, t.createdAt, t.remindedAt, TaskSource.madeByYou(t.source), now, summaryAt, lastSummary) -> {
                        tell(t, if (day == now.toLocalDate()) "New for today" else "New for tomorrow")
                        told += t.id
                    }
                }
            }
            if (told.isNotEmpty()) dao.setReminded(told, nowMs)
            dao.trim(nowMs - KEEP_DONE_MS)
        }
    }

    private fun lastSummaryAt(): Long? = sp.getLong(KEY_LAST_SUMMARY, 0L).takeIf { it > 0 }

    private fun ensureChannel(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Tasks", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tomorrow's tasks each evening, and a reminder a day before anything with a time."
                },
            )
        }
        return true
    }

    private fun openTasks(code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, ROUTE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun summary(list: List<TaskEntity>) {
        if (!ensureChannel()) return
        val lines = list.sortedBy { it.time ?: -1 }.map { t ->
            (t.time?.let { TaskRules.clock(it) + "  " } ?: "") + t.title
        }
        val style = NotificationCompat.InboxStyle()
        lines.take(7).forEach { style.addLine(it) }
        if (lines.size > 7) style.setSummaryText("and ${lines.size - 7} more")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(if (list.size == 1) "Tomorrow: 1 thing" else "Tomorrow: ${list.size} things")
            .setContentText(lines.joinToString(", "))
            .setStyle(style)
            .setContentIntent(openTasks(SUMMARY_ID))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_ID, n) }
    }

    private fun tell(t: TaskEntity, heading: String) {
        if (!ensureChannel()) return
        val whenText = TaskRules.whenText(TaskRules.day(t.day), t.time, LocalDate.now())
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(t.title)
            .setContentText("$heading: $whenText")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf("$heading: $whenText", t.detail).filter { it.isNotBlank() }.joinToString("\n")))
            .setContentIntent(openTasks(TASK_ID_BASE + (t.id % 100_000).toInt()))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(TASK_ID_BASE + (t.id % 100_000).toInt(), n) }
    }

    companion object {
        /** Where the notifications open. */
        const val ROUTE = "tasks"
        const val CHANNEL = "tasks"
        private const val SUMMARY_ID = 7100
        private const val TASK_ID_BASE = 7200
        /** Emails per question on the look back: each goes in whole (as far as the agent reads one). */
        private const val BATCH = 5
        private const val LOOK_BACK_MAX = 600
        /** The look back runs again when its rules change. */
        private const val LOOK_BACK_VERSION = 2
        private const val KEY_LOOKED_BACK_VERSION = "looked_back_version"
        private const val KEEP_DONE_MS = 90L * 24 * 60 * 60_000L
        private const val KEY_EMAIL = "from_email"
        private const val KEY_CALENDAR = "from_calendar"
        private const val KEY_NOTES = "from_notes"
        private const val KEY_SUMMARY_AT = "summary_at"
        private const val KEY_OWN = "own_reminders"
        private const val KEY_LAST_SUMMARY = "last_summary"
        private const val KEY_LOOK_BACK = "look_back"
        private const val KEY_LOOKED_BACK = "looked_back"
        private const val KEY_CALENDAR_SYNCED = "calendar_synced"
        /** The calendar is read again from scratch when its rules change. */
        private const val CALENDAR_VERSION = 3
        private const val KEY_CALENDAR_VERSION = "calendar_version"
        private const val GOOGLE = "com.google"
        private const val KEY_GOOGLE_ACCOUNTS = "google_accounts"
        private const val TAG = "Tasks"
    }
}

/** Every 15 minutes: the calendar and Google Tasks read again, and anything due sent. */
class TaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val tasks = (applicationContext as BrokeApp).container.tasks
        runCatching { tasks.syncCalendar() }
        runCatching { tasks.syncGoogleTasks() }
        runCatching { tasks.check() }
        return Result.success()
    }

    companion object {
        private const val NAME = "tasks-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TaskWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
