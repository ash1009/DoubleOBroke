package app.brokeometer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.data.LogKinds
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates

/**
 * What the app did with each alert it saw (bank alerts, email-app alerts) and each News briefing. For
 * working out why something is missing. Filtered by kind at the top.
 */
@Composable
fun LogScreen(vm: AppViewModel, onBack: () -> Unit) {
    val rows by vm.captureLog.collectAsStateWithLifecycle()
    // The kind shown, by name; empty for all of them.
    var kind by rememberSaveable { mutableStateOf("") }
    val picked = LogKinds.Kind.entries.firstOrNull { it.name == kind }
    val counts = remember(rows) { rows.groupingBy { LogKinds.kindOf(it.packageName, it.outcome) }.eachCount() }
    val shown = remember(rows, picked) { if (picked == null) rows else rows.filter { LogKinds.kindOf(it.packageName, it.outcome) == picked } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BrokeTopBar(title = "Alert log", onBack = onBack) },
    ) { padding ->
        if (rows.isEmpty()) {
            EmptyNote(
                title = "No alerts seen yet",
                body = "Each bank alert and email-app notification the app reads is listed here with what it did. " +
                    "If this stays empty after a payment or an email, notification access is off.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item("filter") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LogChip("All ${rows.size}", on = picked == null) { kind = "" }
                    LogKinds.Kind.entries.forEach { k ->
                        LogChip("${k.label} ${counts[k] ?: 0}", on = picked == k) { kind = k.name }
                    }
                }
            }
            if (shown.isEmpty()) {
                item("none") {
                    Text(
                        "Nothing of this kind yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(shown, key = { it.id }) { row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(row.outcome, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Dates.pretty(Dates.dayOf(row.at))}, ${Dates.clock(row.at)}, ${row.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        row.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun LogChip(text: String, on: Boolean, onClick: () -> Unit) {
    val brand = LocalBrand.current
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) brand.onAction else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}
