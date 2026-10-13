package app.brokeometer.ui.screens

import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.data.Catalog
import app.brokeometer.data.TransactionEntity
import app.brokeometer.data.TxSource
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.CategoryTotal
import app.brokeometer.ui.components.AmountText
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.CategoryDot
import app.brokeometer.ui.components.DailyBars
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.EntryDraft
import app.brokeometer.ui.components.EntryRow
import app.brokeometer.ui.components.EntrySheet
import app.brokeometer.ui.components.ShareBar
import app.brokeometer.ui.summarise
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import java.time.YearMonth

/** One month at a time, by category or by day. The month switch is at the bottom, by the thumb. */
@Composable
fun MonthScreen(vm: AppViewModel) {
    val month by vm.selectedMonth.collectAsStateWithLifecycle()
    val entries by vm.selectedMonthEntries.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val away by vm.away.collectAsStateWithLifecycle()

    // Opening Months always starts on the current month. Turning the phone keeps the month on screen.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            vm.showMonth(YearMonth.from(today))
            started = true
        }
    }

    val summary = remember(entries, catalog, month, today, away) { summarise(entries, catalog, month, today, away) }
    val currency = summary.currency
    var byDay by rememberSaveable { mutableStateOf(false) }
    var expanded by remember(month) { mutableStateOf(setOf<Long>()) }
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    val isCurrent = month == YearMonth.from(today)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Inside the app's frame: its top and bottom bars already keep clear of the phone's own bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            MonthSwitcher(
                title = Dates.monthTitle(month),
                onPrevious = { vm.stepMonth(-1) },
                onNext = { vm.stepMonth(1) },
                nextEnabled = month.isBefore(YearMonth.from(today)),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    AmountText(
                        Money.format(summary.totalMinor, currency),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val facts = buildList {
                        add(if (summary.entryCount == 1) "1 payment" else "${summary.entryCount} payments")
                        if (summary.totalMinor > 0 && summary.daysCounted > 0) {
                            add("${Money.format(summary.dailyAverageMinor, currency)} a day")
                        }
                        if (summary.awayDays > 0) add(awayNote(summary.awayDays))
                    }
                    Text(
                        facts.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    summary.otherCurrencies.forEach { (currency, amount) ->
                        Text(
                            "Plus ${Money.format(amount, currency)} in $currency, not converted yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    DailyBars(
                        summary.perDay,
                        todayIndex = if (isCurrent) today.dayOfMonth - 1 else -1,
                        awayIndexes = summary.awayIndexes,
                    )
                    Spacer(Modifier.height(20.dp))
                    ViewSwitch(byDay = byDay, onChange = { byDay = it })
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (entries.isEmpty()) {
                item(key = "empty") {
                    EmptyNote(
                        title = "Nothing filed in ${Dates.monthTitle(month)}",
                        body = "Payments you accept, and ones you add by hand, are listed here.",
                    )
                }
            } else if (byDay) {
                val days = entries.groupBy { it.day }.toSortedMap(compareByDescending<String> { it })
                days.forEach { (day, list) ->
                    item(key = "day-$day") {
                        DayHeader(day, list.filter { it.currency == currency }.sumOf { it.amountMinor }, currency)
                    }
                    items(list, key = { "d-${it.id}" }) { entry ->
                        EntryRow(
                            entry = entry,
                            catalog = catalog,
                            detail = if (entry.source == TxSource.BANK) Dates.clock(entry.occurredAt) else "",
                            onClick = { editing = entry },
                        )
                    }
                }
            } else {
                val largest = summary.byCategory.firstOrNull()?.totalMinor?.coerceAtLeast(1L) ?: 1L
                summary.byCategory.forEach { row ->
                    val open = row.category.id in expanded
                    item(key = "c-${row.category.id}") {
                        CategoryHeader(
                            row = row,
                            largestMinor = largest,
                            currency = currency,
                            open = open,
                            onToggle = {
                                expanded = if (open) expanded - row.category.id else expanded + row.category.id
                            },
                        )
                    }
                    item(key = "cx-${row.category.id}") {
                        AnimatedVisibility(visible = open) {
                            CategoryDetail(row = row, catalog = catalog, currency = currency, onEdit = { editing = it })
                        }
                    }
                }
            }
        }
    }

    val open = editing
    if (open != null) {
        EntrySheet(
            entry = open,
            catalog = catalog,
            onDismiss = { editing = null },
            onSave = { d: EntryDraft -> vm.saveEntry(open, d.amountMinor, d.label, d.date, d.subcategoryId, d.note) },
            onDelete = { vm.deleteEntry(open.id) },
        )
    }
}

@Composable
private fun MonthSwitcher(title: String, onPrevious: () -> Unit, onNext: () -> Unit, nextEnabled: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month", modifier = Modifier.size(30.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext, enabled = nextEnabled, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month", modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** Two-way switch between the category view and the day view. */
@Composable
private fun ViewSwitch(byDay: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        SwitchHalf("By category", selected = !byDay, onClick = { onChange(false) }, modifier = Modifier.weight(1f))
        SwitchHalf("By day", selected = byDay, onClick = { onChange(true) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwitchHalf(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val brand = LocalBrand.current
    Box(
        modifier
            .clip(CircleShape)
            .background(if (selected) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) brand.onAction else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayHeader(day: String, totalMinor: Long, currency: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Dates.prettyShort(day), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        AmountText(
            Money.format(totalMinor, currency),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryHeader(
    row: CategoryTotal,
    largestMinor: Long,
    currency: String,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val brand = LocalBrand.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.category.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            AmountText(Money.format(row.totalMinor, currency), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (open) "Hide details" else "Show details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ShareBar(
            fraction = row.totalMinor.toFloat() / largestMinor.toFloat(),
            color = brand.category(row.category.colorIndex),
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

@Composable
private fun CategoryDetail(
    row: CategoryTotal,
    catalog: Catalog,
    currency: String,
    onEdit: (TransactionEntity) -> Unit,
) {
    val brand = LocalBrand.current
    Column(Modifier.padding(bottom = 8.dp)) {
        row.subs.sortedByDescending { it.totalMinor }.forEach { sub ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryDot(brand.category(row.category.colorIndex), size = 8.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    sub.sub.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                AmountText(
                    Money.format(sub.totalMinor, currency),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            sub.entries.forEach { entry ->
                EntryRow(
                    entry = entry,
                    catalog = catalog,
                    detail = Dates.prettyShort(entry.day),
                    showCategory = false,
                    onClick = { onEdit(entry) },
                )
            }
        }
    }
}
