package app.brokeometer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks Google's Gemini (free tier) to file a payment from a shop the phone has never seen.
 * The key is typed into Settings and stays on the phone. Model names change often, so the app asks
 * Google which models the key can use and picks the lightest Flash one that works.
 */
class GeminiClient(private val prefs: Prefs) {

    sealed interface Reply {
        data class Text(val text: String, val model: String) : Reply
        data class Failed(val message: String) : Reply
    }

    val hasKey: Boolean get() = prefs.geminiKey.value.isNotBlank()

    suspend fun ask(prompt: String): Reply = withContext(Dispatchers.IO) {
        val key = prefs.geminiKey.value.trim()
        if (key.isEmpty()) return@withContext Reply.Failed("No Gemini key in Settings yet.")
        val remembered = prefs.geminiModel
        val candidates = buildList {
            if (remembered.isNotBlank()) add(remembered)
            try {
                addAll(listModels(key).filter { it != remembered })
            } catch (e: HttpError) {
                return@withContext Reply.Failed(e.friendly)
            } catch (e: IOException) {
                return@withContext Reply.Failed("No internet, so Gemini could not be reached.")
            }
        }
        if (candidates.isEmpty()) return@withContext Reply.Failed("This key has no Gemini Flash model it can use.")
        var last = "Gemini did not answer."
        for (model in candidates.take(5)) {
            try {
                val text = generate(key, model, prompt)
                prefs.geminiModel = model
                return@withContext Reply.Text(text, model)
            } catch (e: HttpError) {
                last = e.friendly
                // A bad key will not work with any model.
                if (e.code == 400 && e.body.contains("API key", ignoreCase = true)) break
                if (e.code == 401 || e.code == 403) break
            } catch (e: IOException) {
                return@withContext Reply.Failed("No internet, so Gemini could not be reached.")
            }
        }
        Reply.Failed(last)
    }

    /** Flash models this key can call, lightest and newest first. */
    private fun listModels(key: String): List<String> {
        val body = request("GET", "$BASE/models?pageSize=200", key, null)
        val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
        val names = (0 until models.length()).mapNotNull { i ->
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            val canGenerate = methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            val name = m.optString("name").removePrefix("models/")
            if (canGenerate && name.contains("flash") && SKIP.none { name.contains(it) }) name else null
        }
        return names.sortedWith(
            compareBy<String> { if (it.endsWith("-latest")) 0 else 1 }
                .thenBy { if (it.contains("lite")) 0 else 1 }
                .thenByDescending { version(it) }
        )
    }

    private fun version(name: String): Double =
        Regex("""gemini-(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun generate(key: String, model: String, prompt: String): String {
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0))
        val body = request("POST", "$BASE/models/$model:generateContent", key, payload.toString())
        val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: throw IOException("empty answer")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
    }

    private class HttpError(val code: Int, val body: String) : Exception("HTTP $code") {
        val friendly: String
            get() = when {
                code == 400 && body.contains("API key", ignoreCase = true) -> "That key is not valid. Copy it again from AI Studio."
                code == 401 || code == 403 -> "Google refused the key. Check it in AI Studio."
                code == 429 -> "Gemini's free limit is used up for now. It resets later."
                code == 404 -> "That Gemini model is not available."
                code >= 500 -> "Gemini is having problems. Try again later."
                else -> "Gemini said no (error $code)."
            }
    }

    private fun request(method: String, url: String, key: String, json: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("x-goog-api-key", key)
            c.setRequestProperty("Accept", "application/json")
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpError(code, body)
            return body
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        val SKIP = listOf("preview", "exp", "image", "tts", "audio", "live", "embedding", "thinking", "robotics", "computer", "native", "8b")
    }
}
