package app.brokeometer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every alert here is copied from the owner's real HSBC notification history. */
class HsbcParserTest {

    private fun payment(title: String, text: String): ParsedPayment {
        val result = HsbcParser.parse(title, text)
        assertTrue("Expected a payment but got $result", result is ParseResult.Payment)
        return (result as ParseResult.Payment).payment
    }

    private fun ignored(title: String, text: String) {
        val result = HsbcParser.parse(title, text)
        assertTrue("Expected to be ignored but got $result", result is ParseResult.Ignored)
    }

    @Test
    fun cardPaymentInLondon() {
        val p = payment("Money Out", "£15.55 transaction | SUBWAY 58204-0    LONDON          GB | BANK A/C")
        assertEquals(1555L, p.amountMinor)
        assertEquals("GBP", p.currency)
        assertEquals("Subway", p.merchantName)
        assertEquals("SUBWAY", p.merchantKey)
        assertEquals("London", p.place)
        assertEquals("BANK A/C", p.account)
    }

    @Test
    fun merchantWithWebsiteAsPlace() {
        val p = payment("Money Out", "£10.70 transaction | TfL Travel Charge    TFL.gov.uk/CP   GB | BANK A/C")
        assertEquals(1070L, p.amountMinor)
        assertEquals("TfL Travel Charge", p.merchantName)
        assertEquals("TFL TRAVEL CHARGE", p.merchantKey)
        assertEquals("tfl.gov.uk/cp", p.place)
    }

    @Test
    fun doubleSpaceInsideTheNameIsNotASeparator() {
        val p = payment("Money Out", "£9.00 transaction | EE TOPUP  VESTA    EE.CO.UK        GB | BANK A/C")
        assertEquals("EE Topup Vesta", p.merchantName)
        assertEquals("EE TOPUP VESTA", p.merchantKey)
    }

    @Test
    fun referenceCodesAreDropped() {
        val facebook = payment("Money Out", "£20.18 transaction | FACEBK *SFMV59SEL4    London          GB | BANK A/C")
        assertEquals("FACEBK", facebook.merchantKey)
        val vending = payment("Money Out", "£3.00 transaction | NYX*GMGVending    London          GB | BANK A/C")
        assertEquals("NYX GMGVENDING", vending.merchantKey)
        assertEquals(300L, vending.amountMinor)
    }

    @Test
    fun paymentAbroadArrivesInPounds() {
        val p = payment(
            "Money Out",
            "£7.65 transaction | INTL CARD T3000862    PRESTO FARE/SRR8XS    TORONTO       ON CA | BANK A/C",
        )
        assertEquals(765L, p.amountMinor)
        assertEquals("GBP", p.currency)
        assertEquals("Presto Fare", p.merchantName)
        assertEquals("PRESTO FARE", p.merchantKey)
        assertEquals("Toronto", p.place)
    }

    @Test
    fun paymentAbroadWithReferenceBeforeTheName() {
        val p = payment(
            "Money Out",
            "£3.91 transaction | INTL CARD 07556747 1864_YYZ_6__Sundry    Mississauga  ON CA | BANK A/C",
        )
        assertEquals("YYZ SUNDRY", p.merchantKey)
        assertEquals("Mississauga", p.place)
    }

    @Test
    fun bankCharge() {
        val p = payment("Money Out", "£1.75 transaction | CHARGE          NON-STERLING    CASH FEE | BANK A/C")
        assertEquals(175L, p.amountMinor)
        assertEquals("Non-sterling cash fee", p.merchantName)
    }

    @Test
    fun thousandsSeparator() {
        val p = payment("Money Out", "£1,234.50 transaction | UBER *EATS    HELP.UBER.COM   GB | BANK A/C")
        assertEquals(123450L, p.amountMinor)
        assertEquals("UBER EATS", p.merchantKey)
        assertEquals("Uber Eats", p.merchantName)
    }

    @Test
    fun moneyInIsIgnored() {
        ignored("Money In", "£5,000.00 transaction | ADVICE CONFIRMS RBC00000EXAMPLE0  SOMEONE | BANK A/C")
    }

    @Test
    fun paymentAttemptIsIgnored() {
        ignored(
            "Payment attempt - action needed",
            "A payment of GBP9.00 to VESTA  *EE Top Up was attempted on your card ending 1234. " +
                "Tap to open the app and confirm if this was you.",
        )
    }

    @Test
    fun onePennyCardChecksAreIgnored() {
        ignored("Money Out", "£0.01 transaction | TfL Travel Charge    TFL.gov.uk/CP   GB | BANK A/C")
        ignored("Money Out", "£0.01 transaction | INTL CARD | BANK A/C")
    }

    @Test
    fun unknownWordingIsIgnoredNotCrashed() {
        ignored("Money Out", "hello")
        ignored("Money Out", "")
        assertTrue(HsbcParser.parse(null, null) is ParseResult.Ignored)
    }
}
