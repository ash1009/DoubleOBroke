package app.brokeometer.capture

import app.brokeometer.data.BrokeDao
import app.brokeometer.data.Prefs
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * Watches this month's spending by category and sends one nudge when a category reaches 80% of
 * its cap, and one when it goes over. Each is sent once a month.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NudgeWatcher(
    private val dao: BrokeDao,
    private val prefs: Prefs,
    private val notifier: Notifier,
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            val months = flow {
                while (true) {
                    emit(YearMonth.now())
                    delay(30 * 60 * 1000L)
                }
            }.distinctUntilChanged()
            val sums = months.flatMapLatest { month ->
                dao.observeCategorySums(month.toString()).map { rows -> month to rows.associate { it.categoryId to it.totalMinor } }
            }
            combine(sums, prefs.goals, dao.observeCategories()) { (month, totals), goals, categories ->
                Triple(month, totals, goals.categoryCaps.mapNotNull { (id, cap) ->
                    val name = categories.firstOrNull { it.id == id }?.name ?: return@mapNotNull null
                    Triple(id, name, cap)
                })
            }.collect { (month, totals, caps) ->
                for ((id, name, cap) in caps) check(month, id, name, cap, totals[id] ?: 0L)
            }
        }
    }

    private fun check(month: YearMonth, categoryId: Long, name: String, capMinor: Long, spentMinor: Long) {
        if (capMinor <= 0) return
        val level = when {
            spentMinor > capMinor -> OVER
            spentMinor * 100 >= capMinor * 80 -> NEAR
            else -> return
        }
        val key = "$month|$categoryId|$level"
        if (prefs.nudgeSent(key)) return
        val monthName = Dates.monthName(month)
        val cap = Money.formatWhole(capMinor)
        val (title, body) = if (level == OVER) {
            "$name is over your $cap cap" to "${Money.format(spentMinor)} spent on $name so far in $monthName."
        } else {
            "$name is at ${spentMinor * 100 / capMinor}% of your $cap cap" to
                "${Money.format(spentMinor)} spent so far in $monthName, ${Money.format(capMinor - spentMinor)} left."
        }
        if (notifier.showNudge(categoryId, title, body)) {
            prefs.markNudgeSent(key, month.toString())
            // Going straight past 80% to over counts as both.
            if (level == OVER) prefs.markNudgeSent("$month|$categoryId|$NEAR", month.toString())
        }
    }

    private companion object {
        const val NEAR = "80"
        const val OVER = "100"
    }
}
