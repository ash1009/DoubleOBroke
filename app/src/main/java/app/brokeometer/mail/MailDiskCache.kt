package app.brokeometer.mail

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Emails opened or fetched ahead, kept as files in the app's own cache on the phone, so they open at
 * once next time. The oldest go once there are too many. Android may also clear the cache when space
 * runs low; they're fetched again then.
 */
internal class MailDiskCache(private val dir: File) {

    private val lock = Any()

    private fun file(id: Long) = File(dir, "$id.json")

    fun read(id: Long): FullEmail? = synchronized(lock) {
        val f = file(id)
        if (!f.exists()) return null
        runCatching {
            val o = JSONObject(f.readText())
            f.setLastModified(System.currentTimeMillis())
            FullEmail(
                fromName = o.optString("fromName"),
                fromAddress = o.optString("fromAddress"),
                replyTo = o.str("replyTo"),
                to = o.optString("to"),
                cc = o.optString("cc"),
                date = o.optLong("date"),
                subject = o.optString("subject"),
                html = o.str("html"),
                text = o.str("text"),
                attachments = o.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                messageId = o.str("messageId"),
                references = o.str("references"),
                loadsFromWeb = o.optBoolean("loadsFromWeb"),
            )
        }.getOrElse {
            f.delete()
            null
        }
    }

    fun write(id: Long, e: FullEmail) {
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                val o = JSONObject()
                    .put("fromName", e.fromName)
                    .put("fromAddress", e.fromAddress)
                    .putOpt("replyTo", e.replyTo)
                    .put("to", e.to)
                    .put("cc", e.cc)
                    .put("date", e.date)
                    .put("subject", e.subject)
                    .putOpt("html", e.html)
                    .putOpt("text", e.text)
                    .put("attachments", JSONArray(e.attachments))
                    .putOpt("messageId", e.messageId)
                    .putOpt("references", e.references)
                    .put("loadsFromWeb", e.loadsFromWeb)
                val tmp = File(dir, "$id.tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(file(id))) tmp.delete()
                trim()
            }
        }
    }

    /** Newest kept first; the rest go once there are more than [MAX_FILES] or [MAX_BYTES]. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i >= MAX_FILES || total > MAX_BYTES) f.delete()
        }
    }

    private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

    companion object {
        private const val MAX_FILES = 400
        private const val MAX_BYTES = 80L * 1024 * 1024
    }
}
