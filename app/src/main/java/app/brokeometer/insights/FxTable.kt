package app.brokeometer.insights

import java.time.LocalDate
import java.util.TreeMap
import kotlin.math.roundToLong

/**
 * Exchange rates by day, as units of a currency per pound. Rates are published on working days
 * only, so a weekend uses the nearest working day before it (or after, at the very start).
 */
class FxTable(rates: Map<String, Map<LocalDate, Double>>) {

    private val byCurrency: Map<String, TreeMap<LocalDate, Double>> =
        rates.mapValues { (_, days) -> TreeMap(days.filterValues { it > 0 }) }

    fun unitsPerGbp(currency: String, day: LocalDate): Double? {
        if (currency == "GBP") return 1.0
        val days = byCurrency[currency] ?: return null
        days.floorEntry(day)?.takeIf { !it.key.isBefore(day.minusDays(MAX_GAP)) }?.let { return it.value }
        days.ceilingEntry(day)?.takeIf { !it.key.isAfter(day.plusDays(MAX_GAP)) }?.let { return it.value }
        return null
    }

    /** Pence for an amount in [currency] on [day], or null when there is no rate for that day. */
    fun toGbpMinor(amountMinor: Long, currency: String, day: LocalDate): Long? {
        val rate = unitsPerGbp(currency, day) ?: return null
        return (amountMinor / rate).roundToLong()
    }

    fun has(currency: String, day: LocalDate): Boolean = unitsPerGbp(currency, day) != null

    companion object {
        private const val MAX_GAP = 7L
        val EMPTY = FxTable(emptyMap())
    }
}
