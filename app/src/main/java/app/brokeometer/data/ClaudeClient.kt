package app.brokeometer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anthropic's Claude, with the owner's own API key (from the Claude Console, paid for by the
 * monthly credits that come with a Max plan). The key stays on the phone.
 * Model names change, so the app asks which models the key can use: a Haiku for quick jobs and
 * a Sonnet (or the best there is) for bigger ones.
 */
class ClaudeClient(private val prefs: Prefs) {

    sealed interface Reply {
        data class Text(val text: String, val model: String) : Reply
        data class Failed(val message: String, val noCredit: Boolean = false) : Reply
    }

    val hasKey: Boolean get() = prefs.claudeKey.value.isNotBlank()

    /** [smart] picks the stronger model, for jobs such as building the email categories. */
    suspend fun ask(system: String, prompt: String, smart: Boolean = false, maxTokens: Int = 1024): Reply =
        call(smart) { key, model -> message(key, model, system, prompt, maxTokens) }

    /**
     * Like [ask] with the stronger model and Claude's web search on (at most [searches] searches),
     * for the news briefing. Each search costs a little on top of the words.
     */
    suspend fun askWithSearch(system: String, prompt: String, maxTokens: Int = 4000, searches: Int = 8): Reply =
        call(smart = true) { key, model -> searchMessage(key, model, system, prompt, maxTokens, searches) }

    private suspend fun call(smart: Boolean, send: (key: String, model: String) -> String): Reply =
        withContext(Dispatchers.IO) {
            val key = prefs.claudeKey.value.trim()
            if (key.isEmpty()) return@withContext Reply.Failed("No Claude key in the app yet.")
            val model = try {
                pickModel(key, smart)
            } catch (e: HttpError) {
                return@withContext Reply.Failed(e.friendly, e.noCredit)
            } catch (e: IOException) {
                return@withContext Reply.Failed("No internet, so Claude could not be reached.")
            } ?: return@withContext Reply.Failed("This key has no Claude model it can use.")
            var tries = 0
            while (true) {
                try {
                    return@withContext Reply.Text(send(key, model), model)
                } catch (e: HttpError) {
                    // Busy or rate limited: wait as long as Claude asks (up to a minute) and try again.
                    if ((e.code == 429 || e.code == 529 || e.code >= 500) && tries < 5) {
                        tries++
                        val wait = e.retryAfterSeconds?.coerceIn(1, 60)?.times(1000L) ?: (10_000L * tries)
                        Thread.sleep(wait)
                        continue
                    }
                    if (e.code == 404) prefs.claudeModels = ""
                    return@withContext Reply.Failed(e.friendly, e.noCredit)
                } catch (e: IOException) {
                    if (tries < 2) {
                        tries++
                        Thread.sleep(3_000)
                        continue
                    }
                    return@withContext Reply.Failed("No internet, so Claude could not be reached.")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Reply.Failed("Claude did not answer.")
        }

    private fun pickModel(key: String, smart: Boolean): String? {
        val remembered = prefs.claudeModels.split(',').filter { it.isNotBlank() }
        val models = remembered.ifEmpty {
            listModels(key).also { prefs.claudeModels = it.joinToString(",") }
        }
        // The list comes newest first.
        val haiku = models.firstOrNull { it.contains("haiku") }
        val sonnet = models.firstOrNull { it.contains("sonnet") }
        val opus = models.firstOrNull { it.contains("opus") }
        return if (smart) sonnet ?: opus ?: haiku ?: models.firstOrNull() else haiku ?: sonnet ?: models.firstOrNull()
    }

    private fun listModels(key: String): List<String> {
        val body = request("GET", "$BASE/models?limit=100", key, null)
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { data.getJSONObject(it).optString("id").takeIf { id -> id.startsWith("claude") } }
    }

    private fun message(key: String, model: String, system: String, prompt: String, maxTokens: Int): String {
        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val body = request("POST", "$BASE/messages", key, payload.toString())
        val content = JSONObject(body).optJSONArray("content") ?: throw IOException("empty answer")
        return (0 until content.length())
            .map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
    }

    /**
     * One answer with web search. A long search can pause part way ("pause_turn"); it is then sent
     * back as it is so Claude carries on. All the text Claude wrote is returned together.
     */
    private fun searchMessage(key: String, model: String, system: String, prompt: String, maxTokens: Int, searches: Int): String {
        val tool = JSONObject()
            .put("type", "web_search_20250305")
            .put("name", "web_search")
            .put("max_uses", searches)
            .put("user_location", JSONObject().put("type", "approximate").put("city", "London").put("country", "GB").put("timezone", "Europe/London"))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        val text = StringBuilder()
        repeat(4) {
            val payload = JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("system", system)
                .put("messages", messages)
                .put("tools", JSONArray().put(tool))
            // Searching takes a while: up to five minutes before giving up.
            val body = JSONObject(request("POST", "$BASE/messages", key, payload.toString(), readTimeoutMs = 300_000))
            val content = body.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") text.append(block.optString("text"))
            }
            if (body.optString("stop_reason") != "pause_turn") return text.toString()
            messages.put(JSONObject().put("role", "assistant").put("content", content))
        }
        return text.toString()
    }

    private class HttpError(val code: Int, val body: String, val retryAfterSeconds: Long? = null) : Exception("HTTP $code") {
        val noCredit: Boolean get() = body.contains("credit balance", ignoreCase = true)
        val friendly: String
            get() = when {
                noCredit -> "Your Claude API credits have run out."
                code == 401 -> "That Claude key is not valid. Copy it again from the Claude Console."
                code == 403 -> "Claude refused the key. Check it in the Claude Console."
                code == 429 -> "Claude says too many requests. Try again in a minute."
                code == 529 || code >= 500 -> "Claude is busy or having problems. Try again later."
                code == 404 -> "That Claude model is not available."
                code == 400 -> "Claude said: " + (runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull() ?: "error 400")
                else -> "Claude said no (error $code)."
            }
    }

    private fun request(method: String, url: String, key: String, json: String?, readTimeoutMs: Int = 120_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("Accept", "application/json")
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpError(code, body, c.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.toLong())
            return body
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val BASE = "https://api.anthropic.com/v1"
    }
}
