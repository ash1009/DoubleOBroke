package app.brokeometer.limits

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Notes which Instagram screen App limits thinks you're on, with the names Instagram gives the parts
 * of that screen, so the guesses can be put right. Never what's written on the screen: no messages,
 * no names, only element names (like "row_thread_composer_edittext") and text box hints.
 * Kept on the phone; it only leaves when you share it.
 */
object Recorder {

    private const val KEEP = 300
    private var lastKey: String? = null

    private fun file(context: Context) = File(context.filesDir, "limits/instagram-screens.jsonl")

    @Synchronized
    fun add(context: Context, screen: InstaScreen, nodes: List<ScreenNode>, chatsOnly: Boolean) {
        val ids = nodes.mapNotNull { InstaScreens.short(it.id) }.distinct().sorted()
        val key = screen.name + ids.hashCode()
        if (key == lastKey) return
        lastKey = key
        val o = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("screen", screen.name)
            .put("chatsOnly", chatsOnly)
            .put("selected", JSONArray(nodes.filter { it.selected }.mapNotNull { InstaScreens.short(it.id) }))
            .put("boxes", JSONArray(nodes.filter { it.editable }.map { "${InstaScreens.short(it.id)}|${it.hint.orEmpty()}" }))
            .put("ids", JSONArray(ids.take(250)))
        val f = file(context)
        f.parentFile?.mkdirs()
        val lines = (if (f.exists()) f.readLines() else emptyList()) + o.toString()
        f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
    }

    fun count(context: Context): Int = file(context).takeIf { it.exists() }?.readLines()?.size ?: 0

    /** A copy to share, in the folder shared files go from. */
    fun export(context: Context): File? {
        val f = file(context)
        if (!f.exists()) return null
        val out = File(context.cacheDir, "exports/instagram-screens.json")
        out.parentFile?.mkdirs()
        val arr = JSONArray()
        f.readLines().filter { it.isNotBlank() }.forEach { line -> runCatching { arr.put(JSONObject(line)) } }
        out.writeText(JSONObject().put("app", app.brokeometer.BuildConfig.VERSION_NAME).put("screens", arr).toString(1))
        return out
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        lastKey = null
    }
}
