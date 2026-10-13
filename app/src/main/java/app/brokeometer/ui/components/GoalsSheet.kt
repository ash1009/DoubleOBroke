package app.brokeometer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.brokeometer.data.Catalog
import app.brokeometer.insights.Goals
import app.brokeometer.ui.InsightsData
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Where the owner sets savings, a date, a monthly limit, a no-spend goal and category caps. */
@Composable
fun GoalsSheet(
    goals: Goals,
    catalog: Catalog,
    insights: InsightsData?,
    onDismiss: () -> Unit,
    onSave: (Goals) -> Unit,
) {
    val brand = LocalBrand.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var savings by remember { mutableStateOf(goals.savingsMinor?.let { Money.plain(it) }.orEmpty()) }
    var lastUntil by remember { mutableStateOf(goals.lastUntil) }
    var limit by remember { mutableStateOf(goals.monthlyLimitMinor?.let { Money.plain(it) }.orEmpty()) }
    var noSpend by remember { mutableStateOf(goals.noSpendTarget?.toString().orEmpty()) }
    val caps = remember {
        mutableStateMapOf<Long, String>().apply { goals.categoryCaps.forEach { (id, cap) -> put(id, Money.plain(cap)) } }
    }
    var pickingDate by remember { mutableStateOf(false) }

    val usualByCategory = insights?.averages?.byCategory.orEmpty()

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Goals", style = MaterialTheme.typography.headlineSmall)
            Text(
                "All of these are optional. Leave a box empty to turn that goal off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GoalHeading("Savings")
            MoneyField(
                value = savings,
                onChange = { savings = it },
                label = "What you have saved right now",
                help = "Spending from now on comes off this. Update it whenever you check your balance.",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PickerLikeField(
                    label = "Make it last until",
                    value = lastUntil?.let { Dates.pretty(it) } ?: "No date",
                    onClick = { pickingDate = true },
                    modifier = Modifier.weight(1f),
                )
                if (lastUntil != null) {
                    TextButton(onClick = { lastUntil = null }, modifier = Modifier.padding(top = 8.dp)) { Text("Clear") }
                }
            }

            GoalHeading("Each month")
            MoneyField(
                value = limit,
                onChange = { limit = it },
                label = "Monthly limit",
                help = insights?.usualMonthMinor?.let { "A usual month is about ${Money.formatWhole(it)}." }
                    ?: "Gives you a daily allowance on the Insights page.",
            )
            OutlinedTextField(
                value = noSpend,
                onValueChange = { text -> noSpend = text.filter { it.isDigit() }.take(2) },
                label = { Text("No-spend days a month") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            GoalHeading("Category caps")
            Text(
                "You get a nudge at 80% of a cap and another if you go over.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            catalog.categories.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(brand.category(category.colorIndex))
                    Spacer(Modifier.width(12.dp))
                    Text(category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = caps[category.id].orEmpty(),
                        onValueChange = { text -> caps[category.id] = cleanMoney(text) },
                        placeholder = {
                            Text(usualByCategory[category.id]?.takeIf { it > 0 }?.let { Money.plain(it) }.orEmpty())
                        },
                        prefix = { Text("£") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.width(140.dp),
                    )
                }
            }
            Text(
                "The grey figure in each box is your average a month there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            ActionButton(
                text = "Save goals",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val newSavings = Money.parseToMinor(savings)
                    val savingsChanged = newSavings != goals.savingsMinor
                    onSave(
                        Goals(
                            savingsMinor = newSavings,
                            savingsAt = when {
                                newSavings == null -> null
                                savingsChanged || goals.savingsAt == null -> System.currentTimeMillis()
                                else -> goals.savingsAt
                            },
                            lastUntil = lastUntil,
                            monthlyLimitMinor = Money.parseToMinor(limit),
                            noSpendTarget = noSpend.toIntOrNull()?.takeIf { it in 1..31 },
                            categoryCaps = caps.mapNotNull { (id, text) -> Money.parseToMinor(text)?.let { id to it } }.toMap(),
                        )
                    )
                    close()
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (pickingDate) {
        val tomorrow = LocalDate.now().plusDays(1)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (lastUntil ?: LocalDate.now().plusMonths(6))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(tomorrow)
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { lastUntil = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("Use this date") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun GoalHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit, label: String, help: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(cleanMoney(it)) },
        label = { Text(label) },
        prefix = { Text("£") },
        supportingText = { Text(help) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun cleanMoney(text: String): String = text.filter { it.isDigit() || it == '.' }.take(10)
