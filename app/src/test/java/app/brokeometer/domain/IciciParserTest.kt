package app.brokeometer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IciciParserTest {

    private fun paid(text: String): ParsedPayment {
        val r = IciciParser.parse(text)
        assertTrue("expected a payment: $r", r is ParseResult.Payment)
        return (r as ParseResult.Payment).payment
    }

    private fun ignored(text: String) {
        assertTrue(IciciParser.parse(text) is ParseResult.Ignored)
    }

    @Test
    fun upiAutoPayDebit() {
        val p = paid(
            "Rs 1999.00 debited from ICICI Bank Savings Account XX123 on 25-Aug-26 towards OpenAI LLC for " +
                "UPI Mandate AutoPay Retrieval Ref No.100000000001"
        )
        assertEquals(199_900L, p.amountMinor)
        assertEquals("INR", p.currency)
        assertEquals("OpenAI LLC", p.merchantName)
        assertEquals("OPENAI LLC", p.merchantKey)
        assertEquals("ICICI XX123", p.account)
        assertEquals(LocalDate.of(2026, 8, 25), p.day)
        assertEquals("100000000001", p.reference)
    }

    @Test
    fun googlePlayInSeptember() {
        val p = paid(
            "Rs 210.00 debited from ICICI Bank Savings Account XX123 on 10-Sep-26 towards Google Play for UPI " +
                "Mandate AutoPay Retrieval Ref No.100000000002"
        )
        assertEquals(21_000L, p.amountMinor)
        assertEquals("Google Play", p.merchantName)
        assertEquals(LocalDate.of(2026, 9, 10), p.day)
    }

    @Test
    fun upiPaymentWithPayeeCredited() {
        val p = paid(
            "ICICI Bank Acct XX123 debited for Rs 4999.00 on 28-Sep-26; Airtel credited. UPI:100000000003. " +
                "Call 18002662 for dispute. SMS BLOCK 123 to 9215676766."
        )
        assertEquals(499_900L, p.amountMinor)
        assertEquals("Airtel", p.merchantName)
        assertEquals(LocalDate.of(2026, 9, 28), p.day)
        assertEquals("100000000003", p.reference)
    }

    @Test
    fun cardSpend() {
        val p = paid("INR 1,250.50 spent using ICICI Bank Card XX1234 on 05-Oct-26 on AMAZON PAY. Avl Limit: INR 50,000.")
        assertEquals(125_050L, p.amountMinor)
        assertEquals("ICICI XX1234", p.account)
    }

    @Test
    fun creditsRemindersAndMandatesAreIgnored() {
        ignored("Dear Customer, Acct XX123 is credited with Rs 5000.00 on 06-Sep-26 from SHARMA R K. UPI:100000000004-ICICI Bank.")
        ignored(
            "ICICI Bank SAVINGS Account XX123 will be debited for Rs 199.00 on 23-Sep-26 towards Autopay for NETFLIX COM, " +
                "Upi Mandate, Unique Mandate Number 0123456789abcdef0123456789abcdef@okicici"
        )
        ignored(
            "Your AutoPay mandate with ASPRESENTED is successfully created towards OpenAI LLC from 25-Aug-26 to 22-Aug-36 " +
                "for Rs 1999.00, RRN 100000000005-ICICI Bank."
        )
        ignored("123456 is the OTP for your ICICI Bank transaction. Do not share it.")
    }

    @Test
    fun senderCheck() {
        assertTrue(IciciParser.isIcici("AD-ICICIT-S", "anything"))
        assertTrue(!IciciParser.isIcici("Mum", "see you at 6"))
    }
}
