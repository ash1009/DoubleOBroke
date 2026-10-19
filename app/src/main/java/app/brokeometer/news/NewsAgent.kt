package app.brokeometer.news

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.brokeometer.BrokeApp
import app.brokeometer.R
import app.brokeometer.data.ClaudeClient
import app.brokeometer.ui.MainActivity
import app.brokeometer.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The news briefing: every couple of hours while you're awake (going by your screen time, so it
 * follows however you sleep), Claude searches the web for the topics you follow and writes a short
 * briefing of what's new since the last one. It shows as a notification and on the News page, and
 * every briefing is kept. The topics and the briefings stay on the phone; only the topics go to Claude.
 */
class NewsAgent(
    private val context: Context,
    private val claude: ClaudeClient,
    /** Writes a line in the alert log: the topics, and what happened. */
    private val log: suspend (text: String, outcome: String) -> Unit = { _, _ -> },
) {

    private val sp = context.getSharedPreferences("news", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "news.json")
    private val lock = Mutex()

    private val _on = MutableStateFlow(sp.getBoolean(KEY_ON, false))
    /** Whether the briefing comes each day. */
    val on: StateFlow<Boolean> = _on

    private val _topics = MutableStateFlow(sp.getString(KEY_TOPICS, "").orEmpty().split('\n').filter { it.isNotBlank() })
    /** The topics followed, in order. */
    val topics: StateFlow<List<String>> = _topics

    private val _every = MutableStateFlow(sp.getInt(KEY_EVERY, NewsRules.DEFAULT_EVERY).coerceIn(NewsRules.EVERY_HOURS))
    /** How often it comes while you're awake, in hours. */
    val every: StateFlow<Int> = _every

    /** When a briefing was last made or tried on its own, so a failed one isn't tried again straight away. */
    private var lastTriedAt: Long
        get() = sp.getLong(KEY_TRIED, 0L)
        set(value) = sp.edit().putLong(KEY_TRIED, value).apply()

    private val _quietAt = MutableStateFlow(sp.getLong(KEY_QUIET, 0L))
    /** The last time a briefing found nothing new (so nothing was saved or sent). */
    val quietAt: StateFlow<Long> = _quietAt

    private val _briefings = MutableStateFlow(read())
    /** The briefings kept, newest first. */
    val briefings: StateFlow<List<Briefing>> = _briefings

    private val _making = MutableStateFlow(false)
    val making: StateFlow<Boolean> = _making

    private val _problem = MutableStateFlow<String?>(sp.getString(KEY_PROBLEM, null))
    /** Why the last briefing couldn't be made, if it couldn't. */
    val problem: StateFlow<String?> = _problem

    fun setOn(value: Boolean) {
        sp.edit().putBoolean(KEY_ON, value).apply()
        _on.value = value
        NewsWorker.schedule(context)
    }

    fun setTopics(value: List<String>) {
        sp.edit().putString(KEY_TOPICS, value.joinToString("\n")).apply()
        _topics.value = value
    }

    fun setEvery(hours: Int) {
        sp.edit().putInt(KEY_EVERY, hours.coerceIn(NewsRules.EVERY_HOURS)).apply()
        _every.value = hours.coerceIn(NewsRules.EVERY_HOURS)
    }

    /** The latest briefing's time, or 0. */
    private fun lastAt(): Long = _briefings.value.firstOrNull()?.at ?: 0L

    /** About when the next one comes (if you're awake then), or null when off or never made. */
    fun nextAround(): Long? = if (!_on.value || _briefings.value.isEmpty()) null else maxOf(lastAt(), lastTriedAt) + _every.value * 3_600_000L

    /** Whether one is due now, given whether you're [awake]. */
    fun due(awake: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        _on.value && _topics.value.isNotEmpty() && NewsRules.due(now, maxOf(lastAt(), lastTriedAt), _every.value, awake)

    /**
     * Makes a briefing now, of what's new since the last one (or the last day). With [notify], a
     * notification says it's ready; with nothing new, nothing is saved or sent. True when it worked.
     */
    suspend fun make(notify: Boolean): Boolean = lock.withLock {
        val topics = _topics.value
        if (topics.isEmpty()) {
            note("Pick at least one topic first.")
            return@withLock false
        }
        _making.value = true
        val now = System.currentTimeMillis()
        if (notify) lastTriedAt = now
        try {
            val last = lastAt()
            val since = if (last > 0 && now - last < 24 * 3_600_000L) sinceText(last, now) else null
            when (val reply = claude.askWithSearch(NewsRules.SYSTEM, NewsRules.prompt(topics, LocalDate.now(NewsRules.LONDON), since))) {
                is ClaudeClient.Reply.Failed -> {
                    note(reply.message)
                    logged("News: couldn't make one. ${reply.message}")
                    false
                }
                is ClaudeClient.Reply.Text -> {
                    val briefing = parse(reply.text, now)
                    when {
                        briefing == null -> {
                            note("Claude's answer couldn't be read. Try again.")
                            logged("News: Claude's answer couldn't be read")
                            false
                        }
                        briefing.sections.all { it.stories.isEmpty() } -> {
                            // Nothing new since the last one: nothing to keep or send.
                            note(null)
                            sp.edit().putLong(KEY_QUIET, now).apply()
                            _quietAt.value = now
                            logged("News: nothing new since the last one, nothing sent")
                            true
                        }
                        else -> {
                            save(listOf(briefing) + _briefings.value)
                            note(null)
                            if (notify) notifyReady(briefing)
                            val n = NewsRules.count(briefing)
                            logged("News: briefing ready, $n ${if (n == 1) "story" else "stories"}" + if (notify) ", notification sent" else "")
                            true
                        }
                    }
                }
            }
        } finally {
            _making.value = false
        }
    }

    /** "10:02 today" or "23:40 yesterday" or "18:05 on 17 October". */
    private fun sinceText(at: Long, now: Long): String {
        val zone = NewsRules.LONDON
        val d = java.time.Instant.ofEpochMilli(at).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = NewsRules.clock(d.hour * 60 + d.minute)
        return when (d.toLocalDate()) {
            today -> "$clock today"
            today.minusDays(1) -> "$clock yesterday"
            else -> "$clock on ${d.toLocalDate().dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)}"
        }
    }

    private suspend fun logged(outcome: String) {
        runCatching { log(_topics.value.joinToString(", "), outcome) }
    }

    private fun note(message: String?) {
        sp.edit().putString(KEY_PROBLEM, message).apply()
        _problem.value = message
    }

    private fun save(list: List<Briefing>) {
        val kept = list.take(NewsRules.KEEP)
        val arr = JSONArray()
        kept.forEach { arr.put(toJson(it)) }
        runCatching { file.writeText(arr.toString()) }
        _briefings.value = kept
    }

    private fun read(): List<Briefing> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i -> fromJson(arr.getJSONObject(i), arr.getJSONObject(i).optLong("at")) }
    }.getOrDefault(emptyList())

    private fun notifyReady(b: Briefing) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "News briefing", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Your daily news briefing, once it's ready."
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 5101,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.NEWS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lines = NewsRules.headlines(b)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle("Your news briefing")
            .setContentText(lines.firstOrNull() ?: "Ready to read.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }

    companion object {
        const val CHANNEL = "news"
        private const val NOTIFICATION_ID = 5100
        private const val KEY_ON = "on"
        private const val KEY_TOPICS = "topics"
        private const val KEY_EVERY = "every_hours"
        private const val KEY_TRIED = "tried_at"
        private const val KEY_QUIET = "quiet_at"
        private const val KEY_PROBLEM = "problem"

        /** A briefing from Claude's answer, or null when there's no JSON in it that reads. */
        fun parse(text: String, at: Long): Briefing? {
            val json = NewsRules.jsonPart(text) ?: return null
            return runCatching { fromJson(JSONObject(json), at) }.getOrNull()
        }

        fun fromJson(o: JSONObject, at: Long): Briefing? {
            val sections = o.optJSONArray("sections") ?: return null
            return Briefing(
                at = at,
                sections = (0 until sections.length()).mapNotNull { i ->
                    val s = sections.optJSONObject(i) ?: return@mapNotNull null
                    val stories = s.optJSONArray("stories") ?: JSONArray()
                    Section(
                        topic = s.optString("topic"),
                        stories = (0 until stories.length()).mapNotNull { j ->
                            val st = stories.optJSONObject(j) ?: return@mapNotNull null
                            Story(st.optString("headline"), st.optString("summary"), st.optString("source"), st.optString("url"))
                                .takeIf { it.headline.isNotBlank() }
                        },
                    )
                },
            )
        }

        fun toJson(b: Briefing): JSONObject = JSONObject()
            .put("at", b.at)
            .put("sections", JSONArray().apply {
                b.sections.forEach { s ->
                    put(JSONObject().put("topic", s.topic).put("stories", JSONArray().apply {
                        s.stories.forEach { st ->
                            put(JSONObject().put("headline", st.headline).put("summary", st.summary).put("source", st.source).put("url", st.url))
                        }
                    }))
                }
            })
    }
}

