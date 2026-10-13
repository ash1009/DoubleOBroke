package app.brokeometer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.util.Dates

/** What the app did with each bank alert it saw. For working out why a payment is missing. */
@Composable
fun LogScreen(vm: AppViewModel, onBack: () -> Unit) {
    val rows by vm.captureLog.collectAsStateWithLifecycle()
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                items(rows, key = { it.id }) { row ->
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
}
