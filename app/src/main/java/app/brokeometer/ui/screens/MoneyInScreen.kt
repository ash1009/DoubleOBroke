package app.brokeometer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.capture.Notifier
import app.brokeometer.data.MoneyMoveEntity
import app.brokeometer.data.MoveKind
import app.brokeometer.data.TransactionEntity
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.PaybackActivity
import app.brokeometer.ui.components.AmountText
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.util.Money

/**
 * Money that isn't spending: money in at HSBC and Starling, transfers between your own accounts, and
 * Oyster top-ups.
 * A transfer seen at both banks is one row. Tap a row to say what it was.
 */
@Composable
fun MoneyInScreen(vm: AppViewModel) {
    val moves by vm.moneyMoves.collectAsStateWithLifecycle()
    val paidBack by vm.paidBack.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var asking by remember { mutableStateOf<MoneyMoveEntity?>(null) }
    val byId = remember(moves) { moves.associateBy { it.id } }
    // A transfer seen at both banks shows once, as its money in.
    val rows = remember(moves) {
        moves.filter { !(it.direction == MoneyMoveEntity.OUT && it.pairId != null && byId[it.pairId] != null) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        if (rows.isEmpty()) {
            item(key = "empty") {
                EmptyNote(
                    title = "No money in yet",
                    body = "Money that comes into HSBC or Starling shows here. Moving money between your own accounts " +
                        "is noted here too, and never counts as spending. When a friend pays you back, pick the payment " +
                        "and it counts only your share.",
                )
            }
        }
        items(rows, key = { it.id }) { move ->
            MoveRow(move, byId, paidBack, onClick = { asking = move })
        }
    }

    val open = asking
    if (open != null) {
        val brand = LocalBrand.current
        if (open.kind == MoveKind.TOPUP) {
            AlertDialog(
                onDismissRequest = { asking = null },
                title = { Text("Oyster top-up") },
                text = {
                    Text(
                        "${Money.format(open.amountMinor, open.currency)} put on your Oyster card. It moves money from the bank " +
                            "to Oyster, so it isn't spending: your trips count instead. If it was a payment, it goes back to payments waiting."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.notATransfer(open.id); asking = null }) { Text("It was a payment", color = brand.action) }
                },
                dismissButton = { TextButton(onClick = { asking = null }) { Text("Keep") } },
            )
        } else if (open.direction == MoneyMoveEntity.OUT) {
            AlertDialog(
                onDismissRequest = { asking = null },
                title = { Text("Moved to your own account") },
                text = {
                    Text(
                        "${Money.format(open.amountMinor, open.currency)} to ${open.counterparty} looked like money moved " +
                            "between your own accounts, so it isn't counted as spending. If it was a payment, it goes back " +
                            "to payments waiting."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.notATransfer(open.id); asking = null }) { Text("It was a payment", color = brand.action) }
                },
                dismissButton = { TextButton(onClick = { asking = null }) { Text("Keep") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { asking = null },
                title = { Text("${Money.format(open.amountMinor, open.currency)} from ${open.counterparty.ifBlank { "someone" }}") },
                text = {
                    Column {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(context, PaybackActivity::class.java)
                                    .setData(Uri.parse("brokeometer://money/${open.id}"))
                                    .putExtra(Notifier.EXTRA_MOVE_ID, open.id)
                            )
                            asking = null
                        }) { Text("Paying me back for a payment", color = brand.action) }
                        TextButton(onClick = { vm.moneyMine(open.id); asking = null }) { Text("From my own account") }
                        TextButton(onClick = { vm.moneyOther(open.id); asking = null }) { Text("Other money in") }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun MoveRow(
    move: MoneyMoveEntity,
    byId: Map<Long, MoneyMoveEntity>,
    paidBack: Map<Long, TransactionEntity>,
    onClick: () -> Unit,
) {
    val brand = LocalBrand.current
    val partner = move.pairId?.let { byId[it] }
    val time = "${Dates.prettyShort(move.day)}, ${Dates.clock(move.occurredAt)}"
    val title: String
    val detail: String
    var waiting = false
    when {
        move.kind == MoveKind.TOPUP -> {
            title = "Oyster top-up"
            detail = "From ${move.bank} to your Oyster card, $time"
        }
        move.direction == MoneyMoveEntity.OUT -> {
            title = "To ${move.counterparty.ifBlank { "your account" }}"
            detail = "Moved to your own account from ${move.bank}, $time"
        }
        move.kind == MoveKind.TRANSFER && partner != null -> {
            title = "Moved from ${partner.bank} to ${move.bank}"
            detail = "Between your own accounts, $time"
        }
        move.kind == MoveKind.TRANSFER -> {
            title = "From ${move.counterparty.ifBlank { "your account" }}"
            detail = "From your own account into ${move.bank}, $time"
        }
        move.kind == MoveKind.PAYBACK -> {
            val tx = move.paysBackId?.let { paidBack[it] }
            title = "From ${move.counterparty.ifBlank { "someone" }}"
            detail = if (tx != null) "Paying back ${tx.merchantName.ifBlank { "a payment" }} on ${Dates.prettyShort(tx.day)}"
            else "Paying back a payment, $time"
        }
        move.kind == MoveKind.OTHER -> {
            title = "From ${move.counterparty.ifBlank { "someone" }}"
            detail = "Money in at ${move.bank}, $time"
        }
        else -> {
            title = "From ${move.counterparty.ifBlank { "someone" }}"
            detail = "Paying you back? Tap to say. ${move.bank}, $time"
            waiting = true
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (waiting) brand.action else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        AmountText(Money.format(move.amountMinor, move.currency))
    }
}
