package app.brokeometer.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRulesTest {

    private val day = 86_400_000L

    @Test
    fun theBinKeepsNotesForSevenDays() {
        assertFalse(NoteRules.binExpired(0, 6 * day))
        assertTrue(NoteRules.binExpired(0, 7 * day))
        assertEquals(7, NoteRules.daysLeft(0, 1000))
        assertEquals(1, NoteRules.daysLeft(0, 6 * day + 5))
    }

    @Test
    fun draggingMovesANoteToAnotherPlace() {
        assertEquals(listOf(2L, 3L, 1L), NoteRules.move(listOf(1L, 2L, 3L), 1L, 3L))
        assertEquals(listOf(3L, 1L, 2L), NoteRules.move(listOf(1L, 2L, 3L), 3L, 1L))
        assertEquals(listOf(1L, 2L), NoteRules.move(listOf(1L, 2L), 1L, 9L))
    }
}
