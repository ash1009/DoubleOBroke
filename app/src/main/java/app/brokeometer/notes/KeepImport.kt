package app.brokeometer.notes

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * Brings notes in from Google Keep, using the .zip that Google Takeout makes (Keep only is enough).
 * Each note there is a .json file in the Keep folder. Notes in Keep's bin, and archived ones, are
 * left out; one already brought in before isn't added again.
 */
object KeepImport {

    data class Parsed(val note: NoteEntity, val archived: Boolean, val trashed: Boolean)

    data class Result(val added: Int, val archived: Int, val already: Int, val notKeep: Boolean = false)

    /** One note from a Takeout .json file, or null when it isn't a Keep note. */
    fun parse(json: String, now: Long): Parsed? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!o.has("textContent") && !o.has("listContent") && !o.has("title")) return null
        val created = o.optLong("createdTimestampUsec", 0L) / 1000
        val edited = o.optLong("userEditedTimestampUsec", 0L) / 1000
        val title = o.optString("title")
        val html = o.optString("textContentHtml")
        val body = if (html.isNotBlank()) KeepHtml.toMarks(html) else o.optString("textContent")
        val list = o.optJSONArray("listContent")
        val items = if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            NoteItem(item.optString("text").replace('\n', ' '), item.optBoolean("isChecked"))
        }
        val color = o.optString("color").uppercase().takeIf { it in NoteColors.ALL } ?: NoteColors.DEFAULT
        val note = NoteEntity(
            title = title,
            body = if (list != null) "" else body,
            items = NoteText.encodeItems(items),
            checklist = list != null,
            color = color,
            pinned = o.optBoolean("isPinned"),
            createdAt = created.takeIf { it > 0 } ?: now,
            updatedAt = edited.takeIf { it > 0 } ?: created.takeIf { it > 0 } ?: now,
            keepKey = "keep|${o.optLong("createdTimestampUsec", 0L)}|${title.trim()}",
        )
        return Parsed(note, archived = o.optBoolean("isArchived"), trashed = o.optBoolean("isTrashed"))
    }

    /** Reads the Takeout .zip at [uri] and adds its Keep notes. */
    suspend fun import(context: Context, uri: Uri, dao: NotesDao): Result = withContext(Dispatchers.IO) {
        val have = dao.keepKeys().toSet()
        var added = 0
        var archived = 0
        var already = 0
        var seen = 0
        val now = System.currentTimeMillis()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || !name.endsWith(".json", ignoreCase = true) || !(name.startsWith("Keep/") || name.contains("/Keep/"))) continue
                    // Notes are small; anything huge isn't one.
                    if (entry.size > 5_000_000) continue
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    val parsed = parse(text, now) ?: continue
                    seen++
                    when {
                        parsed.trashed -> Unit
                        parsed.archived -> archived++
                        parsed.note.keepKey in have -> already++
                        parsed.note.isBlank -> Unit
                        else -> if (dao.insert(parsed.note) > 0) added++ else already++
                    }
                }
            }
        }
        Result(added, archived, already, notKeep = seen == 0)
    }
}
