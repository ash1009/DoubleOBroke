package app.brokeometer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.insights.recap
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.ui.theme.TABULAR
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import java.time.format.TextStyle
import java.util.Locale

/** A year looked back on, one big fact at a time. */
@Composable
fun RecapScreen(vm: AppViewModel, onBack: () -> Unit) {
    val data by vm.insights.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val year by vm.recapYear.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val d = data
    val recap = remember(d, year) { d?.let { recap(it.history, year) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BrokeTopBar(title = "Your $year", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp),
        ) {
            if (d != null && d.years.size > 1) {
                item("years") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(d.years) { y ->
                            val selected = y == year
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { vm.showRecap(y) }
                                    .padding(horizontal = 18.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    y.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) brand.onAction else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (recap == null) {
                item("empty") { EmptyNote("Nothing for $year", "Spending filed in $year shows up here.") }
                return@LazyColumn
            }
            val isThisYear = year == java.time.LocalDate.now().year
            item("total") {
                Fact(
                    big = Money.formatWhole(recap.totalMinor),
                    line = "spent in $year${if (isThisYear) " so far" else ""}, across ${recap.payments} payments " +
                        "and ${recap.months} ${if (recap.months == 1) "month" else "months"}. " +
                        "About ${Money.formatWhole(recap.monthlyAverageMinor)} in a usual month.",
                    accent = brand.action,
                )
            }
            recap.topCategories.firstOrNull()?.let { (id, minor) ->
                val category = catalog.category(id)
                item("topcat") {
                    val share = if (recap.totalMinor > 0) minor * 100 / recap.totalMinor else 0
                    val others = recap.topSubcategories.mapNotNull { (sid, m) ->
                        catalog.sub(sid)?.let { "${it.name} ${Money.formatWhole(m)}" }
                    }
                    Fact(
                        big = category?.name ?: "Top category",
                        line = "took $share% of the money, ${Money.formatWhole(minor)}. Biggest lines: ${others.joinToString(", ")}.",
                        accent = category?.let { brand.category(it.colorIndex) } ?: brand.action,
                    )
                }
            }
            recap.biggestDay?.let { (day, minor) ->
                item("bigday") {
                    Fact(big = Money.formatWhole(minor), line = "on your biggest day, ${Dates.pretty(day)}.", accent = brand.broke)
                }
            }
            if (recap.priciestMonth != null && recap.cheapestMonth != null) {
                item("months") {
                    Fact(
                        big = Dates.monthName(recap.priciestMonth.first),
                        line = "was the priciest month at ${Money.formatWhole(recap.priciestMonth.second)}." +
                            if (recap.cheapestMonth.first != recap.priciestMonth.first) {
                                " The cheapest was ${Dates.monthName(recap.cheapestMonth.first)} at " +
                                    "${Money.formatWhole(recap.cheapestMonth.second)}."
                            } else {
                                ""
                            },
                        accent = brand.careful,
                    )
                }
            }
            item("nospend") {
                Fact(
                    big = recap.noSpendDays.toString(),
                    line = "days with nothing spent." + if (recap.awayDays > 0) " Plus ${recap.awayDays} days away, not counted." else "",
                    accent = brand.fine,
                )
            }
            recap.busiestDay?.let { day ->
                item("weekday") {
                    Fact(
                        big = day.getDisplayName(TextStyle.FULL, Locale.UK),
                        line = "is the day of the week you spend the most.",
                        accent = brand.category(4),
                    )
                }
            }
            recap.topPlace?.let { place ->
                item("place") {
                    Fact(
                        big = place.name,
                        line = "is your top place: ${place.visits} visits, ${Money.formatWhole(place.totalMinor)} in all.",
                        accent = brand.category(7),
                    )
                }
            }
            if (recap.lateNightCount > 0) {
                item("late") {
                    Fact(
                        big = Money.formatWhole(recap.lateNightMinor),
                        line = "spent after 9pm, over ${recap.lateNightCount} payments.",
                        accent = brand.category(2),
                    )
                }
            }
        }
    }
}

@Composable
private fun Fact(big: String, line: String, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp)
    ) {
        Box(
            Modifier
                .width(6.dp)
                .padding(vertical = 2.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                big,
                style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = TABULAR),
                color = accent,
                maxLines = 2,
            )
            Text(line, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
