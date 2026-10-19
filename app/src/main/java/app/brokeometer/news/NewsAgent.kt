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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The news briefing: once a day, at the time you pick, Claude searches the web for the topics you
 * follow and writes a short briefing. It shows as a notification and on the News page. The topics
 * and the briefings stay on the phone; only the topics go to Claude.
 */
class NewsAgent(private val context: Context, private val claude: ClaudeClient) {

    private val sp = context.getSharedPreferences("news", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "news.json")
    private val lock = Mutex()

    private val _on = MutableStateFlow(sp.getBoolean(KEY_ON, false))
    /** Whether the briefing comes each day. */
    val on: StateFlow<Boolean> = _on

    private val _topics = MutableStateFlow(sp.getString(KEY_TOPICS, "").orEmpty().split('\n').filter { it.isNotBlank() })
    /** The topics followed, in order. */
    val topics: StateFlow<List<String>> = _topics

    private val _time = MutableStateFlow(sp.getInt(KEY_TIME, 8 * 60))
    /** When it comes, in minutes after midnight. */
    val time: StateFlow<Int> = _time

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

    fun setTime(minuteOfDay: Int) {
        sp.edit().putInt(KEY_TIME, minuteOfDay).apply()
        _time.value = minuteOfDay
        NewsWorker.schedule(context)
    }

    /** Makes a briefing now. With [notify], a notification says it's ready. True when one was made. */
    suspend fun make(notify: Boolean): Boolean = lock.withLock {
        val topics = _topics.value
        if (topics.isEmpty()) {
            note("Pick at least one topic first.")
            return@withLock false
        }
        _making.value = true
        try {
            when (val reply = claude.askWithSearch(NewsRules.SYSTEM, NewsRules.prompt(topics, LocalDate.now(NewsRules.LONDON)))) {
                is ClaudeClient.Reply.Failed -> {
                    note(reply.message)
                    false
                }
                is ClaudeClient.Reply.Text -> {
                    val briefing = parse(reply.text, System.currentTimeMillis())
                    if (briefing == null || briefing.sections.all { it.stories.isEmpty() }) {
                        note("Claude's answer couldn't be read. Try again.")
                        false
                    } else {
                        save(listOf(briefing) + _briefings.value)
                        note(null)
                        if (notify) notifyReady(briefing)
                        true
                    }
                }
            }
        } finally {
            _making.value = false
        }
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
        private const val KEY_TIME = "time"
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

/** Makes the day's briefing at the time picked, then sets up the next one. */
class NewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val news = (applicationContext as BrokeApp).container.news
        if (!news.on.value) return Result.success()
        val made = withContext(Dispatchers.IO) { news.make(notify = true) }
        if (!made && runAttemptCount < 2) return Result.retry()
        schedule(applicationContext, fromItself = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "news-briefing"

        /** The next briefing at the time picked, needing the internet. Cancelled when the briefing is off. */
        fun schedule(context: Context, fromItself: Boolean = false) {
            val news = (context.applicationContext as BrokeApp).container.news
            val work = WorkManager.getInstance(context)
            if (!news.on.value) {
                work.cancelUniqueWork(NAME)
                return
            }
            val now = ZonedDateTime.now()
            val next = NewsRules.nextRun(now, news.time.value)
            val request = OneTimeWorkRequestBuilder<NewsWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniqueWork(NAME, if (fromItself) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }
    }
}