/**
 * Every quarter of an hour (Android's shortest repeat): when you're awake and one is due, makes a
 * briefing. Awake means a few minutes of screen time in the last half hour, read from the phone's
 * own record (the Usage access App limits uses). Without that access, a screen that's on counts.
 */
class NewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as BrokeApp).container
        val news = container.news
        if (!news.on.value) return Result.success()
        val awake = withContext(Dispatchers.IO) { awake(container.usage) }
        if (!news.due(awake)) return Result.success()
        withContext(Dispatchers.IO) { news.make(notify = true) }
        return Result.success()
    }

    private fun awake(usage: app.brokeometer.limits.UsageReader): Boolean {
        if (!usage.allowed()) {
            return applicationContext.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        }
        val now = System.currentTimeMillis()
        val from = now - NewsRules.AWAKE_WINDOW_MS
        // Read from a while before, so an app already open at the start of the half hour counts too.
        val events = usage.events(from - 3 * 3_600_000L, now)
        val used = runCatching { app.brokeometer.limits.UsageMath.foreground(events, from, now).values.sum() }.getOrDefault(0L)
        return NewsRules.awake(used)
    }

    companion object {
        private const val NAME = "news-briefing"
        private const val CHECK = "news-briefing-check"

        /** Checks every 15 minutes while the briefing is on (needing the internet); none while it's off. */
        fun schedule(context: Context) {
            val news = (context.applicationContext as BrokeApp).container.news
            val work = WorkManager.getInstance(context)
            // The once-a-day one from before.
            work.cancelUniqueWork(NAME)
            if (!news.on.value) {
                work.cancelUniqueWork(CHECK)
                return
            }
            val request = PeriodicWorkRequestBuilder<NewsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(CHECK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
