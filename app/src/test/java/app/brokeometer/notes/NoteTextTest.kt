package app.brokeometer.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextTest {

    @Test
    fun marksBecomeBoldItalicAndHeadings() {
        val runs = NoteText.runs("# Shopping\nGet **milk** and *bread*")
        assertEquals(
            listOf(
                Run("Shopping", heading = true),
                Run("\nGet "),
                Run("milk", bold = true),
                Run(" and "),
                Run("bread", italic = true),
            ),
            runs,
        )
        assertEquals("Shopping\nGet milk and bread", NoteText.plain("# Shopping\nGet **milk** and *bread*"))
    }

    @Test
    fun aLoneStarStaysAsItIs() {
        assertEquals("5 * 3 = 15", NoteText.plain("5 * 3 = 15"))
    }

    @Test
    fun wrappingPutsMarksOnAndTakesThemOff() {
        val (bolded, sel) = NoteText.wrap("get milk", 4, 8, "**")
        assertEquals("get **milk**", bolded)
        assertEquals(6..10, sel)
        val (back, sel2) = NoteText.wrap(bolded, sel.first, sel.last, "**")
        assertEquals("get milk", back)
        assertEquals(4..8, sel2)
        assertEquals("a****b" to (3..3), NoteText.wrap("ab", 1, 1, "**"))
    }

    @Test
    fun headingsGoOnAndOffTheCursorsLine() {
        val (on, c) = NoteText.toggleHeading("one\ntwo", 5)
        assertEquals("one\n# two", on)
        assertEquals(7, c)
        assertEquals("one\ntwo" to 5, NoteText.toggleHeading(on, c))
    }

    @Test
    fun checklistsKeepTheirTicks() {
        val items = listOf(NoteItem("milk", true), NoteItem("bread"))
        val text = NoteText.encodeItems(items)
        assertEquals("[x] milk\n[ ] bread", text)
        assertEquals(items, NoteText.decodeItems(text))
        assertEquals(listOf(NoteItem("milk"), NoteItem("eggs")), NoteText.toItems("**milk**\n\neggs"))
        assertEquals("milk\nbread", NoteText.fromItems(items))
    }

    @Test
    fun searchLooksEverywhere() {
        assertTrue(NoteText.matches("MILK", "", "get **milk**", emptyList()))
        assertTrue(NoteText.matches("bread", "Shop", "", listOf(NoteItem("bread"))))
        assertFalse(NoteText.matches("eggs", "Shop", "milk", listOf(NoteItem("bread"))))
    }

    @Test
    fun keepFormattingTurnsIntoMarks() {
        assertEquals(
            "# Trip\nPack **passport** and *charger*\nDone",
            KeepHtml.toMarks("<h1>Trip</h1><p>Pack <b>passport</b> and <i>charger</i></p>Done"),
        )
        assertEquals("Tom & Jerry", KeepHtml.toMarks("Tom &amp; Jerry"))
    }
}
