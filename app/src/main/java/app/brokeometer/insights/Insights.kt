package app.brokeometer.insights

import app.brokeometer.domain.AwayDays
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One counted payment, already in pounds. */
data class Spend(
    val day: LocalDate,
    val gbpMinor: Long,
    val categoryId: Long,
    val subcategoryId: Long,
    /** Empty when the shop is not known, as with entries from the old sheet. */
    val merchantKey: String,
    val merchantName: String,
    /** Hour of day, or null when the time is not known (imported or typed in). */
    val hour: Int?,
    val occurredAt: Long,
    /** A fixed cost, like rent: there's no cutting back on it (see [FixedCosts]). */
    val fixed: Boolean = false,
)

/**
 * What can't be cut back, like rent: whole categories, or single sub-categories. Fixed costs still count
 * in totals, but are kept out of Where to cut back and the daily allowance.
 */
data class FixedCosts(val categories: Set<Long> = emptySet(), val subcategories: Set<Long> = emptySet()) {
    fun has(categoryId: Long, subcategoryId: Long): Boolean = categoryId in categories || subcategoryId in subcategories
}

/** What the owner has set on the Goals sheet. Everything is optional. */
data class Goals(
    /** Savings as typed in, in pence, and when it was typed. Spending after that comes off it. */
    val savingsMinor: Long? = null,
    val savingsAt: Long? = null,
    /** The day the savings need to last until. */
    val lastUntil: LocalDate? = null,
    val monthlyLimitMinor: Long? = null,
    val noSpendTarget: Int? = null,
    /** Monthly cap in pence, by category id. */
    val categoryCaps: Map<Long, Long> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = savingsMinor == null && lastUntil == null && monthlyLimitMinor == null &&
            noSpendTarget == null && categoryCaps.isEmpty()
}

const val DAYS_PER_MONTH = 30.44

/**
 * Everything the insights are worked out from. Months on a trip are fair: days away are left out
 * of every average, and a past month only counts as "usual" with at least ten days at home.
 */
class History(
    spends: List<Spend>,
    val away: AwayDays,
    val today: LocalDate,
    /** Days with any payment, in any currency, waiting or not. Used for no-spend days. */
    spendDays: Set<LocalDate> = spends.map { it.day }.toSet(),
) {
    val spends: List<Spend> = spends.sortedBy { it.occurredAt }
    val spendDays: Set<LocalDate> = spendDays
    val currentMonth: YearMonth = YearMonth.from(today)
    val firstDay: LocalDate? = (spends.map { it.day } + spendDays).minOrNull()

    val byMonth: Map<YearMonth, List<Spend>> = this.spends.groupBy { YearMonth.from(it.day) }

    fun homeDays(month: YearMonth): Int = month.lengthOfMonth() - away.countIn(month)

    /** Spend on days at home in a month. */
    fun homeSpend(month: YearMonth, filter: (Spend) -> Boolean = { true }): Long =
        byMonth[month].orEmpty().filter { it.day !in away && filter(it) }.sumOf { it.gbpMinor }

    /** The last six finished months with at least ten days at home and some spending. */
    val baseMonths: List<YearMonth> = byMonth.keys
        .filter { it < currentMonth && homeDays(it) >= MIN_HOME_DAYS && homeSpend(it) > 0 }
        .sorted()
        .takeLast(6)

    /** Middle daily spend of the base months, in pence. Null with no base months. */
    val usualDaily: Double? = median(baseMonths.map { homeSpend(it).toDouble() / homeDays(it) })

    fun usualDaily(filter: (Spend) -> Boolean): Double? =
        median(baseMonths.map { homeSpend(it, filter).toDouble() / homeDays(it) })

    /** Plain average daily spend over the base months (trip days left out), in pence. */
    fun averageDaily(filter: (Spend) -> Boolean): Double? =
        if (baseMonths.isEmpty()) null
        else baseMonths.map { homeSpend(it, filter).toDouble() / homeDays(it) }.average()

    // This month
    val thisMonth: List<Spend> = byMonth[currentMonth].orEmpty()
    val spentThisMonth: Long = thisMonth.sumOf { it.gbpMinor }
    /** Fixed costs this month (rent, say), part of [spentThisMonth]. */
    val fixedThisMonth: Long = thisMonth.filter { it.fixed }.sumOf { it.gbpMinor }
    /** Days at home so far this month, today included. */
    val homeDaysSoFar: Int = (1..today.dayOfMonth).count { currentMonth.atDay(it) !in away }
    /** Days at home from tomorrow to the end of the month. */
    val homeDaysAfterToday: Int =
        (today.dayOfMonth + 1..currentMonth.lengthOfMonth()).count { currentMonth.atDay(it) !in away }
    val dailyThisMonth: Double? =
        if (homeDaysSoFar >= 3) homeSpend(currentMonth).toDouble() / homeDaysSoFar else null

    companion object {
        const val MIN_HOME_DAYS = 10
    }
}

fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

// Runway

data class Runway(
    val savingsMinor: Long,
    val spentSinceMinor: Long,
    val leftMinor: Long,
    /** At a usual month's spending. Null until there is a usual month. */
    val monthsLeft: Double?,
    /** When the money runs out at this month's pace (or a usual pace early in the month). */
    val runsOutOn: LocalDate?,
    val lastUntil: LocalDate?,
    /** What can be spent each month, and each day, for the money to last until [lastUntil]. */
    val monthlyToLastMinor: Long?,
    val dailyToLastMinor: Long?,
    /**
     * When the money runs out if you spend a usual amount from now on. It stays put while you spend
     * as usual, moves later when you spend less and earlier when you spend more.
     */
    val lastsUntil: LocalDate? = null,
    /** Days ahead (positive) or behind (negative) a usual pace since the savings were entered. */
    val aheadDays: Double? = null,
)

/** Whole days ahead or behind; less than a day either way counts as on track. */
fun Runway.aheadWholeDays(): Int? = aheadDays?.let { if (abs(it) < 1.0) 0 else it.roundToInt() }

fun runway(history: History, goals: Goals, now: Long = System.currentTimeMillis()): Runway? {
    val savings = goals.savingsMinor ?: return null
    val since = goals.savingsAt ?: Long.MIN_VALUE
    val spent = history.spends.filter { it.occurredAt > since }.sumOf { it.gbpMinor }
    val left = savings - spent
    val usual = history.usualDaily
    val monthsLeft = usual?.takeIf { it > 0 }?.let { (left.coerceAtLeast(0) / (it * DAYS_PER_MONTH)) }
    val pace = history.dailyThisMonth ?: usual
    val runsOut = if (pace != null && pace > 0) {
        history.today.plusDays(ceil(left.coerceAtLeast(0) / pace).toLong())
    } else {
        null
    }
    val until = goals.lastUntil
    val daysToGo = until?.let { ChronoUnit.DAYS.between(history.today, it) }?.takeIf { it > 0 }
    val monthly = daysToGo?.let { (left.coerceAtLeast(0) / (it / DAYS_PER_MONTH)).roundToLong() }
    val daily = daysToGo?.let { left.coerceAtLeast(0) / it }
    val dayMs = 86_400_000.0
    val lastsUntil = usual?.takeIf { it > 0 }?.let { u ->
        val at = now + (left.coerceAtLeast(0) / u * dayMs).toLong()
        Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val ahead = if (usual != null && usual > 0 && goals.savingsAt != null) {
        val elapsedDays = (now - goals.savingsAt).coerceAtLeast(0) / dayMs
        elapsedDays - spent / usual
    } else {
        null
    }
    return Runway(savings, spent, left, monthsLeft, runsOut, until, monthly, daily, lastsUntil, ahead)
}

// Daily allowance

data class Allowance(
    val limitMinor: Long,
    /** True when the limit comes from "last until" rather than a monthly limit. */
    val fromLastUntil: Boolean,
    val spentMinor: Long,
    val leftMinor: Long,
    /** Days at home left this month, today included. */
    val daysLeft: Int,
    /** What can be spent each day from now on. Null when the limit is already used up. */
    val perDayMinor: Long?,
    /** Fixed costs paid this month (rent, say): not part of [spentMinor], as there's no cutting back on them. */
    val fixedMinor: Long = 0,
)

/** The month's limit shared over the days left. Only day-to-day spending counts against it, not fixed costs. */
fun allowance(history: History, goals: Goals, runway: Runway?): Allowance? {
    val fromGoal = goals.monthlyLimitMinor
    val limit = fromGoal ?: runway?.monthlyToLastMinor ?: return null
    val spent = history.spentThisMonth - history.fixedThisMonth
    val left = limit - spent
    val todayAtHome = if (history.today in history.away) 0 else 1
    val daysLeft = history.homeDaysAfterToday + todayAtHome
    val perDay = if (left > 0 && daysLeft > 0) left / daysLeft else null
    return Allowance(limit, fromGoal == null, spent, left, daysLeft, perDay, history.fixedThisMonth)
}

// Categories against a usual month

data class CategoryChange(
    val categoryId: Long,
    val thisMonthMinor: Long,
    /** What an average month has spent on this by now. */
    val usualByNowMinor: Long,
    /** An average whole month, with this month's days away taken off. */
    val usualMonthMinor: Long,
    val capMinor: Long?,
) {
    val changeMinor: Long get() = thisMonthMinor - usualByNowMinor
}

/**
 * Each category this month against its average, most over first. Fixed costs (rent, say) are left out,
 * as there's no cutting back on them: a category that's all fixed doesn't show at all.
 */
fun categoryChanges(history: History, goals: Goals, fixed: FixedCosts = FixedCosts()): List<CategoryChange> {
    val ids = (history.thisMonth.filter { !it.fixed }.map { it.categoryId } +
        history.baseMonths.flatMap { m -> history.byMonth[m].orEmpty().filter { !it.fixed }.map { it.categoryId } } +
        goals.categoryCaps.keys).toSet() - fixed.categories
    val homeDaysInMonth = history.homeDays(history.currentMonth)
    return ids.map { id ->
        val daily = history.averageDaily { it.categoryId == id && !it.fixed } ?: 0.0
        CategoryChange(
            categoryId = id,
            thisMonthMinor = history.thisMonth.filter { it.categoryId == id && !it.fixed }.sumOf { it.gbpMinor },
            usualByNowMinor = (daily * history.homeDaysSoFar).roundToLong(),
            usualMonthMinor = (daily * homeDaysInMonth).roundToLong(),
            capMinor = goals.categoryCaps[id],
        )
    }
        .filter { it.thisMonthMinor > 0 || it.usualMonthMinor > 0 || it.capMinor != null }
        // Spending over the average comes first: that is where there is room to cut back.
        .sortedWith(
            compareByDescending<CategoryChange> { it.changeMinor > OVER_BY }
                .thenByDescending { if (it.changeMinor > OVER_BY) it.changeMinor else abs(it.changeMinor) }
        )
}

/** Less than £2 either way counts as about average. */
const val OVER_BY = 200L

// Averages a month

data class Averages(
    /** Average a month in pence, by category id and by sub-category id. */
    val byCategory: Map<Long, Long>,
    val bySubcategory: Map<Long, Long>,
    /** How many months the averages come from. */
    val months: Int,
)

/**
 * What each category and sub-category costs in an average month: the last six finished months with
 * at least ten days at home, trip days left out, scaled to a whole month.
 */
fun averages(history: History): Averages {
    if (history.baseMonths.isEmpty()) return Averages(emptyMap(), emptyMap(), 0)
    val base = history.baseMonths.flatMap { history.byMonth[it].orEmpty() }
    fun perMonth(filter: (Spend) -> Boolean): Long =
        ((history.averageDaily(filter) ?: 0.0) * DAYS_PER_MONTH).roundToLong()
    val categories = base.map { it.categoryId }.toSet().associateWith { id -> perMonth { it.categoryId == id } }
    val subs = base.map { it.subcategoryId }.toSet().associateWith { id -> perMonth { it.subcategoryId == id } }
    return Averages(categories.filterValues { it > 0 }, subs.filterValues { it > 0 }, history.baseMonths.size)
}

// No-spend days

data class NoSpend(
    /** Days at home this month, up to yesterday, with nothing spent. */
    val thisMonth: Int,
    /** Days at home this month up to yesterday. */
    val daysSoFar: Int,
    val target: Int?,
    val currentStreak: Int,
    /** Longest run in the last year. */
    val bestStreak: Int,
)

fun noSpend(history: History, goals: Goals): NoSpend {
    val yesterday = history.today.minusDays(1)
    val first = history.firstDay ?: return NoSpend(0, 0, goals.noSpendTarget, 0, 0)
    val monthStart = history.currentMonth.atDay(1)
    var thisMonth = 0
    var daysSoFar = 0
    var d = monthStart
    while (d <= yesterday) {
        if (d !in history.away && d >= first) {
            daysSoFar++
            if (d !in history.spendDays) thisMonth++
        }
        d = d.plusDays(1)
    }

    var current = 0
    var counting = true
    var best = 0
    var run = 0
    val start = maxOf(first, history.today.minusDays(365))
    d = yesterday
    while (d >= start) {
        if (d !in history.away) {
            if (d in history.spendDays) {
                counting = false
                run = 0
            } else {
                run++
                if (counting) current++
                if (run > best) best = run
            }
        }
        d = d.minusDays(1)
    }
    return NoSpend(thisMonth, daysSoFar, goals.noSpendTarget, current, best)
}

// Top places

data class Place(val key: String, val name: String, val visits: Int, val totalMinor: Long) {
    val averageMinor: Long get() = if (visits > 0) totalMinor / visits else 0
}

fun topPlaces(spends: List<Spend>, limit: Int = 5): List<Place> =
    spends.filter { it.merchantKey.isNotEmpty() }
        .groupBy { it.merchantKey }
        .map { (key, list) ->
            Place(key, list.maxBy { it.occurredAt }.merchantName.ifBlank { key }, list.size, list.sumOf { it.gbpMinor })
        }
        .sortedByDescending { it.totalMinor }
        .take(limit)

fun lastDays(history: History, days: Long): List<Spend> {
    val from = history.today.minusDays(days - 1)
    return history.spends.filter { it.day >= from && it.day <= history.today }
}

// When the money goes

enum class TimeSlot(val label: String) {
    MORNING("Morning, 5am to 11am"),
    AFTERNOON("Afternoon, 11am to 5pm"),
    EVENING("Evening, 5pm to 9pm"),
    LATE("Late night, 9pm to 5am"),
    ;

    companion object {
        fun of(hour: Int): TimeSlot = when (hour) {
            in 5..10 -> MORNING
            in 11..16 -> AFTERNOON
            in 17..20 -> EVENING
            else -> LATE
        }
    }
}

data class SlotTotal(val slot: TimeSlot, val totalMinor: Long, val count: Int)

data class WhenYouSpend(
    /** Last 90 days, only payments with a known time. */
    val slots: List<SlotTotal>,
    val timedPayments: Int,
    /** Average per day at home over the last six months. */
    val weekdayDailyMinor: Long?,
    val weekendDailyMinor: Long?,
    val busiestDay: DayOfWeek?,
    val busiestDayMinor: Long?,
)

fun whenYouSpend(history: History): WhenYouSpend {
    val timed = lastDays(history, 90).filter { it.hour != null }
    val slots = TimeSlot.entries.map { slot ->
        val list = timed.filter { TimeSlot.of(it.hour!!) == slot }
        SlotTotal(slot, list.sumOf { it.gbpMinor }, list.size)
    }

    val first = history.firstDay
    val from = listOfNotNull(first, history.today.minusDays(182)).maxOrNull()
    val totals = HashMap<DayOfWeek, Long>()
    val counts = HashMap<DayOfWeek, Int>()
    if (from != null) {
        val byDay = history.spends.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.gbpMinor } }
        var d: LocalDate = from
        while (d < history.today) {
            if (d !in history.away) {
                totals[d.dayOfWeek] = (totals[d.dayOfWeek] ?: 0) + (byDay[d] ?: 0)
                counts[d.dayOfWeek] = (counts[d.dayOfWeek] ?: 0) + 1
            }
            d = d.plusDays(1)
        }
    }
    fun average(days: Collection<DayOfWeek>): Long? {
        val n = days.sumOf { counts[it] ?: 0 }
        return if (n == 0) null else days.sumOf { totals[it] ?: 0 } / n
    }
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    val busiest = DayOfWeek.entries.filter { (counts[it] ?: 0) > 0 }
        .maxByOrNull { (totals[it] ?: 0).toDouble() / counts.getValue(it) }
    return WhenYouSpend(
        slots = slots,
        timedPayments = timed.size,
        weekdayDailyMinor = average(DayOfWeek.entries - weekend),
        weekendDailyMinor = average(weekend),
        busiestDay = busiest,
        busiestDayMinor = busiest?.let { (totals[it] ?: 0) / counts.getValue(it) },
    )
}

