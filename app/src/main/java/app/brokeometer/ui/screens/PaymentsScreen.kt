package app.brokeometer.ui.screens

import androidx.compose.foundation.layout.WindowInsets

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import app.brokeometer.ui.AppViewModel.VoiceState
import app.brokeometer.ui.components.EntryStart
import java.time.LocalDate
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.data.Catalog
import app.brokeometer.data.TransactionEntity
import app.brokeometer.data.TxSource
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.AmountText
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.CategoryDot
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.EntryDraft
import app.brokeometer.ui.components.EntryRow
import app.brokeometer.ui.components.EntrySheet
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.util.Money

/**
 * Every payment, newest first. Payments waiting for Accept or Update sit at the top; while any are
 * waiting, the page is called "Waiting for you".
 */
@Composable
fun PaymentsScreen(vm: AppViewModel) {
    val pending by vm.pending.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val guessed = pending.count { it.subcategoryId != null }
    val brand = LocalBrand.current
    // Opens at the top every time.
    val listState = remember { LazyListState() }
    val days = remember(history) { history.groupBy { it.day } }

    // Adding by voice: Android's own speech typing hears it, then the app works out the payment.
    val voice by vm.voice.collectAsStateWithLifecycle()
    val voiceAsked by vm.voiceAsked.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf<EntryStart?>(null) }
    val listener = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) vm.addByVoice(heard)
    }
    val listen: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say it like: 3.40 for metro at Vauxhall")
        try {
            listener.launch(intent)
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("This phone has no voice typing. Turn on Google voice typing in the phone's settings.") }
        }
    }
    LaunchedEffect(voiceAsked) {
        if (voiceAsked) {
            vm.voiceAskHandled()
            listen()
        }
    }
    LaunchedEffect(voice) {
        when (val v = voice) {
            is VoiceState.Saved -> {
                vm.voiceShown()
                scope.launch {
                    val r = snackbar.showSnackbar(v.message, actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) vm.deleteEntry(v.id)
                }
            }
            is VoiceState.Check -> {
                vm.voiceShown()
                val d = v.draft
                start = EntryStart(
                    amountMinor = d.amountMinor,
                    label = d.where.ifBlank { d.note },
                    note = if (d.where.isBlank()) "" else d.note,
                    date = LocalDate.now().minusDays(d.daysAgo.toLong()),
                    subcategoryId = d.subcategoryId,
                    currency = d.currency ?: "GBP",
                    place = d.place,
                )
                adding = true
                scope.launch { snackbar.showSnackbar(v.message) }
            }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Inside the app's frame: its top and bottom bars already keep clear of the phone's own bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Two clear buttons side by side: say it, or type it.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ExtendedFloatingActionButton(
                    text = { Text(if (voice is VoiceState.Working) "Working" else "Voice", style = MaterialTheme.typography.labelLarge) },
                    icon = {
                        if (voice is VoiceState.Working) {
                            CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Rounded.Mic, contentDescription = "Add by voice")
                        }
                    },
                    onClick = { if (voice !is VoiceState.Working) listen() },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = brand.action,
                )
                ExtendedFloatingActionButton(
                    text = { Text("Add", style = MaterialTheme.typography.labelLarge) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = {
                        start = null
                        adding = true
                    },
                    containerColor = brand.action,
                    contentColor = brand.onAction,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            if (pending.isNotEmpty()) {
                if (guessed > 1) {
                    item(key = "accept-all") {
                        TextButton(
                            onClick = { vm.acceptAll() },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Accept all $guessed guesses", color = brand.action) }
                    }
                }
                items(pending, key = { "p-${it.id}" }) { entry ->
                    PendingRow(
                        entry = entry,
                        catalog = catalog,
                        onAccept = { vm.accept(entry.id) },
                        onUpdate = { editing = entry },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                item(key = "history-title") {
                    Text(
                        "Payments",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 4.dp),
                    )
                }
            }
            if (history.isEmpty() && pending.isEmpty()) {
                item(key = "empty") {
                    EmptyNote(
                        title = "No payments yet",
                        body = "Pay with your HSBC card and the payment lands here to accept. Cash goes in with Add.",
                    )
                }
            }
            days.forEach { (day, list) ->
                item(key = "d-$day") { DayHeader(day, list) }
                items(list, key = { "h-${it.id}" }) { entry ->
                    EntryRow(
                        entry = entry,
                        catalog = catalog,
                        detail = if (entry.source == TxSource.BANK) Dates.clock(entry.occurredAt) else "",
                        onClick = { editing = entry },
                    )
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
    if (adding) {
        EntrySheet(
            entry = null,
            catalog = catalog,
            onDismiss = {
                adding = false
                start = null
            },
            onSave = { d: EntryDraft -> vm.addEntry(d.amountMinor, d.label, d.date, d.subcategoryId, d.note, d.currency, d.place) },
            onDelete = null,
            start = start,
        )
    }
}

@Composable
private fun DayHeader(day: String, list: List<TransactionEntity>) {
    val totals = list.groupBy { it.currency }.map { (currency, l) -> Money.format(l.sumOf { it.amountMinor }, currency) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(Dates.prettyShort(day), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(
            totals.joinToString(" + "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PendingRow(
    entry: TransactionEntity,
    catalog: Catalog,
    onAccept: () -> Unit,
    onUpdate: () -> Unit,
) {
    val brand = LocalBrand.current
    val category = catalog.categoryOfSub(entry.subcategoryId)
    val label = catalog.label(entry.subcategoryId)
    val whenText = "${Dates.prettyShort(entry.day)}, ${Dates.clock(entry.occurredAt)}"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.merchantName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(whenText, entry.place).filter { it.isNotBlank() }.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            AmountText(Money.format(entry.amountMinor, entry.currency), style = MaterialTheme.typography.titleLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label != null && category != null) {
                CategoryDot(brand.category(category.colorIndex))
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "No guess for this one",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (label != null) {
                QuietButton("Update", onClick = onUpdate, height = 44.dp)
                Spacer(Modifier.width(8.dp))
                ActionButton("Accept", onClick = onAccept, height = 44.dp)
            } else {
                ActionButton("Choose", onClick = onUpdate, height = 44.dp)
            }
        }
    }
}
