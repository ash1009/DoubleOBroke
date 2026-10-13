package app.brokeometer.insights

import kotlin.math.abs
import kotlin.math.roundToLong

/** A piece of a sentence; [strong] pieces are the numbers and names that stand out. */
data class Bit(val text: String, val strong: Boolean = false)

typealias Sentence = List<Bit>

/**
 * How the month is going, in a few short sentences with the numbers picked out, for the home page.
 * Leads with good news where there is some. [money] writes an amount in pence, [category] names one.
 */
fun homeWords(
    history: History,
    categories: List<CategoryChange>,
    noSpend: NoSpend,
    whenYouSpend: WhenYouSpend,
    money: (Long) -> String,
    category: (Long) -> String?,
): List<Sentence> {
    val out = ArrayList<Sentence>()

    // Spending a day this month against a usual day.
    val daily = history.dailyThisMonth
    if (daily != null) {
        val perDay = daily.roundToLong()
        val usual = history.usualDaily
        out += if (usual == null) {
            listOf(Bit("You're spending "), Bit("${money(perDay)} a day", true), Bit(" this month."))
        } else {
            val diff = (daily - usual).roundToLong()
            when {
                abs(diff) < maxOf(SAME_PENCE, (usual * 0.05).roundToLong()) ->
                    listOf(Bit("You're spending "), Bit("${money(perDay)} a day", true), Bit(" this month, about your usual."))
                diff < 0 ->
                    listOf(Bit("You're spending "), Bit("${money(perDay)} a day", true), Bit(", "), Bit("${money(-diff)} less", true), Bit(" than usual."))
                else ->
                    listOf(Bit("You're spending "), Bit("${money(perDay)} a day", true), Bit(", "), Bit("${money(diff)} more", true), Bit(" than usual."))
            }
        }
    }

    // The category furthest under its usual so far.
    categories.filter { it.usualByNowMinor > 0 }
        .minByOrNull { it.changeMinor }
        ?.takeIf { -it.changeMinor >= UNDER_BY }
        ?.let { best ->
            category(best.categoryId)?.let { name ->
                out += listOf(Bit(name, true), Bit(" is "), Bit("${money(-best.changeMinor)} under", true), Bit(" your usual so far."))
            }
        }

    // No-spend days.
    if (noSpend.thisMonth > 0) {
        val days = if (noSpend.thisMonth == 1) "1 no-spend day" else "${noSpend.thisMonth} no-spend days"
        val tail = when {
            noSpend.currentStreak >= 2 -> listOf(Bit(", and you're on a "), Bit("${noSpend.currentStreak}-day run", true), Bit("."))
            noSpend.bestStreak > 1 -> listOf(Bit(". Your best run this year is "), Bit("${noSpend.bestStreak} days", true), Bit("."))
            else -> listOf(Bit("."))
        }
        out += listOf(Bit(days, true), Bit(" this month")) + tail
    }

    // Weekends against weekdays, when they differ a lot.
    val weekday = whenYouSpend.weekdayDailyMinor
    val weekend = whenYouSpend.weekendDailyMinor
    if (weekday != null && weekend != null && weekday > 0 && weekend > 0) {
        if (weekend >= weekday * 1.25) {
            out += listOf(Bit("Weekends cost you "), Bit("${money(weekend)} a day", true), Bit(", weekdays "), Bit(money(weekday), true), Bit("."))
        } else if (weekday >= weekend * 1.25) {
            out += listOf(Bit("Weekdays cost you "), Bit("${money(weekday)} a day", true), Bit(", weekends "), Bit(money(weekend), true), Bit("."))
        }
    }
    return out.take(4)
}

/** Within this many pence a day counts as "about your usual". */
private const val SAME_PENCE = 50L

/** A category has to be at least this far under (in pence) to be worth a mention. */
private const val UNDER_BY = 500L
