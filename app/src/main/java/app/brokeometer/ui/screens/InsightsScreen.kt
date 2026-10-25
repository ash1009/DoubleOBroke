package app.brokeometer.ui.screens

import androidx.compose.foundation.layout.WindowInsets

import app.brokeometer.insights.aheadWholeDays

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.data.Catalog
import app.brokeometer.insights.TimeSlot
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.InsightsData
import app.brokeometer.ui.components.AmountText
import app.brokeometer.ui.components.BigFigure
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.CardText
import app.brokeometer.ui.components.CategoryDot
import app.brokeometer.ui.components.GoalsSheet
import app.brokeometer.ui.components.InsightCard
import app.brokeometer.ui.components.LimitBar
import app.brokeometer.ui.components.MonthBars
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.components.ShareBar
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

@Composable
fun InsightsScreen(vm: AppViewModel, onOpenRecap: (Int) -> Unit) {
    val data by vm.insights.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    // Opened from a card here.
    val editingGoals by vm.editingGoals.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshToday() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Inside the app's frame: its top and bottom bars already keep clear of the phone's own bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LocalBrand.current.action)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            ) {
                item("tabs") {
                    Tabs(
                        labels = listOf("This month", "Patterns"),
                        selected = tab,
                        onSelect = { tab = it },
                    )
                }
                if (d.unconverted.isNotEmpty()) item("fx") { Unconverted(d, onRetry = { vm.refreshRates() }) }
                if (tab == 0) {
                    item("runway") { RunwayCard(d, onSet = { vm.editGoals(true) }) }
                    item("allowance") { AllowanceCard(d, onSet = { vm.editGoals(true) }) }
                    item("categories") { CategoriesCard(d, catalog) }
                    item("nospend") { NoSpendCard(d) }
                } else {
                    item("months") { MonthsCard(d) }
                    item("regular") {
                        RegularCard(d, catalog, onHide = { vm.hideRegular(it) }, onShowAll = { vm.showAllRegulars() })
                    }
                    item("places") { PlacesCard(d) }
                    item("when") { WhenCard(d) }
                    item("recap") { RecapCard(d, onOpenRecap) }
                }
            }
        }
    }

    if (editingGoals) {
        GoalsSheet(
            goals = vm.goals.value,
            catalog = catalog,
            insights = data,
            onDismiss = { vm.editGoals(false) },
            onSave = { vm.saveGoals(it) },
        )
    }
}

@Composable
internal fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val brand = LocalBrand.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(i) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) brand.onAction else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Unconverted(d: InsightsData, onRetry: () -> Unit) {
    InsightCard(title = "Not in pounds yet") {
        CardText(
            d.unconverted.entries.joinToString(" and ") { (c, m) -> Money.formatWhole(m, c) } +
                " is left out until the exchange rates load. They load when the phone is online.",
        )
        QuietButton("Try again", onClick = onRetry, height = 44.dp)
    }
}