// Regular payments

data class Regular(
    /** Stable name for this payment, used to hide it if the owner says it is not regular. */
    val key: String,
    val name: String,
    val subcategoryId: Long,
    val typicalMinor: Long,
    val lastDay: LocalDate,
    val nextDay: LocalDate,
    val times: Int,
) {
    /** True when the next one was expected by now but has not been seen yet. */
    fun isDue(today: LocalDate): Boolean = !nextDay.isAfter(today)
}

data class Regulars(
    /** Payments that come about once a month for about the same amount. */
    val found: List<Regular>,
    /** How many the owner has hidden. */
    val hidden: Int = 0,
) {
    val monthlyMinor: Long get() = found.sumOf { it.typicalMinor }
}

/**
 * Finds payments that repeat about once a month over the last year: the same sub-category and about
 * the same amount, at most one a month, seen at least three times, most gaps close to a whole number of months (so a
 * month missed on a trip is fine), and the last one within the last 45 days.
 *
 * In busy sub-categories such as Lunch, a run of similar amounts is usually chance, so there it has
 * to be the same shop every time.
 */
fun regulars(history: History, hidden: Set<String> = emptySet()): Regulars {
    val found = ArrayList<Regular>()
    var hiddenCount = 0
    // Only the last year: what matters is what still repeats now.
    val since = history.today.minusDays(365)
    history.spends.filter { it.day >= since }.groupBy { it.subcategoryId }.forEach { (subId, list) ->
        val months = list.map { YearMonth.from(it.day) }.toSet().size.coerceAtLeast(1)
        val busy = list.size.toDouble() / months >= 3.0
        for (cluster in amountClusters(list)) {
            val sorted = cluster.sortedBy { it.day }
            val shopKey = sorted.first().merchantKey
            val sameShop = shopKey.isNotEmpty() && shopKey != "CASH" && sorted.all { it.merchantKey == shopKey }
            if (busy && !sameShop) continue
            val days = sorted.distinctBy { it.day }
            if (days.size < (if (sameShop) 2 else 3)) continue
            val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.day, b.day) }
            // At most one a month: allow the odd slip, such as an extra in-game purchase.
            if (gaps.count { it < 20 } > maxOf(1, gaps.size / 6)) continue
            val fitting = gaps.count { gap ->
                val m = Math.round(gap / DAYS_PER_MONTH)
                m >= 1 && abs(gap - m * DAYS_PER_MONTH) <= 5.5
            }
            if (fitting * 10 < gaps.size * 8) continue
            val last = sorted.last()
            if (ChronoUnit.DAYS.between(last.day, history.today) > 45) continue
            val typical = median(sorted.takeLast(4).map { it.gbpMinor.toDouble() })!!.roundToLong()
            val key = "$subId|${if (sameShop) shopKey else ""}|${(typical + 50) / 100}"
            if (key in hidden) {
                hiddenCount++
                continue
            }
            val name = if (sameShop) last.merchantName else ""
            found += Regular(key, name, subId, typical, last.day, last.day.plusMonths(1), sorted.size)
        }
    }
    return Regulars(found.sortedBy { it.nextDay }, hiddenCount)
}

