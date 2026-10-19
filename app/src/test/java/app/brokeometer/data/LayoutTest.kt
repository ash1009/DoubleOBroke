package app.brokeometer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutTest {

    @Test
    fun cardsKeepTheirOrderAndSwitches() {
        val cards = listOf(HomeCard("money"), HomeCard("limits", on = false), HomeCard("email"))
        val text = Layout.encodeCards(Layout.cleanCards(cards))
        assertEquals("money:1,limits:0,email:1,travel:1,news:1,notes:1,passwords:1", text)
        assertEquals(Layout.cleanCards(cards), Layout.decodeCards(text))
        assertNull(Layout.decodeCards(""))
    }

    @Test
    fun theBarTakesThreeAtMost() {
        val order = Layout.FEATURES
        val ticked = setOf("email", "money", "travel")
        assertEquals(ticked, Layout.tick(order, ticked, "news", true))
        assertEquals(setOf("email", "money", "news"), Layout.tick(order, ticked - "travel", "news", true))
        assertEquals(setOf("email", "money"), Layout.tick(order, ticked, "travel", false))
        assertEquals(setOf("email", "money", "travel"), Layout.cleanTicked(order, setOf("passwords", "email", "money", "travel")))
    }

    @Test
    fun theRestAreUnderMoreInTheSameOrder() {
        val order = listOf("notes", "email", "news", "money", "travel", "limits", "passwords")
        val ticked = setOf("email", "notes", "money")
        assertEquals(listOf("notes", "email", "money"), Layout.inBar(order, ticked))
        assertEquals(listOf("news", "travel", "limits", "passwords"), Layout.underMore(order, ticked))
    }

    @Test
    fun movingUpAndDown() {
        assertEquals(listOf("b", "a", "c"), Layout.move(listOf("a", "b", "c"), 1, -1))
        assertEquals(listOf("a", "b", "c"), Layout.move(listOf("a", "b", "c"), 0, -1))
    }

    @Test
    fun theOldSettingsCarryOver() {
        val (cards, order, ticked) = Layout.fromOld(listOf("money", "email", "limits", "travel"), listOf("notes", "limits", "passwords", "news"))
        assertEquals(listOf("money", "email", "limits", "travel", "news", "notes", "passwords"), cards.map { it.key })
        assertEquals(listOf("money", "email", "travel", "notes", "limits", "passwords", "news"), order)
        assertEquals(setOf("money", "email", "travel"), ticked)
    }
}
