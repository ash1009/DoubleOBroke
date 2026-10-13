package app.brokeometer.ui

import app.brokeometer.data.Catalog
import app.brokeometer.data.TransactionEntity
import app.brokeometer.data.TxSource
import app.brokeometer.domain.AwayDays
import app.brokeometer.insights.Allowance
import app.brokeometer.insights.Averages
import app.brokeometer.insights.averages
import app.brokeometer.insights.CategoryChange
import app.brokeometer.insights.DAYS_PER_MONTH
import app.brokeometer.insights.FxTable
import app.brokeometer.insights.Goals
import app.brokeometer.insights.History
import app.brokeometer.insights.MonthBar
import app.brokeometer.insights.NoSpend
import app.brokeometer.insights.Place
import app.brokeometer.insights.Regulars
import app.brokeometer.insights.Runway
import app.brokeometer.insights.Spend
import app.brokeometer.insights.WhenYouSpend
import app.brokeometer.insights.allowance
import app.brokeometer.insights.categoryChanges
import app.brokeometer.insights.lastDays
import app.brokeometer.insights.monthBars
import app.brokeometer.insights.noSpend
import app.brokeometer.insights.recapYears
import app.brokeometer.insights.regulars
import app.brokeometer.insights.runway
import app.brokeometer.insights.topPlaces
import app.brokeometer.insights.whenYouSpend
import app.brokeometer.util.Dates
import java.time.LocalDate
import kotlin.math.roundToLong

/** Everything the Insights page shows, worked out in one go away from the main thread. */
data class InsightsData(
    val history: History,
    val goals: Goals,
    val runway: Runway?,
    val allowance: Allowance?,
    val categories: List<CategoryChange>,
    val noSpend: NoSpend,
    val places: List<Place>,
    val whenYouSpend: WhenYouSpend,
    val regulars: Regulars,
    val averages: Averages,
    val months: List<MonthBar>,
    val years: List<Int>,
    /** A usual whole month in pence, or null before there is one. */
    val usualMonthMinor: Long?,
    /** Amounts in other currencies that have no exchange rate yet, by currency. */
    val unconverted: Map<String, Long>,
)

fun buildInsights(
    rows: List<TransactionEntity>,
    pending: List<TransactionEntity>,
    catalog: Catalog,
    fx: FxTable,
    away: AwayDays,
    today: LocalDate,
    goals: Goals,
    hiddenRegulars: Set<String> = emptySet(),
): InsightsData {
    val unconverted = HashMap<String, Long>()
    val spends = rows.mapNotNull { row ->
        val sub = catalog.sub(row.subcategoryId) ?: return@mapNotNull null
        val day = runCatching { Dates.parseDay(row.day) }.getOrNull() ?: return@mapNotNull null
        val gbp = fx.toGbpMinor(row.amountMinor, row.currency, day)
        if (gbp == null) {
            unconverted[row.currency] = (unconverted[row.currency] ?: 0) + row.amountMinor
            return@mapNotNull null
        }
        val timed = row.source == TxSource.BANK || row.source == TxSource.TEST
        Spend(
            day = day,
            gbpMinor = gbp,
            categoryId = sub.categoryId,
            subcategoryId = sub.id,
            merchantKey = row.merchantKey,
            merchantName = row.merchantName,
            hour = if (timed) Dates.hourOf(row.occurredAt) else null,
            occurredAt = row.occurredAt,
        )
    }
    val spendDays = (rows + pending).mapNotNull { runCatching { Dates.parseDay(it.day) }.getOrNull() }.toSet()
    val history = History(spends, away, today, spendDays)
    val run = runway(history, goals)
    return InsightsData(
        history = history,
        goals = goals,
        runway = run,
        allowance = allowance(history, goals, run),
        categories = categoryChanges(history, goals),
        noSpend = noSpend(history, goals),
        places = topPlaces(lastDays(history, 90)),
        whenYouSpend = whenYouSpend(history),
        regulars = regulars(history, hiddenRegulars),
        averages = averages(history),
        months = monthBars(history),
        years = recapYears(history),
        usualMonthMinor = history.usualDaily?.let { (it * DAYS_PER_MONTH).roundToLong() },
        unconverted = unconverted,
    )
}
