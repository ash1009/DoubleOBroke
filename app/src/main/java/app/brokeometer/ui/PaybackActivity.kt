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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import app.brokeometer.data.MoneyMoveEntity
import app.brokeometer.data.TransactionEntity
import app.brokeometer.ui.components.AmountText
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.theme.BrokeTheme
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import kotlinx.coroutines.launch

/**
 * The small window from Paying back on a money in alert, or from the Money in page: your recent
 * payments, newest first, with the ones big enough to be paid back on top. Tap the one it was for.
 */
class PaybackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.getLongExtra(Notifier.EXTRA_MOVE_ID, -1L) ?: -1L
        val container = (application as BrokeApp).container
        if (id < 0) {
            finish()
            return
        }

        setContent {
            val theme by container.prefs.theme.collectAsStateWithLifecycle()
            var move by remember { mutableStateOf<MoneyMoveEntity?>(null) }
            var choices by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(id) {
                val loaded = container.money.move(id)
                if (loaded == null) {
                    container.notifier.cancelMoneyIn(id)
                    finish()
                } else {
                    choices = container.money.paybackChoices(id)
                    move = loaded
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
                    val shown = move
                    if (shown != null) {
                        PaybackCard(
                            move = shown,
                            choices = choices,
                            onPick = { tx ->
                                scope.launch {
                                    container.money.linkPayback(id, tx.id)
                                    finish()
                                }
                            },
                            onOther = {
                                scope.launch {
                                    container.money.markOther(id)
                                    finish()
                                }
                            },
                            onCancel = { finish() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaybackCard(
    move: MoneyMoveEntity,
    choices: List<TransactionEntity>,
    onPick: (TransactionEntity) -> Unit,
    onOther: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            Modifier
                .padding(top = 24.dp, bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "${Money.format(move.amountMinor, move.currency)} from ${move.counterparty.ifBlank { "someone" }}",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Which payment is it paying back? That payment will then count only your share.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (choices.isEmpty()) {
                Text(
                    "No payments in ${move.currency} in the last ${app.brokeometer.capture.MoneyAgent.CHOICE_DAYS} days.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(choices, key = { it.id }) { tx ->
                        ChoiceRow(tx, onClick = { onPick(tx) })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                QuietButton("Cancel", onClick = onCancel)
                QuietButton("Not paying back", onClick = onOther, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChoiceRow(tx: TransactionEntity, onClick: () -> Unit) {
    val paid = tx.paidMinor
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tx.merchantName.ifBlank { tx.note.ifBlank { "Payment" } },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val second = buildString {
                append(Dates.pretty(tx.day))
                if (tx.source == app.brokeometer.data.TxSource.BANK) append(", ${Dates.clock(tx.occurredAt)}")
                if (paid != null) append(", your share ${Money.format(tx.amountMinor, tx.currency)}")
            }
            Text(
                second,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        AmountText(Money.format(paid ?: tx.amountMinor, tx.currency))
    }
}
