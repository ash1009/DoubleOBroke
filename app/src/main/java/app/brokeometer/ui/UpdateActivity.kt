package app.brokeometer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.BrokeApp
import app.brokeometer.capture.Notifier
import app.brokeometer.data.Catalog
import app.brokeometer.data.TransactionEntity
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.AmountText
import app.brokeometer.ui.components.MainSubPickers
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.theme.BrokeTheme
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import kotlinx.coroutines.launch

/**
 * The small window that opens from the Update button on a payment alert: the payment,
 * two dropdowns labelled Main and Sub, and Save. It sits at the bottom of the screen over
 * whatever was open, and closes itself after saving.
 */
class UpdateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.getLongExtra(Notifier.EXTRA_TX_ID, -1L) ?: -1L
        val container = (application as BrokeApp).container
        if (id < 0) {
            finish()
            return
        }

        setContent {
            val theme by container.prefs.theme.collectAsStateWithLifecycle()
            var entry by remember { mutableStateOf<TransactionEntity?>(null) }
            var catalog by remember { mutableStateOf(Catalog()) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(id) {
                container.repository.ensureSeeded()
                val loaded = container.repository.dao.transaction(id)
                if (loaded == null) {
                    container.notifier.cancel(id)
                    finish()
                } else {
                    catalog = container.repository.catalog()
                    entry = loaded
                }
            }

            BrokeTheme(theme) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { finish() },
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val shown = entry
                    if (shown != null) {
                        UpdateCard(
                            entry = shown,
                            catalog = catalog,
                            onCancel = { finish() },
                            onSave = { subId ->
                                scope.launch {
                                    if (container.repository.setCategory(id, subId)) container.notifier.cancel(id)
                                    finish()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    entry: TransactionEntity,
    catalog: Catalog,
    onCancel: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var categoryId by remember { mutableStateOf(catalog.categoryOfSub(entry.subcategoryId)?.id) }
    var subId by remember { mutableStateOf(entry.subcategoryId) }

    Surface(
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            // Taps on the card itself must not close the window.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.merchantName,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${Dates.pretty(entry.day)}, ${Dates.clock(entry.occurredAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                AmountText(
                    Money.format(entry.amountMinor, entry.currency),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            MainSubPickers(
                catalog = catalog,
                categoryId = categoryId,
                subcategoryId = subId,
                onChange = { c, s ->
                    categoryId = c
                    subId = s
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietButton("Cancel", onClick = onCancel)
                ActionButton(
                    text = "Save",
                    enabled = subId != null,
                    onClick = { subId?.let(onSave) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
