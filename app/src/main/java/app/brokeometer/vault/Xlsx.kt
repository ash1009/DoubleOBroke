package app.brokeometer.vault

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Just enough of the .xlsx format to read the first sheet's cells as text. */
object Xlsx {

    fun rows(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))) {
                    files[name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(it) }.orEmpty()
        val sheet = files.keys.filter { it.startsWith("xl/worksheets/sheet") }
            .minByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE } ?: return emptyList()
        return cells(files.getValue(sheet), shared)
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // No outside files or entities: this only ever reads plain cell text.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { isExpandEntityReferences = false }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val doc = parse(bytes)
        val items = doc.getElementsByTagName("si")
        return (0 until items.length).map { i ->
            val ts = (items.item(i) as Element).getElementsByTagName("t")
            (0 until ts.length).joinToString("") { ts.item(it).textContent.orEmpty() }
        }
    }

    private fun cells(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val doc = parse(bytes)
        val rows = doc.getElementsByTagName("row")
        val out = ArrayList<List<String>>()
        for (r in 0 until rows.length) {
            val row = rows.item(r) as Element
            val cells = row.getElementsByTagName("c")
            val values = ArrayList<String>()
            for (c in 0 until cells.length) {
                val cell = cells.item(c) as Element
                val col = column(cell.getAttribute("r")) ?: values.size
                val type = cell.getAttribute("t")
                val v = cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                val text = when (type) {
                    "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> cell.getElementsByTagName("t").let { ts -> (0 until ts.length).joinToString("") { ts.item(it).textContent.orEmpty() } }
                    else -> v
                }
                while (values.size < col) values += ""
                if (values.size == col) values += text else values[col] = text
            }
            if (values.any { it.isNotEmpty() }) out += values
        }
        return out
    }

    /** "C7" is column 2. */
    private fun column(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }
}
