package app.brokeometer.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailCodesTest {

    @Test
    fun findsCodes() {
        assertEquals("482913", MailCodes.find("Your verification code", "Your verification code is 482913. It expires in 10 minutes."))
        assertEquals("482913", MailCodes.find("G-482913 is your Google verification code", ""))
        assertEquals("482913", MailCodes.find("Microsoft account security code", "Please use the following security code for your account.\n\nSecurity code: 482913"))
        assertEquals("482913", MailCodes.find("Sign in to Example", "Your one-time password: 482 913"))
        assertEquals("7731", MailCodes.find("OTP", "7731 is your OTP for the transaction of Rs. 1,250.00 at AMAZON."))
        assertEquals("482913", MailCodes.find("Your code", "Hi Alex,\nYour login code is\n\n482913\n\nIf this wasn't you, call 0800 123 4567."))
    }

    @Test
    fun leavesOtherNumbersAlone() {
        assertNull(MailCodes.find("Your order 12345678 has shipped", "Thanks for your order. It arrives on 14 Oct 2026."))
        assertNull(MailCodes.find("Flash sale", "Use code SAVE20 for 20% off. Ends 23:59."))
        assertNull(MailCodes.find("Dinner on Sunday?", "Shall we meet at 7pm? Table for 4."))
        assertNull(MailCodes.find("Your bill is ready", "Your bill of £42.50 is ready. Account number: 12345678"))
        assertNull(MailCodes.find("New sign in to your account", "We noticed a new sign in on 11 Oct 2026 at 14:05 from Chrome on Windows."))
        assertNull(MailCodes.find("Your statement", "© 2026 HSBC UK Bank plc. Call us on 0345 740 4404."))
    }
}
