package app.brokeometer.ui

import app.brokeometer.data.Catalog
import app.brokeometer.data.CategoryEntity
import app.brokeometer.data.DayTotal
import app.brokeometer.data.SubcategoryEntity
import app.brokeometer.data.TransactionEntity
import app.brokeometer.domain.AwayDays
import java.time.LocalDate
import java.time.YearMonth

const val HOME_CURRENCY = "GBP"

data class SubTotal(val sub: SubcategoryEntity, val totalMinor: Long, val entries: List<TransactionEntity>)

data class CategoryTotal(val category: CategoryEntity, val totalMinor: Long, val subs: List<SubTotal>)

data class MonthSummary(
    val month: YearMonth,
    /** The currency this month is shown in: the one most of its entries use. */
    val currency: String,
    /** Total in [currency]. Other currencies are listed in [otherCurrencies] until conversion is added. */
    val totalMinor: Long,
    val otherCurrencies: Map<String, Long>,
    val entryCount: Int,
    val byCategory: List<CategoryTotal>,
    /** Spend per day of the month, index 0 is the 1st. */
    val perDay: List<Long>,
    /** Zero-based days of the month spent away. */
    val awayIndexes: Set<Int>,
    /** Days of this month so far that were away and are left out of the average. */
    val awayDays: Int,
    /** Days of this month so far that count towards the average (not away). */
    val daysCounted: Int,
    val dailyAverageMinor: Long,
    /** Where the month ends up at the current pace. Null for past months and in the first days. */
    val projectedMinor: Long?,
)

fun summarise(
    entries: List<TransactionEntity>,
    catalog: Catalog,
    month: YearMonth,
    today: LocalDate,
    away: AwayDays = AwayDays.NONE,
): MonthSummary {
    val counts = entries.groupingBy { it.currency }.eachCount()
    val currency = counts.maxWithOrNull(
        compareBy<Map.Entry<String, Int>> { it.value }.thenBy { if (it.key == HOME_CURRENCY) 1 else 0 }
    )?.key ?: HOME_CURRENCY

    val main = entries.filter { it.currency == currency }
    val total = main.sumOf { it.amountMinor }
    val others = entries.filter { it.currency != currency }
        .groupBy { it.currency }
        .mapValues { (_, list) -> list.sumOf { it.amountMinor } }

    val length = month.lengthOfMonth()
    val perDay = MutableList(length) { 0L }
    for (entry in main) {
        val dayOfMonth = entry.day.substring(8, 10).toIntOrNull() ?: continue
        if (dayOfMonth in 1..length) perDay[dayOfMonth - 1] += entry.amountMinor
    }
    val awayIndexes = (1..length).filter { month.atDay(it) in away }.map { it - 1 }.toSet()

    val bySub = main.groupBy { it.subcategoryId }
    val byCategory = catalog.categories.mapNotNull { category ->
        val subs = catalog.subsOf(category.id).mapNotNull { sub ->
            val list = bySub[sub.id] ?: return@mapNotNull null
            SubTotal(sub, list.sumOf { it.amountMinor }, list)
        }
        if (subs.isEmpty()) null else CategoryTotal(category, subs.sumOf { it.totalMinor }, subs)
    }.sortedByDescending { it.totalMinor }

    val current = YearMonth.from(today)
    val elapsed = when {
        month == current -> today.dayOfMonth
        month.isBefore(current) -> length
        else -> 0
    }
    val awayDays = (0 until elapsed).count { it in awayIndexes }
    val daysCounted = elapsed - awayDays
    val countedSpend = (0 until elapsed).filter { it !in awayIndexes }.sumOf { perDay[it] }
    val average = if (daysCounted > 0) countedSpend / daysCounted else 0L
    val projected = if (month == current && daysCounted >= 3 && total > 0) {
        val daysLeft = (elapsed until length).count { it !in awayIndexes }
        total + average * daysLeft
    } else {
        null
    }
    return MonthSummary(
        month, currency, total, others, entries.size, byCategory, perDay, awayIndexes, awayDays, daysCounted,
        average, projected,
    )
}

/**
 * What a normal month costs in pounds. Takes the last six finished months with at least ten days at
 * home, finds the middle of their daily averages (trip days left out), and multiplies it by the days
 * of [current] that are not away. Null until there is at least one such month.
 */
fun usualMonthMinor(dayTotals: List<DayTotal>, current: YearMonth, away: AwayDays = AwayDays.NONE): Long? {
    val daily = dayTotals
        .filter { it.currency == HOME_CURRENCY && it.day < current.atDay(1).toString() }
        .groupBy { YearMonth.parse(it.day.substring(0, 7)) }
        .toSortedMap()
        .mapNotNull { (month, days) ->
            val atHome = month.lengthOfMonth() - away.countIn(month)
            if (atHome < MIN_DAYS_AT_HOME) return@mapNotNull null
            val spend = days.filter { LocalDate.parse(it.day) !in away }.sumOf { it.totalMinor }
            if (spend <= 0) null else spend.toDouble() / atHome
        }
        .takeLast(6)
        .sorted()
    if (daily.isEmpty()) return null
    val mid = daily.size / 2
    val median = if (daily.size % 2 == 1) daily[mid] else (daily[mid - 1] + daily[mid]) / 2
    val atHome = current.lengthOfMonth() - away.countIn(current)
    return Math.round(median * atHome)
}

private const val MIN_DAYS_AT_HOME = 10

enum class MeterZone(val word: String) {
    LOADED("Loaded"),
    COMFORTABLE("Comfortable"),
    CAREFUL("Careful"),
    BROKE("Broke"),
}

/** [level] is this month's spend as a share of a usual month. */
fun zoneFor(level: Float): MeterZone = when {
    level < 0.4f -> MeterZone.LOADED
    level < 0.7f -> MeterZone.COMFORTABLE
    level < 1.0f -> MeterZone.CAREFUL
    else -> MeterZone.BROKE
}
