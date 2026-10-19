package app.brokeometer.notes

/** One ticked or unticked line of a checklist. */
data class NoteItem(val text: String, val checked: Boolean = false)

/** A piece of a note's text with its look, the marks taken out: for showing notes. */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val heading: Boolean = false)

/**
 * The text of a note, with a few marks for its look: a line starting "# " is a heading, **words** are
 * bold and *words* are italic. The marks stay in the text, so the note is plain text underneath;
 * the editor shows them faintly and everywhere else hides them.
 */
object NoteText {

    enum class Kind { BOLD, ITALIC, HEADING, MARK }

    /** A stretch of the text, start included and end not. */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val ITALIC = Regex("""\*([^*\n]+?)\*""")

    /** Every styled stretch and every mark in [text]. */
    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var offset = 0
        for (line in text.split('\n')) {
            var from = 0
            if (line.startsWith("# ")) {
                out += Span(offset, offset + 2, Kind.MARK)
                if (line.length > 2) out += Span(offset + 2, offset + line.length, Kind.HEADING)
                from = 2
            }
            val body = line.substring(from)
            val base = offset + from
            // Bold first; its stars are blanked out so they aren't read again as italics.
            val masked = StringBuilder(body)
            for (m in BOLD.findAll(body)) {
                val s = m.range.first
                val e = m.range.last + 1
                out += Span(base + s, base + s + 2, Kind.MARK)
                out += Span(base + s + 2, base + e - 2, Kind.BOLD)
                out += Span(base + e - 2, base + e, Kind.MARK)
                for (i in s until e) if (masked[i] == '*') masked.setCharAt(i, ' ')
            }
            for (m in ITALIC.findAll(masked)) {
                val s = m.range.first
                val e = m.range.last + 1
                out += Span(base + s, base + s + 1, Kind.MARK)
                out += Span(base + s + 1, base + e - 1, Kind.ITALIC)
                out += Span(base + e - 1, base + e, Kind.MARK)
            }
            offset += line.length + 1
        }
        return out
    }

    /** The text as runs with their look and no marks, for showing a note. */
    fun runs(text: String): List<Run> {
        if (text.isEmpty()) return emptyList()
        val n = text.length
        val bold = BooleanArray(n)
        val italic = BooleanArray(n)
        val heading = BooleanArray(n)
        val mark = BooleanArray(n)
        for (s in spans(text)) {
            val arr = when (s.kind) {
                Kind.BOLD -> bold
                Kind.ITALIC -> italic
                Kind.HEADING -> heading
                Kind.MARK -> mark
            }
            for (i in s.start until s.end.coerceAtMost(n)) arr[i] = true
        }
        val out = ArrayList<Run>()
        val sb = StringBuilder()
        var style: Triple<Boolean, Boolean, Boolean>? = null
        for (i in 0 until n) {
            if (mark[i]) continue
            val st = Triple(bold[i], italic[i], heading[i])
            if (st != style && sb.isNotEmpty()) {
                out += Run(sb.toString(), style!!.first, style.second, style.third)
                sb.clear()
            }
            style = st
            sb.append(text[i])
        }
        if (sb.isNotEmpty()) out += Run(sb.toString(), style!!.first, style.second, style.third)
        return out
    }

    /** The words alone, without marks: for search and short previews. */
    fun plain(text: String): String = runs(text).joinToString("") { it.text }

    /**
     * Puts [mark] around the selection from [start] to [end], or takes it off when it's already
     * there. With nothing selected, two marks go in with the cursor between them. Returns the new
     * text and the new selection.
     */
    fun wrap(text: String, start: Int, end: Int, mark: String): Pair<String, IntRange> {
        val s = minOf(start, end).coerceIn(0, text.length)
        val e = maxOf(start, end).coerceIn(0, text.length)
        val m = mark.length
        if (s == e) return (text.substring(0, s) + mark + mark + text.substring(s)) to (s + m..s + m)
        val wrapped = s >= m && e + m <= text.length && text.substring(s - m, s) == mark && text.substring(e, e + m) == mark
        return if (wrapped) {
            (text.substring(0, s - m) + text.substring(s, e) + text.substring(e + m)) to (s - m..e - m)
        } else {
            (text.substring(0, s) + mark + text.substring(s, e) + mark + text.substring(e)) to (s + m..e + m)
        }
    }

    /** Makes the line with the cursor a heading, or a normal line again. Returns the new text and cursor. */
    fun toggleHeading(text: String, cursor: Int): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        val lineStart = if (c == 0) 0 else text.lastIndexOf('\n', c - 1) + 1
        return if (text.startsWith("# ", lineStart)) {
            (text.substring(0, lineStart) + text.substring(lineStart + 2)) to (c - 2).coerceAtLeast(lineStart)
        } else {
            (text.substring(0, lineStart) + "# " + text.substring(lineStart)) to c + 2
        }
    }

    /** A checklist kept as text: "[x] done" and "[ ] to do", one per line. */
    fun encodeItems(items: List<NoteItem>): String =
        items.joinToString("\n") { (if (it.checked) "[x] " else "[ ] ") + it.text.replace('\n', ' ') }

    fun decodeItems(text: String): List<NoteItem> =
        text.split('\n').filter { it.isNotEmpty() }.map { line ->
            when {
                line.startsWith("[x] ") || line.startsWith("[X] ") -> NoteItem(line.substring(4), true)
                line.startsWith("[ ] ") -> NoteItem(line.substring(4), false)
                else -> NoteItem(line, false)
            }
        }

    /** A note's lines as a checklist (Show checkboxes): each line that has words becomes an item. */
    fun toItems(text: String): List<NoteItem> = text.split('\n').map { plain(it).trim() }.filter { it.isNotEmpty() }.map { NoteItem(it) }

    /** A checklist back to plain lines (Hide checkboxes). */
    fun fromItems(items: List<NoteItem>): String = items.joinToString("\n") { it.text }

    /** Whether a note has [query] in its title, text or checklist, ignoring capitals. */
    fun matches(query: String, title: String, body: String, items: List<NoteItem>): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return title.contains(q, ignoreCase = true) || plain(body).contains(q, ignoreCase = true) ||
            items.any { it.text.contains(q, ignoreCase = true) }
    }
}

/**
 * Google Keep's own formatting (the HTML in a Google Takeout export) turned into the marks above.
 * Anything else is dropped, keeping the words.
 */
object KeepHtml {
    fun toMarks(html: String): String {
        var s = html.replace("\r", "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>")) { "\n# " + it.groupValues[1].trim() + "\n" }
        s = s.replace(Regex("(?i)</?(b|strong)(\\s[^>]*)?>"), "**")
        s = s.replace(Regex("(?i)</?(i|em)(\\s[^>]*)?>"), "*")
        s = s.replace(Regex("(?i)</(p|div|li)>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")
        return s.replace(Regex("\n{3,}"), "\n\n").trim('\n')
    }
}
