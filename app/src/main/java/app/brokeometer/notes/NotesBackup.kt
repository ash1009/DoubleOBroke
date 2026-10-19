package app.brokeometer.notes

import org.json.JSONArray
import org.json.JSONObject

/**
 * Notes in the app's backup file: written as a list, and put back only where the phone doesn't have them.
 * Notes in the Bin, background photos and voice recordings stay on the phone.
 */
object NotesBackup {

    fun write(notes: List<NoteEntity>): String = JSONArray().apply {
        notes.filter { !it.isBlank && it.deletedAt == null }.forEach { n ->
            put(
                JSONObject()
                    .put("title", n.title)
                    .put("body", n.body)
                    .put("items", n.items)
                    .put("checklist", n.checklist)
                    .put("color", n.color)
                    .put("background", n.background)
                    .put("pinned", n.pinned)
                    .put("createdAt", n.createdAt)
                    .put("updatedAt", n.updatedAt)
                    .put("position", n.position)
                    .putOpt("keepKey", n.keepKey),
            )
        }
    }.toString()

    fun read(text: String): List<NoteEntity> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NoteEntity(
                title = o.optString("title"),
                body = o.optString("body"),
                items = o.optString("items"),
                checklist = o.optBoolean("checklist"),
                color = o.optString("color", NoteColors.DEFAULT).takeIf { it in NoteColors.ALL } ?: NoteColors.DEFAULT,
                background = o.optString("background", NoteBackgrounds.NONE).takeIf { it in NoteBackgrounds.ALL } ?: NoteBackgrounds.NONE,
                pinned = o.optBoolean("pinned"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                position = o.optLong("position", 0L),
                keepKey = if (o.isNull("keepKey")) null else o.optString("keepKey"),
            )
        }
    }

    /** Puts back the notes the phone doesn't have (the same note is the same title, made at the same moment). How many. */
    suspend fun restore(dao: NotesDao, text: String): Int {
        val have = dao.all().map { it.createdAt to it.title }.toSet()
        var added = 0
        for (n in read(text)) {
            if (n.createdAt to n.title in have) continue
            if (dao.insert(n) > 0) added++
        }
        return added
    }
}
