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
                Run("Shopping", heading = 1),
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
        val (on, c) = NoteText.setHeading("one\ntwo", 5, 1)
        assertEquals("one\n# two", on)
        assertEquals(7, c)
        assertEquals("one\ntwo" to 5, NoteText.setHeading(on, c, 1))
        // From big to small, and the levels read back.
        val (small, c2) = NoteText.setHeading(on, c, 3)
        assertEquals("one\n### two", small)
        assertEquals(9, c2)
        assertEquals(3, NoteText.headingAt(small, c2))
        assertEquals(listOf(Run("one\n"), Run("two", heading = 3)), NoteText.runs(small))
        assertEquals(listOf(Run("A", heading = 2)), NoteText.runs("## A"))
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
        assertEquals(
            "## Day one",
            KeepHtml.toMarks("<h2>Day one</h2>"),
        )
        assertEquals("Tom & Jerry", KeepHtml.toMarks("Tom &amp; Jerry"))
    }
}
