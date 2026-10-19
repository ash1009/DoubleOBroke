package app.brokeometer.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.components.GoalsSheet
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.util.Dates
import app.brokeometer.util.Money

/** Money's pages, in order. Setup is last, as on every part of the app. */
internal val moneyPageNames = listOf("This month", "Payments", "Insights", "Months", "Categories", "Money in", "Setup")

/** Money's Setup: your goals, and the bank and Oyster balances the app keeps up to date. */
@Composable
internal fun MoneySetupPage(vm: AppViewModel, listState: LazyListState) {
    val goals by vm.goals.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    var editingGoals by remember { mutableStateOf(false) }
    var editingBalances by remember { mutableStateOf(false) }

    CardList(listState) {
        item("goals") {
            PageCard {
                CardHead("Goals", "What Insights measures your spending against. Everything is optional.")
                SetupLine("Savings", goals.savingsMinor?.let { Money.format(it) })
                SetupLine("Make them last until", goals.lastUntil?.let { Dates.pretty(it) })
                SetupLine("Monthly limit", goals.monthlyLimitMinor?.let { Money.format(it) })
                SetupLine("No-spend days a month", goals.noSpendTarget?.toString())
                SetupLine("Category caps", goals.categoryCaps.size.takeIf { it > 0 }?.let { if (it == 1) "1 category" else "$it categories" })
                QuietButton("Change goals", onClick = { editingGoals = true }, modifier = Modifier.fillMaxWidth(), height = 44.dp)
            }
        }
        item("balances") {
            PageCard {
                CardHead("Balances", "Typed in once, then kept up to date from payments, money in, top-ups and trips.")
                val b = balances
                SetupLine("Bank", b?.let { Money.format(it.bankMinor) })
                SetupLine("Oyster", b?.let { Money.format(it.oysterMinor) })
                QuietButton(if (b == null) "Add balances" else "Change balances", onClick = { editingBalances = true }, modifier = Modifier.fillMaxWidth(), height = 44.dp)
            }
        }
    }

    if (editingGoals) {
        GoalsSheet(
            goals = goals,
            catalog = catalog,
            insights = insights,
            onDismiss = { editingGoals = false },
            onSave = { vm.saveGoals(it) },
        )
    }
    if (editingBalances) {
        val b = balances
        BalanceDialog(
            bankMinor = b?.bankMinor,
            oysterMinor = b?.oysterMinor,
            onSave = { bank, oyster -> vm.setBalances(bank, oyster) },
            onDismiss = { editingBalances = false },
        )
    }
}

/** A setting's name and its value, or "Not set". */
@Composable
private fun SetupLine(name: String, value: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value ?: "Not set",
            style = MaterialTheme.typography.bodyLarge,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
