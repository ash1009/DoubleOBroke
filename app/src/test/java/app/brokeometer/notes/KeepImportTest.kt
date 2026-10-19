package app.brokeometer.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepImportTest {

    @Test
    fun readsAKeepNoteWithItsLook() {
        val json = """{"color":"YELLOW","isTrashed":false,"isPinned":true,"isArchived":false,"textContent":"Pack passport",
            "textContentHtml":"<p dir=\"ltr\">Pack <b>passport</b></p>","title":"Trip",
            "userEditedTimestampUsec":1700000000000000,"createdTimestampUsec":1690000000000000}"""
        val p = KeepImport.parse(json, now = 5)!!
        assertEquals("Trip", p.note.title)
        assertEquals("Pack **passport**", p.note.body)
        assertEquals("YELLOW", p.note.color)
        assertTrue(p.note.pinned)
        assertEquals(1_690_000_000_000L, p.note.createdAt)
        assertEquals(1_700_000_000_000L, p.note.updatedAt)
    }

    @Test
    fun readsAKeepList() {
        val json = """{"color":"DEFAULT","isTrashed":false,"isPinned":false,"isArchived":false,"title":"Shop",
            "listContent":[{"text":"milk","isChecked":true},{"text":"bread","isChecked":false}],"createdTimestampUsec":1}"""
        val p = KeepImport.parse(json, now = 5)!!
        assertTrue(p.note.checklist)
        assertEquals(listOf(NoteItem("milk", true), NoteItem("bread")), p.note.itemList)
    }

    @Test
    fun knowsTheBinAndTheArchiveAndOtherFiles() {
        val binned = KeepImport.parse("""{"title":"x","textContent":"y","isTrashed":true}""", 0)!!
        assertTrue(binned.trashed)
        val archived = KeepImport.parse("""{"title":"x","textContent":"y","isArchived":true}""", 0)!!
        assertTrue(archived.archived)
        assertNull(KeepImport.parse("""{"something":"else"}""", 0))
    }
}
