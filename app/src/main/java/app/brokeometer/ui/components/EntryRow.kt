package app.brokeometer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Place
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.brokeometer.data.Catalog
import app.brokeometer.data.TransactionEntity
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Money

/** One filed payment: where, what it was filed as, and how much. Tap to edit. */
@Composable
fun EntryRow(
    entry: TransactionEntity,
    catalog: Catalog,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCategory: Boolean = true,
) {
    val brand = LocalBrand.current
    val category = catalog.categoryOfSub(entry.subcategoryId)
    val sub = catalog.sub(entry.subcategoryId)
    // Entries from the old sheet have no shop name: show their note, or what they were filed as.
    val title = entry.merchantName.ifBlank { entry.note.ifBlank { sub?.name ?: "Payment" } }
    val titleIsSub = entry.merchantName.isBlank() && entry.note.isBlank()
    val filedAs = when {
        !showCategory -> null
        titleIsSub -> category?.name
        else -> catalog.label(entry.subcategoryId)
    }
    // Friends paid some back: the amount is your share, and this says what was paid.
    val paidBack = entry.paidMinor?.let { paid ->
        "paid ${Money.format(paid, entry.currency)}, ${Money.format(paid - entry.amountMinor, entry.currency)} back"
    }
    val secondLine = listOfNotNull(filedAs, entry.place.ifBlank { null }, detail.ifBlank { null }, paidBack).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(category?.let { brand.category(it.colorIndex) } ?: MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondLine.isNotEmpty()) {
                Text(
                    secondLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (entry.latitude != null && entry.longitude != null) {
            // The spot was saved: open the payment for See on map.
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Rounded.Place,
                contentDescription = "Location saved",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        AmountText(Money.format(entry.amountMinor, entry.currency))
    }
}
