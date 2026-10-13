package app.brokeometer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.brokeometer.data.Catalog
import app.brokeometer.ui.theme.LocalBrand

data class PickerOption(val id: Long, val name: String, val color: Color? = null)

/** A labelled dropdown: tap the field, pick from the list. */
@Composable
fun PickerField(
    label: String,
    value: String,
    options: List<PickerOption>,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    placeholder: String = "Choose",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .heightIn(min = 60.dp)
                    .padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (valueColor != null && value.isNotEmpty()) {
                            CategoryDot(valueColor)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            value.ifEmpty { placeholder },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (value.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 220.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name, style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = if (option.color != null) {
                        { CategoryDot(option.color) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onPick(option.id)
                    },
                )
            }
        }
    }
}

/**
 * The two dropdowns, labelled Main and Sub. Picking a different Main clears Sub,
 * because "Others" exists under most main categories.
 */
@Composable
fun MainSubPickers(
    catalog: Catalog,
    categoryId: Long?,
    subcategoryId: Long?,
    onChange: (categoryId: Long?, subcategoryId: Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = LocalBrand.current
    val category = catalog.category(categoryId)
    val sub = catalog.sub(subcategoryId)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PickerField(
            label = "Main",
            value = category?.name.orEmpty(),
            valueColor = category?.let { brand.category(it.colorIndex) },
            options = catalog.categories.map { PickerOption(it.id, it.name, brand.category(it.colorIndex)) },
            onPick = { picked ->
                if (picked != categoryId) {
                    val subs = catalog.subsOf(picked)
                    onChange(picked, if (subs.size == 1) subs.first().id else null)
                }
            },
        )
        PickerField(
            label = "Sub",
            value = sub?.name.orEmpty(),
            options = catalog.subsOf(categoryId).map { PickerOption(it.id, it.name) },
            placeholder = if (categoryId == null) "Pick Main first" else "Choose",
            onPick = { picked -> onChange(categoryId, picked) },
        )
    }
}
