package app.brokeometer.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** One payment read out of a bank alert. */
data class ParsedPayment(
    val amountMinor: Long,
    val currency: String,
    val descriptor: String,
    val merchantName: String,
    val merchantKey: String,
    val place: String,
    val account: String,
    /** The payment's own date when the message states it (bank SMS do), else null. */
    val day: java.time.LocalDate? = null,
    /** The bank's reference for this payment, used so the same one is never saved twice. */
    val reference: String? = null,
)

sealed interface ParseResult {
    data class Payment(val payment: ParsedPayment) : ParseResult
    data class Ignored(val reason: String) : ParseResult
}

/**
 * Reads HSBC UK alerts of the form
 *   title: "Money Out"
 *   text:  "£15.55 transaction | SUBWAY 58204-0    LONDON          GB | BANK A/C"
 *
 * Everything else ("Money In", "Payment attempt - action needed", 1p card checks) is ignored.
 */
object HsbcParser {

    private val amountPart =
        Regex("""^\s*(\D{0,4}?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s+transaction\s*$""", RegexOption.IGNORE_CASE)

    /** Card checks such as TfL's 1p authorisation are not real spending. */
    private const val SMALLEST_REAL_PAYMENT_MINOR = 2L

    fun parse(title: String?, text: String?): ParseResult {
        val heading = title?.trim().orEmpty()
        if (!heading.equals("Money Out", ignoreCase = true)) {
            return ParseResult.Ignored("Not a Money Out alert")
        }
        val parts = text.orEmpty().split('|')
        if (parts.size < 2) return ParseResult.Ignored("Wording not recognised")

        val match = amountPart.find(parts.first()) ?: return ParseResult.Ignored("No amount found")
        val currency = currencyFor(match.groupValues[1])
        val amountMinor = try {
            BigDecimal(match.groupValues[2].replace(",", ""))
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        } catch (e: ArithmeticException) {
            return ParseResult.Ignored("Amount not readable")
        } catch (e: NumberFormatException) {
            return ParseResult.Ignored("Amount not readable")
        }
        if (amountMinor < SMALLEST_REAL_PAYMENT_MINOR) return ParseResult.Ignored("Card check, not a payment")

        val rawDescriptor: String
        val account: String
        if (parts.size >= 3) {
            rawDescriptor = parts.subList(1, parts.size - 1).joinToString("|")
            account = parts.last().trim().replace(Regex("""\s+"""), " ")
        } else {
            rawDescriptor = parts[1]
            account = ""
        }
        if (rawDescriptor.isBlank()) return ParseResult.Ignored("No merchant found")

        val merchant = Merchants.fromDescriptor(rawDescriptor)
        return ParseResult.Payment(
            ParsedPayment(
                amountMinor = amountMinor,
                currency = currency,
                descriptor = rawDescriptor.trim().replace(Regex("""\s+"""), " "),
                merchantName = merchant.name,
                merchantKey = merchant.key,
                place = merchant.place,
                account = account,
            )
        )
    }

    private fun currencyFor(prefix: String): String {
        val p = prefix.trim()
        return when {
            p.isEmpty() || p == "£" || p.equals("GBP", ignoreCase = true) -> "GBP"
            p == "₹" || p.equals("INR", ignoreCase = true) || p.equals("Rs", ignoreCase = true) -> "INR"
            p == "€" || p.equals("EUR", ignoreCase = true) -> "EUR"
            p == "$" || p.equals("USD", ignoreCase = true) -> "USD"
            p.length == 3 && p.all { it.isLetter() } -> p.uppercase()
            else -> p.uppercase()
        }
    }
}
