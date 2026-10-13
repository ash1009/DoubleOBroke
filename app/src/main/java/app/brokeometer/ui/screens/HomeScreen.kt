package app.brokeometer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.data.TransactionEntity
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.CategoryTotal
import app.brokeometer.ui.InsightsData
import app.brokeometer.ui.MeterZone
import app.brokeometer.ui.MonthSummary
import app.brokeometer.ui.SetupPanel
import app.brokeometer.ui.components.AmountText
import app.brokeometer.ui.components.Avatar
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.CategoryDot
import app.brokeometer.insights.aheadWholeDays
import app.brokeometer.ui.components.DailyBars
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.EntryDraft
import app.brokeometer.ui.components.EntryRow
import app.brokeometer.ui.components.EntrySheet
import app.brokeometer.ui.components.MeterGauge
import app.brokeometer.ui.components.SectionTitle
import app.brokeometer.ui.components.ShareBar
import app.brokeometer.ui.rememberSetupStatus
import app.brokeometer.ui.summarise
import app.brokeometer.ui.theme.BrandColors
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.ui.theme.TABULAR
import app.brokeometer.ui.usualMonthMinor
import app.brokeometer.ui.zoneFor
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import java.time.YearMonth
import kotlin.math.roundToInt

/** Money's first page: the month so far on the meter, goals, payments waiting, and how it's going. */
@Composable
internal fun MoneyThisMonthPage(
    vm: AppViewModel,
    listState: LazyListState,
    onOpenPending: () -> Unit,
    onOpenInsights: () -> Unit,
) {
    val entries by vm.thisMonth.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val dayTotals by vm.dayTotals.collectAsStateWithLifecycle()
    val away by vm.away.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val setup = rememberSetupStatus()
    val insights by vm.insights.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshToday() }

    val month = YearMonth.from(today)
    val summary = remember(entries, catalog, today, away) { summarise(entries, catalog, month, today, away) }
    val usual = remember(dayTotals, month, away) { usualMonthMinor(dayTotals, month, away) }
    val level = if (usual != null && usual > 0) summary.totalMinor.toFloat() / usual.toFloat() else null

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
    ) {
        if (!setup.complete) {
            item(key = "setup") {
                SetupPanel(setup, Modifier.padding(top = 4.dp, bottom = 12.dp))
            }
        }

        item(key = "meter") {
            MeterBlock(summary = summary, level = level, usualMinor = usual)
        }

        insights?.let { data ->
            item(key = "tiles") {
                Spacer(Modifier.height(20.dp))
                InsightTiles(data, onClick = onOpenInsights)
            }
        }

        if (pending.isNotEmpty()) {
            item(key = "waiting") {
                WaitingTray(
                    count = pending.size,
                    totalMinor = pending.filter { it.currency == "GBP" }.sumOf { it.amountMinor },
                    onClick = onOpenPending,
                )
            }
        }

        insights?.let { data ->
            item(key = "words") { WordsCard(data, catalog, onClick = onOpenInsights) }
            item(key = "cut-back") { CutBackCard(data, catalog, onClick = onOpenInsights) }
            item(key = "coming-up") { ComingUp(data, catalog) }
        }
    }
}

/** How the month is going in a few plain sentences, numbers picked out. */
@Composable
private fun WordsCard(data: InsightsData, catalog: app.brokeometer.data.Catalog, onClick: () -> Unit) {
    val sentences = remember(data, catalog) {
        app.brokeometer.insights.homeWords(
            history = data.history,
            categories = data.categories,
            noSpend = data.noSpend,
            whenYouSpend = data.whenYouSpend,
            money = { pence -> Money.format(pence).removeSuffix(".00") },
            category = { id -> catalog.category(id)?.name },
        )
    }
    if (sentences.isEmpty()) return
    val strong = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How it's going", style = MaterialTheme.typography.titleLarge)
            sentences.forEach { sentence ->
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        sentence.forEach { bit ->
                            if (bit.strong) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = strong))
                                append(bit.text)
                                pop()
                            } else {
                                append(bit.text)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The one or two categories most over their average this month: where there is room to save. */
@Composable
private fun CutBackCard(data: InsightsData, catalog: app.brokeometer.data.Catalog, onClick: () -> Unit) {
    if (data.history.baseMonths.isEmpty()) return
    val brand = LocalBrand.current
    val over = data.categories.filter { it.changeMinor > app.brokeometer.insights.OVER_BY }.take(2)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Where to cut back", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("against your average", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (over.isEmpty()) {
                Text(
                    "Nothing is over your average so far this month.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val largest = over.maxOfOrNull { it.changeMinor }?.coerceAtLeast(1L) ?: 1L
            over.forEach { row ->
                val category = catalog.category(row.categoryId) ?: return@forEach
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryDot(brand.category(category.colorIndex))
                        Spacer(Modifier.width(10.dp))
                        Text(category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        AmountText("${Money.formatWhole(row.changeMinor)} over", color = brand.broke)
                    }
                    ShareBar(fraction = row.changeMinor.toFloat() / largest, color = brand.category(category.colorIndex))
                }
            }
        }
    }
}

