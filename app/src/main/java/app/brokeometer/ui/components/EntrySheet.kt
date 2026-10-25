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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.brokeometer.data.Catalog
import app.brokeometer.data.TransactionEntity
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What the entry sheet hands back when Save is pressed. */
data class EntryDraft(
    val amountMinor: Long,
    val label: String,
    val date: LocalDate,
    val subcategoryId: Long,
    val note: String,
    val currency: String = "GBP",
    val place: String = "",
)

/** What a new entry starts with, for example what was understood from a spoken payment. */
data class EntryStart(
    val amountMinor: Long?,
    val label: String,
    val note: String,
    val date: LocalDate,
    val subcategoryId: Long?,
    val currency: String,
    val place: String,
)

/**
 * The sheet for adding an entry by hand or changing one. It slides up from the bottom,
 * so every control is within reach of a thumb.
 * [entry] is null when adding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(
    entry: TransactionEntity?,
    catalog: Catalog,
    onDismiss: () -> Unit,
    onSave: (EntryDraft) -> Unit,
    onDelete: (() -> Unit)?,
    start: EntryStart? = null,
    /** For a bank payment that was money put on the Oyster card: it moves to Money in as a top-up. */
    onTopUp: (() -> Unit)? = null,
    /** For a bank payment that was a deposit you get back: it moves to Money in, and isn't spending. */
    onDeposit: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val currency = entry?.currency ?: start?.currency ?: "GBP"
    var amount by remember { mutableStateOf((entry?.amountMinor ?: start?.amountMinor)?.let { Money.plain(it) }.orEmpty()) }
    var label by remember { mutableStateOf(entry?.merchantName ?: start?.label.orEmpty()) }
    var note by remember { mutableStateOf(entry?.note ?: start?.note.orEmpty()) }
    var date by remember { mutableStateOf(entry?.let { Dates.parseDay(it.day) } ?: start?.date ?: LocalDate.now()) }
    var subId by remember { mutableStateOf(entry?.subcategoryId ?: start?.subcategoryId) }
    var categoryId by remember { mutableStateOf(catalog.categoryOfSub(entry?.subcategoryId ?: start?.subcategoryId)?.id) }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val amountMinor = Money.parseToMinor(amount)
    val canSave = amountMinor != null && subId != null

    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            then()
            onDismiss()
        }
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
            Text(
                if (entry == null) "Add a payment" else "Edit payment",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (entry != null) PaidWhere(entry)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { text -> amount = text.filter { it.isDigit() || it == '.' }.take(10) },
                    label = { Text("Amount") },
                    prefix = { Text(Money.symbol(currency)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                )
                PickerLikeField(
                    label = "Date",
                    value = Dates.pretty(date),
                    onClick = { pickingDate = true },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(60) },
                label = { Text("Where") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            MainSubPickers(
                catalog = catalog,
                categoryId = categoryId,
                subcategoryId = subId,
                onChange = { c, s ->
                    categoryId = c
                    subId = s
                },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(140) },
                label = { Text("Note") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            if (onTopUp != null) {
                QuietButton(
                    text = "It's an Oyster top-up",
                    onClick = { close(onTopUp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onDeposit != null) {
                QuietButton(
                    text = "It's a deposit I get back",
                    onClick = { close(onDeposit) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onDelete != null) {
                    QuietButton(
                        text = if (confirmDelete) "Tap again to delete" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                        onClick = {
                            if (confirmDelete) {
                                close(onDelete)
                            } else {
                                confirmDelete = true
                            }
                        },
                    )
                }
                ActionButton(
                    text = "Save",
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val minor = amountMinor
                        val sub = subId
                        if (minor != null && sub != null) {
                            close { onSave(EntryDraft(minor, label, date, sub, note, currency, start?.place.orEmpty())) }
                        }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (pickingDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text("Use this date") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** A read-only field that looks like the pickers and opens something when tapped. */
@Composable
fun PickerLikeField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.padding(top = 8.dp),
    ) {
        Column(
            Modifier
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

/** The town from the bank alert and, when it was saved, a link to the spot on a map. */
@Composable
private fun PaidWhere(entry: TransactionEntity) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lat = entry.latitude
    val lng = entry.longitude
    if (entry.place.isBlank() && (lat == null || lng == null)) return
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            when {
                entry.place.isNotBlank() -> "Paid in ${entry.place}"
                else -> "Where you paid is saved"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (lat != null && lng != null) {
            TextButton(onClick = {
                val label = android.net.Uri.encode(entry.merchantName.ifBlank { "Payment" })
                val uri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
                try {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                } catch (e: android.content.ActivityNotFoundException) {
                    // No maps app on the phone.
                }
            }) { Text("See on map", color = LocalBrand.current.action) }
        }
    }
}