/** Groups payments whose amounts are within about 10% of each other (£9 and £10 go together). */
private fun amountClusters(list: List<Spend>): List<List<Spend>> {
    val out = ArrayList<MutableList<Spend>>()
    for (spend in list.sortedBy { it.gbpMinor }) {
        val current = out.lastOrNull()
        val centre = current?.let { c -> c.sumOf { it.gbpMinor }.toDouble() / c.size }
        if (current != null && centre != null && abs(spend.gbpMinor - centre) <= maxOf(centre, spend.gbpMinor.toDouble()) * 0.12) {
            current += spend
        } else {
            out += mutableListOf(spend)
        }
    }
    return out
}

// Month by month

data class MonthBar(val month: YearMonth, val totalMinor: Long, val awayDays: Int, val current: Boolean)

fun monthBars(history: History, count: Int = 12): List<MonthBar> {
    val first = history.firstDay?.let { YearMonth.from(it) } ?: return emptyList()
    val start = maxOf(first, history.currentMonth.minusMonths(count - 1L))
    val out = ArrayList<MonthBar>()
    var m = start
    while (m <= history.currentMonth) {
        out += MonthBar(m, history.byMonth[m].orEmpty().sumOf { it.gbpMinor }, history.away.countIn(m), m == history.currentMonth)
        m = m.plusMonths(1)
    }
    return out
}

