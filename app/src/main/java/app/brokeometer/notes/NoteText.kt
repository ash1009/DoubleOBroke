package app.brokeometer.notes

/** One ticked or unticked line of a checklist. */
data class NoteItem(val text: String, val checked: Boolean = false)

/** A piece of a note's text with its look, the marks taken out: for showing notes. [heading] is 1 to 3, or 0. */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val heading: Int = 0)

/**
 * The text of a note, with a few marks for its look: a line starting "# ", "## " or "### " is a heading
 * (big, medium or small), **words** are bold and *words* are italic. The marks stay in the text, so the note is plain text underneath;
 * the editor shows them faintly and everywhere else hides them.
 */
object NoteText {

    enum class Kind { BOLD, ITALIC, H1, H2, H3, MARK }

    /** A line's heading level from its start: 3 for "### ", 2 for "## ", 1 for "# ", else 0. */
    fun headingOf(line: String): Int = when {
        line.startsWith("### ") -> 3
        line.startsWith("## ") -> 2
        line.startsWith("# ") -> 1
        else -> 0
    }

    private fun prefix(level: Int): String = if (level == 0) "" else "#".repeat(level) + " "

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
            val level = headingOf(line)
            if (level > 0) {
                val m = level + 1
                out += Span(offset, offset + m, Kind.MARK)
                val kind = when (level) { 1 -> Kind.H1; 2 -> Kind.H2; else -> Kind.H3 }
                if (line.length > m) out += Span(offset + m, offset + line.length, kind)
                from = m
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
        val heading = IntArray(n)
        val mark = BooleanArray(n)
        for (s in spans(text)) {
            val end = s.end.coerceAtMost(n)
            when (s.kind) {
                Kind.BOLD -> for (i in s.start until end) bold[i] = true
                Kind.ITALIC -> for (i in s.start until end) italic[i] = true
                Kind.H1 -> for (i in s.start until end) heading[i] = 1
                Kind.H2 -> for (i in s.start until end) heading[i] = 2
                Kind.H3 -> for (i in s.start until end) heading[i] = 3
                Kind.MARK -> for (i in s.start until end) mark[i] = true
            }
        }
        val out = ArrayList<Run>()
        val sb = StringBuilder()
        var style: Triple<Boolean, Boolean, Int>? = null
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

    /**
     * Makes the line with the cursor a heading of [level] (1 big, 2 medium, 3 small), or a normal line
     * with 0. Picking the level it already has makes it normal again. Returns the new text and cursor.
     */
    fun setHeading(text: String, cursor: Int, level: Int): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        val lineStart = if (c == 0) 0 else text.lastIndexOf('\n', c - 1) + 1
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val had = headingOf(text.substring(lineStart, lineEnd))
        val oldPrefix = prefix(had)
        val newPrefix = prefix(if (had == level) 0 else level)
        val t = text.substring(0, lineStart) + newPrefix + text.substring(lineStart + oldPrefix.length)
        val moved = (c - oldPrefix.length).coerceAtLeast(lineStart) + newPrefix.length
        return t to moved.coerceIn(0, t.length)
    }

    /** The heading level of the line with the cursor. */
    fun headingAt(text: String, cursor: Int): Int {
        val c = cursor.coerceIn(0, text.length)
        val lineStart = if (c == 0) 0 else text.lastIndexOf('\n', c - 1) + 1
        return headingOf(text.substring(lineStart))
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
        s = s.replace(Regex("(?is)<h([1-6])[^>]*>(.*?)</h[1-6]>")) {
            val level = it.groupValues[1].toInt().coerceAtMost(3)
            "\n" + "#".repeat(level) + " " + it.groupValues[2].trim() + "\n"
        }
        s = s.replace(Regex("(?i)</?(b|strong)(\\s[^>]*)?>"), "**")
        s = s.replace(Regex("(?i)</?(i|em)(\\s[^>]*)?>"), "*")
        s = s.replace(Regex("(?i)</(p|div|li)>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")
        return s.replace(Regex("\n{3,}"), "\n\n").trim('\n')
    }
}

/** The rules of arranging notes and the Bin, with no Android in them. */
object NoteRules {
    /** Days a deleted note waits in the Bin. */
    const val BIN_DAYS = 7

    fun binExpired(deletedAt: Long, now: Long): Boolean = now - deletedAt >= BIN_DAYS * 86_400_000L

    /** A reminder's day ("2026-10-27"), or null. */
    fun dayOf(text: String?): java.time.LocalDate? = text?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

    /** Days left in the Bin, at least 1 while it's there. */
    fun daysLeft(deletedAt: Long, now: Long): Int =
        (BIN_DAYS - ((now - deletedAt) / 86_400_000L).toInt()).coerceIn(1, BIN_DAYS)

    /** [ids] with [from] moved to where [to] is. */
    fun move(ids: List<Long>, from: Long, to: Long): List<Long> {
        val i = ids.indexOf(from)
        val j = ids.indexOf(to)
        if (i < 0 || j < 0 || i == j) return ids
        val out = ids.toMutableList()
        out.add(j, out.removeAt(i))
        return out
    }
}