@Composable
private fun RunwayCard(d: InsightsData, onSet: () -> Unit) {
    val brand = LocalBrand.current
    val r = d.runway
    InsightCard(title = "Runway") {
        if (r == null) {
            Text("How long will your savings last?", style = MaterialTheme.typography.titleLarge)
            CardText(
                "Add what you have saved. Spending from then on comes off it by itself.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuietButton("Add savings", onClick = onSet, height = 44.dp)
            return@InsightCard
        }
        val lastsUntil = r.lastsUntil
        val ahead = r.aheadWholeDays()
        BigFigure(
            value = lastsUntil?.let { Dates.pretty(it) } ?: Money.formatWhole(r.leftMinor),
            caption = if (lastsUntil != null) "your savings last until, at your usual spending" else "left",
            color = MaterialTheme.colorScheme.onSurface,
        )
        val pace = when {
            ahead == null -> ""
            ahead > 0 -> " You're ${if (ahead == 1) "1 day" else "$ahead days"} ahead of your usual pace, so the date has moved later."
            ahead < 0 -> " You're ${if (ahead == -1) "1 day" else "${-ahead} days"} behind your usual pace, so the date has moved earlier."
            else -> " You're on track: spending as usual keeps this date where it is."
        }
        CardText(
            "${Money.formatWhole(r.leftMinor)} left.$pace",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ahead != null) {
            val tint = if (ahead < 0) brand.careful else brand.fine
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(tint, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        ahead > 0 -> if (ahead == 1) "1 day ahead" else "$ahead days ahead"
                        ahead < 0 -> if (ahead == -1) "1 day behind" else "${-ahead} days behind"
                        else -> "On track"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
        }
        val until = r.lastUntil
        if (until != null && r.monthlyToLastMinor != null && r.dailyToLastMinor != null) {
            Highlight(
                "To last until ${Dates.pretty(until)}: ${Money.formatWhole(r.monthlyToLastMinor)} a month, " +
                    "${Money.format(r.dailyToLastMinor)} a day."
            )
        }
    }
}

@Composable
private fun Highlight(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AllowanceCard(d: InsightsData, onSet: () -> Unit) {
    val brand = LocalBrand.current
    val a = d.allowance
    InsightCard(title = "This month") {
        if (a == null) {
            Text("Get a daily allowance", style = MaterialTheme.typography.titleLarge)
            CardText(
                "Set a monthly limit, or a date your savings must last until, and this shows what you can spend each day." +
                    (d.usualMonthMinor?.let { " A usual month is about ${Money.formatWhole(it)}." } ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuietButton("Set a limit", onClick = onSet, height = 44.dp)
            return@InsightCard
        }
        val source = if (a.fromLastUntil) "to make your savings last" else "within your ${Money.formatWhole(a.limitMinor)} limit"
        if (a.perDayMinor != null) {
            BigFigure(
                value = "${Money.format(a.perDayMinor)} a day",
                caption = "for the next ${plural(a.daysLeft, "day")}, $source",
            )
        } else {
            BigFigure(
                value = "${Money.formatWhole(-a.leftMinor)} over",
                caption = "your ${Money.formatWhole(a.limitMinor)} ${if (a.fromLastUntil) "monthly amount" else "limit"}",
                color = brand.broke,
            )
        }
        LimitBar(a.spentMinor, a.limitMinor)
        Row {
            CardText("${Money.formatWhole(a.spentMinor)} spent", modifier = Modifier.weight(1f))
            CardText(
                if (a.leftMinor >= 0) "${Money.formatWhole(a.leftMinor)} left" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (a.fixedMinor > 0) {
            CardText(
                "Fixed costs this month: ${Money.formatWhole(a.fixedMinor)}, not counted here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoriesCard(d: InsightsData, catalog: Catalog) {
    val brand = LocalBrand.current
    var all by remember { mutableStateOf(false) }
    InsightCard(title = "Against your average") {
        if (d.categories.isEmpty() || d.history.baseMonths.isEmpty()) {
            CardText(
                "Shows which categories are up or down once there is a finished month with ten or more days at home.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@InsightCard
        }
        val rows = if (all) d.categories else d.categories.take(3)
        rows.forEach { row ->
            val category = catalog.category(row.categoryId) ?: return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(brand.category(category.colorIndex))
                    Spacer(Modifier.width(10.dp))
                    Text(category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    AmountText(Money.format(row.thisMonthMinor))
                }
                val change = row.changeMinor
                val line = when {
                    abs(change) < 200 -> "About average so far"
                    change > 0 -> "${Money.formatWhole(change)} more than average so far"
                    else -> "${Money.formatWhole(-change)} less than average so far"
                }
                Text(
                    line + (row.capMinor?.let { ", cap ${Money.formatWhole(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (change >= 200) brand.careful else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.capMinor?.let { LimitBar(row.thisMonthMinor, it, color = brand.category(category.colorIndex)) }
            }
        }
        if (d.categories.size > 3) {
            TextButton(onClick = { all = !all }, contentPadding = PaddingValues(0.dp)) {
                Text(if (all) "Show fewer" else "Show all ${d.categories.size}", color = brand.action)
            }
        }
    }
}

@Composable
private fun NoSpendCard(d: InsightsData) {
    val n = d.noSpend
    InsightCard(title = "No-spend days") {
        BigFigure(
            value = if (n.target != null) "${n.thisMonth} of ${n.target}" else n.thisMonth.toString(),
            caption = if (n.target != null) "no-spend days this month, against your goal" else "no-spend days this month so far",
        )
        n.target?.let { ShareBar(fraction = n.thisMonth.toFloat() / it, color = LocalBrand.current.fine) }
        CardText(
            "Current run ${plural(n.currentStreak, "day")}, longest ${plural(n.bestStreak, "day")}.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonthsCard(d: InsightsData) {
    if (d.months.size < 2) return
    InsightCard(title = "Month by month") {
        MonthBars(d.months, d.usualMonthMinor)
        val finished = d.months.filter { !it.current && it.totalMinor > 0 }
        val top = finished.maxByOrNull { it.totalMinor }
        val parts = buildList {
            d.usualMonthMinor?.let { add("Dashed line is a usual month, ${Money.formatWhole(it)}.") }
            top?.let { add("Highest was ${Dates.monthName(it.month)}, ${Money.formatWhole(it.totalMinor)}.") }
        }
        if (parts.isNotEmpty()) {
            Text(parts.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlacesCard(d: InsightsData) {
    InsightCard(title = "Top places, last 90 days") {
        if (d.places.isEmpty()) {
            CardText(
                "Shop names come from HSBC alerts, so this fills up as the app catches your payments.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@InsightCard
        }
        d.places.forEach { place ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        "${plural(place.visits, "visit")}, ${Money.format(place.averageMinor)} each",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(Money.format(place.totalMinor))
            }
        }
    }
}

@Composable
private fun WhenCard(d: InsightsData) {
    val brand = LocalBrand.current
    val w = d.whenYouSpend
    InsightCard(title = "When you spend, last 90 days") {
        if (w.timedPayments == 0) {
            CardText(
                "Times come from HSBC alerts, so the time of day fills up from now on.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val largest = w.slots.maxOf { it.totalMinor }.coerceAtLeast(1L)
            w.slots.forEach { slot ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row {
                        Text(slot.slot.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        AmountText(Money.formatWhole(slot.totalMinor), style = MaterialTheme.typography.bodyMedium)
                    }
                    ShareBar(
                        fraction = slot.totalMinor.toFloat() / largest,
                        color = if (slot.slot == TimeSlot.LATE) brand.careful else brand.fine,
                    )
                }
            }
        }
        val weekday = w.weekdayDailyMinor
        val weekend = w.weekendDailyMinor
        if (weekday != null && weekend != null) {
            Highlight(
                "Weekdays ${Money.formatWhole(weekday)} a day, weekends ${Money.formatWhole(weekend)} a day." +
                    (w.busiestDay?.let { " Priciest day: ${it.getDisplayName(TextStyle.FULL, Locale.UK)}." } ?: "")
            )
        }
    }
}

@Composable
private fun RegularCard(d: InsightsData, catalog: Catalog, onHide: (String) -> Unit, onShowAll: () -> Unit) {
    val brand = LocalBrand.current
    val r = d.regulars
    val today = d.history.today
    InsightCard(title = "Regular payments") {
        if (r.found.isEmpty()) {
            CardText(
                "None spotted yet. A payment shows here once the same amount has come about once a month a few times.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        r.found.forEach { reg ->
            val name = reg.name.ifBlank { catalog.sub(reg.subcategoryId)?.name ?: "Payment" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        if (reg.isDue(today)) "Due around ${Dates.pretty(reg.nextDay)}, not seen yet"
                        else "Next around ${Dates.pretty(reg.nextDay)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (reg.isDue(today)) brand.careful else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(Money.format(reg.typicalMinor))
                IconButton(onClick = { onHide(reg.key) }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "$name is not a regular payment",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (r.found.isNotEmpty()) Highlight("About ${Money.formatWhole(r.monthlyMinor)} a month in total.")
        if (r.hidden > 0) {
            TextButton(onClick = onShowAll, contentPadding = PaddingValues(0.dp)) {
                Text(
                    "${plural(r.hidden, "hidden payment")}. Show again",
                    color = brand.action,
                )
            }
        }
    }
}

@Composable
private fun RecapCard(d: InsightsData, onOpen: (Int) -> Unit) {
    if (d.years.isEmpty()) return
    InsightCard(title = "Your year") {
        Text("A look back at where the money went", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            d.years.forEach { year ->
                QuietButton(year.toString(), onClick = { onOpen(year) }, height = 44.dp)
            }
        }
    }
}

private fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
