package app.brokeometer.domain

import java.time.LocalDate
import java.time.YearMonth

/** Days spent away on trips. They are left out of daily averages and the usual month. */
class AwayDays(ranges: List<Pair<LocalDate, LocalDate>>) {

    private val ranges: List<ClosedRange<LocalDate>> =
        ranges.map { (a, b) -> if (a <= b) a..b else b..a }.sortedBy { it.start }

    operator fun contains(date: LocalDate): Boolean = ranges.any { date in it }

    /** Away days from [from] to [to], both included. */
    fun count(from: LocalDate, to: LocalDate): Int {
        if (to < from) return 0
        var n = 0
        var d = from
        while (d <= to) {
            if (d in this) n++
            d = d.plusDays(1)
        }
        return n
    }

    fun countIn(month: YearMonth): Int = count(month.atDay(1), month.atEndOfMonth())

    val isEmpty: Boolean get() = ranges.isEmpty()

    companion object {
        val NONE = AwayDays(emptyList())
    }
}
