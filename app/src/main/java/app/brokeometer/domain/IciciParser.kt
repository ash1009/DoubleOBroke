package app.brokeometer.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Reads ICICI Bank (India) SMS alerts. Only money going out is kept:
 *   "Rs 1999.00 debited from ICICI Bank Savings Account XX123 on 25-Aug-26 towards OpenAI LLC for UPI Mandate ..."
 *   "ICICI Bank Acct XX123 debited for Rs 4999.00 on 28-Sep-26; Airtel credited. UPI:627173404296. ..."
 *   "INR 500.00 spent using ICICI Bank Card XX1234 on 05-Oct-26 on AMAZON. ..."
 * Credits, "will be debited" reminders, mandate set-ups and one-time passwords are ignored.
 */
object IciciParser {

    private const val AMOUNT = """(?:Rs\.?|INR)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private const val DATE = """(\d{1,2}-[A-Za-z]{3}-\d{2,4})"""

    private val debitedFrom = Regex(
        """$AMOUNT\s+debited from ICICI Bank.*?(?:Account|Acct|A/c)\s*(XX\d+)\s+on\s+$DATE\s+(?:towards|to|for)\s+(.+?)(?:\s+for\s+|\.\s|\.$|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val acctDebited = Regex(
        """ICICI Bank\s+(?:Acct|Account|A/c)\s*(XX\d+)\s+(?:is\s+)?debited\s+(?:for|with|by)\s+$AMOUNT\s+on\s+$DATE\s*[;,.]?\s*(?:&\s*)?(.+?)\s+credited""",
        RegexOption.IGNORE_CASE,
    )
    private val cardSpent = Regex(
        """$AMOUNT\s+spent\s+(?:using|on)\s+ICICI Bank\s+(?:Credit\s+|Debit\s+)?Card\s*(XX\d+)\s+on\s+$DATE\s+(?:on|at)\s+(.+?)(?:\.\s|\.$|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val reference = Regex("""(?:Ref(?:erence)?\s*No\.?|UPI[:\s]|RRN\s*)\s*([0-9]{6,})""", RegexOption.IGNORE_CASE)

    // English month names as banks write them ("Sep", not "Sept"), in any case.
    private val dateFormats = listOf("d-MMM-yy", "d-MMM-yyyy").map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
    }

    fun isIcici(sender: String?, text: String?): Boolean =
        (sender?.contains("ICICI", ignoreCase = true) == true) || (text?.contains("ICICI Bank", ignoreCase = true) == true)

    fun parse(text: String?): ParseResult {
        val body = text?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
        if (body.isEmpty()) return ParseResult.Ignored("Empty message")
        val lower = body.lowercase(Locale.UK)
        when {
            "will be debited" in lower -> return ParseResult.Ignored("Reminder of a payment to come")
            "mandate" in lower && "created" in lower -> return ParseResult.Ignored("AutoPay set-up")
            "otp" in lower || "one time password" in lower -> return ParseResult.Ignored("One-time password")
        }

        debitedFrom.find(body)?.let { m ->
            return payment(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], body)
        }
        acctDebited.find(body)?.let { m ->
            return payment(m.groupValues[2], m.groupValues[1], m.groupValues[3], m.groupValues[4], body)
        }
        cardSpent.find(body)?.let { m ->
            return payment(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], body)
        }
        return if ("credited" in lower) ParseResult.Ignored("Money in") else ParseResult.Ignored("Not a payment out")
    }

    private fun payment(amountText: String, account: String, dateText: String, payee: String, body: String): ParseResult {
        val amount = try {
            BigDecimal(amountText.replace(",", "")).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: Exception) {
            return ParseResult.Ignored("Amount not readable")
        }
        if (amount <= 0) return ParseResult.Ignored("Zero amount")
        val day = parseDate(dateText)
        val cleanPayee = payee.trim().trimEnd('.', ';', ',').trim()
        val name = Merchants.fromDescriptor(cleanPayee).name.ifBlank { cleanPayee }
        val ref = reference.find(body)?.groupValues?.get(1)
        return ParseResult.Payment(
            ParsedPayment(
                amountMinor = amount,
                currency = "INR",
                descriptor = cleanPayee,
                merchantName = name,
                merchantKey = Merchants.keyOf(cleanPayee).ifBlank { cleanPayee.uppercase(Locale.UK) },
                place = "",
                account = "ICICI ${account.uppercase(Locale.UK)}",
                day = day,
                reference = ref,
            )
        )
    }

    private fun parseDate(text: String): LocalDate? {
        for (f in dateFormats) {
            try {
                return LocalDate.parse(text, f)
            } catch (e: DateTimeParseException) {
                // try the next format
            }
        }
        return null
    }
}
