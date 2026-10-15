package app.brokeometer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Money in and transfers between your own accounts. The wording is the banks' own; the names and
 * references are made up.
 */
class MoneyInTest {

    private val owner = "Alex Morgan"
    private val minute = 60_000L

    private fun starling(text: String): ParsedMoneyIn {
        val r = StarlingParser.parse("Starling", text)
        assertTrue("Expected money in but got $r", r is StarlingResult.MoneyIn)
        return (r as StarlingResult.MoneyIn).money
    }

    @Test
    fun starlingMoneyIn() {
        val m = starling("You received £100.00 from MORGAN A (STARLING-k3pQ9xz)")
        assertEquals(10000L, m.amountMinor)
        assertEquals("GBP", m.currency)
        assertEquals("MORGAN A", m.from)
        assertEquals("STARLING-k3pQ9xz", m.reference)
        assertEquals("Starling", m.bank)
    }

    @Test
    fun starlingMoneyInWithoutReference() {
        val m = starling("You received £1,250.50 from SAM PATEL")
        assertEquals(125050L, m.amountMinor)
        assertEquals("SAM PATEL", m.from)
        assertEquals("", m.reference)
    }

    @Test
    fun starlingNoiseIsIgnored() {
        val r = StarlingParser.parse("Starling", "Online Banking is requesting permission to access your account. Tap to review.")
        assertTrue(r is StarlingResult.Ignored)
    }

    @Test
    fun hsbcMoneyIn() {
        val m = HsbcParser.parseMoneyIn("Money In", "£5,000.00 transaction | ADVICE CONFIRMS RBC07106EKPN8JR4  SOMEONE | BANK A/C")
        assertEquals(500000L, m!!.amountMinor)
        assertEquals("HSBC", m.bank)
        assertEquals("RBC07106EKPN8JR4", m.reference)
        assertEquals("BANK A/C", m.account)
    }

    @Test
    fun hsbcMoneyOutIsNotMoneyIn() {
        assertNull(HsbcParser.parseMoneyIn("Money Out", "£15.55 transaction | SUBWAY 58204-0    LONDON          GB | BANK A/C"))
    }

    @Test
    fun namesAndReferences() {
        val text = "Alex Morgan   STARLING-k3pQ9xz"
        assertEquals("ALEX MORGAN", MoneyText.nameOf(text))
        assertEquals(listOf("STARLINGK3PQ9XZ"), MoneyText.referencesOf(text))
        assertTrue(MoneyText.sharesReference(text, "MORGAN A STARLING-k3pQ9xz"))
        assertFalse(MoneyText.sharesReference("TESCO STORES 3021", "MORGAN A STARLING-k3pQ9xz"))
    }

    @Test
    fun ownerByFullNameOrSurnameAndInitial() {
        assertTrue(MoneyText.namesOwner("Alex Morgan   STARLING-k3pQ9xz", owner, emptySet()))
        assertTrue(MoneyText.namesOwner("MORGAN A (STARLING-k3pQ9xz)", owner, emptySet()))
        assertFalse(MoneyText.namesOwner("MORGAN J", owner, emptySet()))
        assertFalse(MoneyText.namesOwner("SAM PATEL", owner, emptySet()))
        // Only a first name in the profile: names learned from a transfer still work.
        assertFalse(MoneyText.namesOwner("MORGAN A", "Alex", emptySet()))
        assertTrue(MoneyText.namesOwner("MORGAN A", "Alex", setOf("MORGAN A")))
    }

    @Test
    fun transferBetweenOwnAccountsPairs() {
        val out = OutSide(10000, "GBP", 0, "HSBC", "Alex Morgan   STARLING-k3pQ9xz")
        val inn = InSide(10000, "GBP", minute, "Starling", "MORGAN A STARLING-k3pQ9xz")
        assertTrue(TransferMatch.isPair(out, inn, owner, emptySet()))
        assertTrue(TransferMatch.paymentWasTransfer(out, inn, owner, emptySet()))
        // Even with no name known at all, the shared reference proves it.
        assertTrue(TransferMatch.paymentWasTransfer(out, inn, "", emptySet()))
    }

    @Test
    fun sameAmountFromAFriendIsNotATransfer() {
        val shop = OutSide(2000, "GBP", 0, "HSBC", "TESCO STORES 3021    LONDON    GB")
        val friend = InSide(2000, "GBP", 5 * minute, "Starling", "SAM PATEL")
        assertFalse(TransferMatch.isPair(shop, friend, owner, emptySet()))
        assertFalse(TransferMatch.paymentWasTransfer(shop, friend, owner, emptySet()))
    }

    @Test
    fun moneyInFromYouDoesNotTurnAShopPaymentIntoATransfer() {
        // £20 at a shop, then £20 moved in from your other bank: the shop payment stays a payment.
        val shop = OutSide(2000, "GBP", 0, "HSBC", "TESCO STORES 3021    LONDON    GB")
        val fromYou = InSide(2000, "GBP", 5 * minute, "Starling", "MORGAN A")
        assertFalse(TransferMatch.paymentWasTransfer(shop, fromYou, owner, emptySet()))
    }

    @Test
    fun transferNeedsSameAmountDifferentBanksAndCloseInTime() {
        val out = OutSide(10000, "GBP", 0, "HSBC", "Alex Morgan   STARLING-k3pQ9xz")
        assertFalse(TransferMatch.isPair(out, InSide(9999, "GBP", minute, "Starling", "MORGAN A STARLING-k3pQ9xz"), owner, emptySet()))
        assertFalse(TransferMatch.isPair(out, InSide(10000, "GBP", minute, "HSBC", "MORGAN A STARLING-k3pQ9xz"), owner, emptySet()))
        assertFalse(TransferMatch.isPair(out, InSide(10000, "GBP", 31 * minute, "Starling", "MORGAN A STARLING-k3pQ9xz"), owner, emptySet()))
        assertTrue(TransferMatch.isPair(out, InSide(10000, "GBP", -29 * minute, "Starling", "MORGAN A STARLING-k3pQ9xz"), owner, emptySet()))
    }
}