/** The next regular payment, if one is due in the next two weeks. */
@Composable
private fun ComingUp(data: InsightsData, catalog: app.brokeometer.data.Catalog) {
    val today = data.history.today
    val next = data.regulars.found
        .filter { !it.nextDay.isAfter(today.plusDays(14)) }
        .minByOrNull { it.nextDay } ?: return
    val name = next.name.ifBlank { catalog.sub(next.subcategoryId)?.name ?: "A regular payment" }
    val whenText = if (next.isDue(today)) "due around ${Dates.pretty(next.nextDay)}, not seen yet" else "around ${Dates.pretty(next.nextDay)}"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Event,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Coming up: $name, about ${Money.formatWhole(next.typicalMinor)}, $whenText",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The dial with the month's figure inside it, and one line saying how the month is going. */
@Composable
private fun MeterBlock(summary: MonthSummary, level: Float?, usualMinor: Long?) {
    val brand = LocalBrand.current
    val zone = level?.let { zoneFor(it) }
    val percent = level?.let { (it * 100).roundToInt() }
    val spoken = if (percent != null) {
        "${Money.format(summary.totalMinor, summary.currency)} spent, $percent percent of a usual month"
    } else {
        "${Money.format(summary.totalMinor, summary.currency)} spent this month"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.BottomCenter) {
            MeterGauge(level = level, description = spoken, modifier = Modifier.padding(top = 4.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AmountText(
                    Money.format(summary.totalMinor, summary.currency),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "${Dates.monthName(summary.month)} so far",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (zone != null && percent != null && usualMinor != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(zoneColor(zone, brand), size = 9.dp)
                Spacer(Modifier.width(8.dp))
                Text(zone.word, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "$percent% of a usual month (${Money.formatWhole(usualMinor)})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text(
                "The dial starts comparing once you have one full month on record.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        summary.otherCurrencies.forEach { (currency, amount) ->
            Text(
                "Plus ${Money.format(amount, currency)} in $currency, not converted yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun zoneColor(zone: MeterZone, brand: BrandColors) = when (zone) {
    MeterZone.LOADED, MeterZone.COMFORTABLE -> brand.fine
    MeterZone.CAREFUL -> brand.careful
    MeterZone.BROKE -> brand.broke
}

/** The amber tray that says payments are waiting. The only filled block on the screen. */
@Composable
private fun WaitingTray(count: Int, totalMinor: Long, onClick: () -> Unit) {
    val brand = LocalBrand.current
    Surface(
        onClick = onClick,
        color = brand.action,
        contentColor = brand.onAction,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp),
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 payment waiting for you" else "$count payments waiting for you",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${Money.format(totalMinor)} not counted until you accept",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun PaceLine(summary: MonthSummary) {
    val lines = buildList {
        if (summary.daysCounted > 0 && summary.totalMinor > 0) {
            add("${Money.format(summary.dailyAverageMinor, summary.currency)} a day on average")
        }
        summary.projectedMinor?.let { add("on course for ${Money.formatWhole(it, summary.currency)} by the end of the month") }
        if (summary.awayDays > 0) add(awayNote(summary.awayDays))
    }
    if (lines.isNotEmpty()) {
        Text(
            lines.joinToString(", ").replaceFirstChar { it.uppercaseChar() } + ".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
    }
}

@Composable
private fun CategoryShareRow(row: CategoryTotal, largestMinor: Long, currency: String, onClick: () -> Unit) {
    val brand = LocalBrand.current
    val color = brand.category(row.category.colorIndex)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AmountText(Money.format(row.totalMinor, currency))
        }
        ShareBar(fraction = row.totalMinor.toFloat() / largestMinor.toFloat(), color = color)
    }
}

/** "1 day away left out" or "5 days away left out". */
fun awayNote(days: Int): String = if (days == 1) "1 day away left out" else "$days days away left out"

/** Two small tiles on the home screen: how long the savings last, and today's allowance. */
@Composable
private fun InsightTiles(data: InsightsData, onClick: () -> Unit) {
    val brand = LocalBrand.current
    val runway = data.runway
    val until = runway?.lastsUntil
    val ahead = runway?.aheadWholeDays()
    val allowance = data.allowance
    Row(
        Modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Tile(
            value = when {
                until != null -> "${until.dayOfMonth} ${until.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK)}"
                runway != null -> Money.formatWhole(runway.leftMinor)
                else -> "Add"
            },
            unit = when {
                until != null -> if (until.year != data.history.today.year) until.year.toString() else ""
                runway != null -> "left"
                else -> "savings"
            },
            caption = when {
                until != null -> "savings last until"
                runway != null -> "of your savings"
                else -> "to see your runway"
            },
            color = MaterialTheme.colorScheme.onSurface,
            note = ahead?.let {
                when {
                    it > 0 -> (if (it == 1) "1 day ahead" else "$it days ahead") to brand.fine
                    it < 0 -> (if (it == -1) "1 day behind" else "${-it} days behind") to brand.careful
                    else -> "On track" to brand.fine
                }
            },
            onClick = onClick,
            modifier = Modifier.weight(1f),
        )
        val perDay = allowance?.perDayMinor
        Tile(
            value = when {
                perDay != null -> Money.formatWhole(perDay)
                allowance != null -> Money.formatWhole(-allowance.leftMinor)
                else -> "Set"
            },
            unit = when {
                perDay != null -> "a day"
                allowance != null -> "over"
                else -> "a limit"
            },
            caption = when {
                perDay != null -> "left to spend this month"
                allowance != null -> "this month's limit"
                else -> "for a daily allowance"
            },
            color = if (allowance != null && perDay == null) brand.broke else MaterialTheme.colorScheme.onSurface,
            onClick = onClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Tile(
    value: String,
    unit: String,
    caption: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    note: Pair<String, Color>? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = TABULAR),
                    color = color,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            note?.let { (text, tint) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    CategoryDot(tint, size = 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
                }
            }
        }
    }
}
