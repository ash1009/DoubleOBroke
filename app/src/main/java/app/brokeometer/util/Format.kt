package app.brokeometer.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Money {

    private val symbols = DecimalFormatSymbols(Locale.UK)
    private val withPence = DecimalFormat("#,##0.00", symbols)
    private val whole = DecimalFormat("#,##0", symbols)

    fun symbol(currency: String): String = when (currency) {
        "GBP" -> "£"
        "INR" -> "₹"
        "EUR" -> "€"
        "USD" -> "$"
        else -> "$currency "
    }

    /** "£1,234.50" */
    fun format(amountMinor: Long, currency: String = "GBP"): String =
        symbol(currency) + withPence.format(BigDecimal.valueOf(amountMinor, 2))

    /** "£1,235", for places where pence would be noise. */
    fun formatWhole(amountMinor: Long, currency: String = "GBP"): String =
        symbol(currency) + whole.format(BigDecimal.valueOf(amountMinor, 2).setScale(0, RoundingMode.HALF_UP))

    /** "12.5" becomes 1250. Returns null when the text is not a positive amount. */
    fun parseToMinor(text: String): Long? {
        val cleaned = text.trim().replace(",", "").removePrefix("£").trim()
        if (cleaned.isEmpty()) return null
        return try {
            val value = BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
            if (value > 0) value else null
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** 1250 becomes "12.50", for filling an amount box. */
    fun plain(amountMinor: Long): String = BigDecimal.valueOf(amountMinor, 2).toPlainString()
}

object Dates {

    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.UK)
    private val pretty = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    private val prettyShort = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)
    private val monthName = DateTimeFormatter.ofPattern("MMMM", Locale.UK)
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun dayOf(epochMillis: Long): String = dayFormat.format(localDate(epochMillis))

    fun localDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    fun parseDay(day: String): LocalDate = LocalDate.parse(day, dayFormat)

    fun toDay(date: LocalDate): String = dayFormat.format(date)

    /** "8 Oct 2026" */
    fun pretty(day: String): String = pretty.format(parseDay(day))

    fun pretty(date: LocalDate): String = pretty.format(date)

    /** "Thu 8 Oct" */
    fun prettyShort(day: String): String = prettyShort.format(parseDay(day))

    /** "October 2026" */
    fun monthTitle(month: YearMonth): String = monthTitle.format(month.atDay(1))

    /** "October" */
    fun monthName(month: YearMonth): String = monthName.format(month.atDay(1))

    /** "15:30" */
    fun clock(epochMillis: Long): String = clock.format(Instant.ofEpochMilli(epochMillis).atZone(zone()))

    fun hourOf(epochMillis: Long): Int = Instant.ofEpochMilli(epochMillis).atZone(zone()).hour

    fun minuteOf(epochMillis: Long): Int = Instant.ofEpochMilli(epochMillis).atZone(zone()).minute

    /** Midday on the given date, used when the user picks a date without a time. */
    fun middayMillis(date: LocalDate): Long = date.atTime(12, 0).atZone(zone()).toInstant().toEpochMilli()
}
