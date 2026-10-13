package app.brokeometer.vault

/**
 * Turns a note of passwords, or Google Password Manager's export, into vault entries. Runs only on
 * the phone; nothing here goes to any AI or server.
 */
object VaultImport {

    /**
     * Reads a note written like:
     *
     *     Spotify: myname
     *            : my password
     *     Home wifi password: secret@123
     *
     *     Yahoo: first.account
     *          : its password
     *
     *          : second.account
     *          : its password
     *          : 1234 (Passkey)
     *
     * A line "Name: value" starts a new entry; lines starting with ":" add values to it; an empty line
     * followed by more ":" lines starts another account for the same name. One value is a password on
     * its own; two are a username and a password; anything after that, or anything in brackets such
     * as "(Passkey)", goes in the note.
     */
    fun fromNote(text: String): List<VaultEntry> {
        val out = ArrayList<VaultEntry>()
        var title: String? = null
        var values = ArrayList<String>()
        var notes = ArrayList<String>()
        var gap = false

        fun flush() {
            val t = title ?: return
            if (values.isEmpty() && notes.isEmpty()) return
            val username = if (values.size >= 2) values[0] else ""
            val password = when {
                values.size >= 2 -> values[1]
                values.size == 1 -> values[0]
                else -> ""
            }
            val extra = values.drop(2) + notes
            out += VaultEntry(
                title = t,
                username = username,
                password = password,
                url = if (looksLikeUrl(t)) t else "",
                note = extra.joinToString("\n"),
            )
            values = ArrayList()
            notes = ArrayList()
        }

        fun add(value: String) {
            val v = value.trim()
            if (v.isEmpty()) return
            if (Regex("""\(.*\)\s*$""").containsMatchIn(v) && values.size >= 1) notes += v else values += v
        }

        for (raw in text.replace("\r", "").lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                gap = true
                continue
            }
            if (line.startsWith(":")) {
                if (title == null) continue
                // After an empty line, a ":" line starts another account under the same name.
                if (gap && values.isNotEmpty()) flush()
                add(line.removePrefix(":"))
            } else {
                flush()
                // In "https://site.com: name" the first colon belongs to the address.
                val sep = if (looksLikeUrl(line)) {
                    line.indexOf(": ", startIndex = (line.indexOf("://") + 3).coerceAtLeast(0)).takeIf { it > 0 }
                } else {
                    line.indexOf(':').takeIf { it > 0 }
                }
                if (sep != null) {
                    title = line.substring(0, sep).trim()
                    add(line.substring(sep + 1))
                } else {
                    title = line
                }
            }
            gap = false
        }
        flush()
        return out
    }

    private fun looksLikeUrl(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.")
    }

    /** What a file held, and the column names it had, to explain when nothing was found. */
    data class FileResult(val entries: List<VaultEntry>, val columns: List<String>)

    /**
     * Any export file: Google Password Manager's CSV, or the same opened in Sheets and saved again as
     * a spreadsheet (.xlsx) or with tabs (.tsv).
     */
    fun fromFile(bytes: ByteArray): FileResult {
        val rows = if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            Xlsx.rows(bytes)
        } else {
            val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            csv(text, delimiterOf(text))
        }
        return FileResult(fromRows(rows), rows.firstOrNull().orEmpty().map { it.trim() }.filter { it.isNotEmpty() })
    }

    /** Commas normally; tabs or semicolons when a spreadsheet app saved it that way. */
    fun delimiterOf(text: String): Char {
        val first = text.lineSequence().firstOrNull().orEmpty()
        val counts = listOf(',', '\t', ';').associateWith { d -> first.count { it == d } }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    /**
     * Reads Google Password Manager's export (also Chrome's): a CSV with name, url, username, password
     * and note columns. Column order is found from the header.
     */
    fun fromGoogleCsv(text: String): List<VaultEntry> {
        val clean = text.removePrefix("\uFEFF")
        return fromRows(csv(clean, delimiterOf(clean)))
    }

    private fun fromRows(rows: List<List<String>>): List<VaultEntry> {
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { it in names }
        val name = col("name", "title")
        val url = col("url", "website", "login_uri")
        val user = col("username", "login", "login_username")
        val pass = col("password", "login_password")
        val note = col("note", "notes")
        if (pass < 0) return emptyList()
        return rows.drop(1).mapNotNull { r ->
            fun at(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            val password = if (pass < r.size) r[pass] else ""
            val site = at(url)
            val title = at(name).ifEmpty { hostOf(site) }.ifEmpty { site }
            if (title.isEmpty() && password.isEmpty()) return@mapNotNull null
            VaultEntry(title = title.ifEmpty { "Untitled" }, username = at(user), password = password, url = site, note = at(note))
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').removePrefix("www.")

    /** RFC 4180: commas (or [delimiter]), quotes doubled inside quotes, line breaks inside quotes. */
    fun csv(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> {
                    row += cell.toString()
                    cell.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += cell.toString()
                    cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    /** Entries not already in the vault. */
    fun newOnly(found: List<VaultEntry>, existing: List<VaultEntry>): List<VaultEntry> {
        val out = ArrayList<VaultEntry>()
        for (e in found) if (existing.none { it.sameAs(e) } && out.none { it.sameAs(e) }) out += e
        return out
    }
}
