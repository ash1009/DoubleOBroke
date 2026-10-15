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
 * Everything else ("Payment attempt - action needed", 1p card checks) is ignored. "Money In" is read
 * separately by [parseMoneyIn], as money in, never as a payment.
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
        val amountMinor = minorOf(match.groupValues[2]) ?: return ParseResult.Ignored("Amount not readable")
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

    /**
     * Reads a "Money In" alert, which looks like Money Out:
     *   "£100.00 transaction | MORGAN A   STARLING-k3pQ9xz | BANK A/C"
     * The middle part names who sent it and usually carries their reference.
     */
    fun parseMoneyIn(title: String?, text: String?): ParsedMoneyIn? {
        if (!title?.trim().orEmpty().equals("Money In", ignoreCase = true)) return null
        val parts = text.orEmpty().split('|')
        if (parts.size < 2) return null
        val match = amountPart.find(parts.first()) ?: return null
        val amountMinor = minorOf(match.groupValues[2]) ?: return null
        if (amountMinor <= 0) return null
        val middle = if (parts.size >= 3) parts.subList(1, parts.size - 1).joinToString("|") else parts[1]
        val account = if (parts.size >= 3) parts.last().trim().replace(Regex("""\s+"""), " ") else ""
        val descriptor = middle.trim().replace(Regex("""\s+"""), " ")
        return ParsedMoneyIn(
            amountMinor = amountMinor,
            currency = currencyFor(match.groupValues[1]),
            from = MoneyText.nameOf(descriptor).ifBlank { descriptor },
            reference = MoneyText.referencesOf(descriptor).firstOrNull().orEmpty(),
            descriptor = descriptor,
            bank = BANK,
            account = account,
        )
    }

    const val BANK = "HSBC"

    /** "1,234.5" to 123450. Null when it isn't a number. */
    internal fun minorOf(number: String): Long? = try {
        BigDecimal(number.replace(",", ""))
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()
    } catch (e: ArithmeticException) {
        null
    } catch (e: NumberFormatException) {
        null
    }

    internal fun currencyFor(prefix: String): String {
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