// A year looked back on

data class Recap(
    val year: Int,
    val totalMinor: Long,
    val payments: Int,
    val months: Int,
    val monthlyAverageMinor: Long,
    val topCategories: List<Pair<Long, Long>>,
    val topSubcategories: List<Pair<Long, Long>>,
    val biggestDay: Pair<LocalDate, Long>?,
    val priciestMonth: Pair<YearMonth, Long>?,
    val cheapestMonth: Pair<YearMonth, Long>?,
    val noSpendDays: Int,
    val topPlace: Place?,
    val busiestDay: DayOfWeek?,
    val lateNightMinor: Long,
    val lateNightCount: Int,
    val awayDays: Int,
)

fun recapYears(history: History): List<Int> = history.spends.map { it.day.year }.distinct().sortedDescending()

fun recap(history: History, year: Int): Recap? {
    val list = history.spends.filter { it.day.year == year }
    if (list.isEmpty()) return null
    val total = list.sumOf { it.gbpMinor }
    val byMonth = list.groupBy { YearMonth.from(it.day) }.mapValues { (_, l) -> l.sumOf { it.gbpMinor } }
    val fullMonths = byMonth.filterKeys { it < history.currentMonth && history.homeDays(it) >= History.MIN_HOME_DAYS }
    val first = list.minOf { it.day }
    // Days with no entries after the last one of a past year are not tracked, so they do not count.
    val last = if (year == history.today.year) history.today.minusDays(1) else list.maxOf { it.day }
    var noSpend = 0
    var awayDays = 0
    var d = first
    while (d <= last) {
        if (d in history.away) awayDays++ else if (d !in history.spendDays) noSpend++
        d = d.plusDays(1)
    }
    val byWeekday = list.groupBy { it.day.dayOfWeek }.mapValues { (_, l) -> l.sumOf { it.gbpMinor } }
    val late = list.filter { it.hour != null && TimeSlot.of(it.hour) == TimeSlot.LATE }
    return Recap(
        year = year,
        totalMinor = total,
        payments = list.size,
        months = byMonth.size,
        monthlyAverageMinor = if (fullMonths.isEmpty()) total / byMonth.size else fullMonths.values.sum() / fullMonths.size,
        topCategories = list.groupBy { it.categoryId }.map { (id, l) -> id to l.sumOf { it.gbpMinor } }
            .sortedByDescending { it.second }.take(3),
        topSubcategories = list.groupBy { it.subcategoryId }.map { (id, l) -> id to l.sumOf { it.gbpMinor } }
            .sortedByDescending { it.second }.take(3),
        biggestDay = list.groupBy { it.day }.map { (day, l) -> day to l.sumOf { it.gbpMinor } }.maxByOrNull { it.second },
        priciestMonth = fullMonths.maxByOrNull { it.value }?.toPair(),
        cheapestMonth = fullMonths.minByOrNull { it.value }?.toPair(),
        noSpendDays = noSpend,
        topPlace = topPlaces(list, 1).firstOrNull(),
        busiestDay = byWeekday.maxByOrNull { it.value }?.key,
        lateNightMinor = late.sumOf { it.gbpMinor },
        lateNightCount = late.size,
        awayDays = awayDays,
    )
}
