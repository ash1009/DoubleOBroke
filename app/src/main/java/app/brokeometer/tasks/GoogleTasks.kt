package app.brokeometer.tasks

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Google Tasks, read with your own Google sign-in on the phone (Google Play services keeps it, the app
 * keeps no password or key). Read only: nothing is changed in Google Tasks.
 */
class GoogleTasks(private val context: Context) {

    /** Google needs you to say yes first (or sign in again): [intent] shows you Google's screen. */
    class NeedsYou(val intent: Intent?) : Exception("Google needs you to agree")

    /** Google turned the app down; [reason] is Google's word for why. */
    class Refused(val reason: String) : Exception(reason)

    /** A token for [account]'s Google Tasks. Throws [NeedsYou], [Refused] or IOException. */
    suspend fun token(account: String): String = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.getToken(context, Account(account, GOOGLE), SCOPE)
        } catch (e: UserRecoverableAuthException) {
            throw NeedsYou(e.intent)
        } catch (e: GoogleAuthException) {
            throw Refused(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Everything still to do, in every one of [account]'s lists. */
    suspend fun read(account: String): List<GoogleTasksJson.Item> = withContext(Dispatchers.IO) {
        var token = token(account)
        var renewed = false
        fun get(url: String): JSONObject {
            while (true) {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 20_000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    val code = c.responseCode
                    if (code == 401 && !renewed) {
                        // The token ran out: a new one, once.
                        renewed = true
                        runCatching { GoogleAuthUtil.clearToken(context, token) }
                        token = try {
                            GoogleAuthUtil.getToken(context, Account(account, GOOGLE), SCOPE)
                        } catch (e: UserRecoverableAuthException) {
                            throw NeedsYou(e.intent)
                        } catch (e: GoogleAuthException) {
                            throw Refused(e.message ?: e.javaClass.simpleName)
                        }
                        continue
                    }
                    if (code !in 200..299) {
                        val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        throw Refused(GoogleTasksJson.error(body) ?: "HTTP $code")
                    }
                    return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    c.disconnect()
                }
            }
        }

        val out = ArrayList<GoogleTasksJson.Item>()
        var listPage: String? = null
        var lists = 0
        do {
            val page = get("$BASE/users/@me/lists?maxResults=100" + (listPage?.let { "&pageToken=" + enc(it) } ?: ""))
            for ((id, title) in GoogleTasksJson.lists(page)) {
                var taskPage: String? = null
                var pages = 0
                do {
                    val tasks = get("$BASE/lists/${enc(id)}/tasks?showCompleted=false&showHidden=false&maxResults=100" + (taskPage?.let { "&pageToken=" + enc(it) } ?: ""))
                    out += GoogleTasksJson.items(tasks, title)
                    taskPage = tasks.optString("nextPageToken").takeIf { it.isNotEmpty() }
                } while (taskPage != null && ++pages < MAX_PAGES)
            }
            listPage = page.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (listPage != null && ++lists < MAX_PAGES)
        out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val GOOGLE = "com.google"
        private const val SCOPE = "oauth2:https://www.googleapis.com/auth/tasks.readonly"
        private const val BASE = "https://tasks.googleapis.com/tasks/v1"
        private const val MAX_PAGES = 10

        /** What to tell you when Google turns the app down. */
        fun refusedText(reason: String): String = when {
            reason.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) || reason.contains("INVALID_AUDIENCE", ignoreCase = true) ->
                "Google doesn't know this app yet. The Google Cloud setup needs finishing (the Android client with the app's SHA-1)."
            reason.contains("has not been used", ignoreCase = true) || reason.contains("disabled", ignoreCase = true) ->
                "The Google Tasks API isn't turned on in Google Cloud yet."
            else -> "Google said no: $reason"
        }
    }
}

/** Google Tasks' answers, read with no Android in it. */
object GoogleTasksJson {

    data class Item(val id: String, val title: String, val notes: String, val day: LocalDate?, val list: String)

    /** Each list's id and name. */
    fun lists(o: JSONObject): List<Pair<String, String>> {
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val l = a.optJSONObject(i) ?: return@mapNotNull null
            val id = l.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to l.optString("title")
        }
    }

    /** The tasks still to do in one list. */
    fun items(o: JSONObject, list: String): List<Item> {
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (t.optString("status") == "completed" || t.optBoolean("deleted") || t.optBoolean("hidden")) return@mapNotNull null
            val title = t.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            Item(id, title, t.optString("notes").trim(), TaskRules.googleDue(t.optString("due")), list)
        }
    }

    /** Google's message in an error answer. */
    fun error(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
