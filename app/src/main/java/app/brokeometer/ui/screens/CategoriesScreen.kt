package app.brokeometer.ui.screens

import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.data.CategoryEntity
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.components.AmountText
import app.brokeometer.util.Money
import androidx.compose.ui.text.style.TextOverflow
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.CategoryDot
import app.brokeometer.ui.theme.LocalBrand

/** What the name sheet is adding to: a new main category, or a sub-category of [parent]. */
private data class NameRequest(val parent: CategoryEntity?)

/** The fixed list, with a way to add to it. */
@Composable
fun CategoriesScreen(vm: AppViewModel) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val averages = insights?.averages
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var naming by remember { mutableStateOf<NameRequest?>(null) }
    val brand = LocalBrand.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Inside the app's frame: its top and bottom bars already keep clear of the phone's own bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                ActionButton(
                    text = "Add a main category",
                    onClick = { naming = NameRequest(parent = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "averages-note") {
                Text(
                    if (averages != null && averages.months > 0) {
                        "Figures on the right are the average a month, from your last ${averages.months} " +
                            "months at home. Trip days are left out."
                    } else {
                        "Averages a month show here once there is a finished month with ten or more days at home."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            items(catalog.categories, key = { it.id }) { category ->
                val open = category.id in expanded
                val subs = catalog.subsOf(category.id)
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (open) expanded - category.id else expanded + category.id
                            }
                            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryDot(brand.category(category.colorIndex), size = 12.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(category.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                subs.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        averages?.byCategory?.get(category.id)?.let { avg ->
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                                AmountText(Money.format(avg), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "a month",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = if (open) "Hide" else "Show",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(visible = open) {
                      Column {
                        subs.forEach { sub ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 46.dp, end = 48.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(sub.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                val avg = averages?.bySubcategory?.get(sub.id)
                                AmountText(
                                    avg?.let { Money.format(it) } ?: "£0.00",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { naming = NameRequest(parent = category) }
                                .padding(start = 46.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Add a sub-category to ${category.name}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                      }
                    }
                }
            }
        }
    }

    val request = naming
    if (request != null) {
        NameSheet(
            title = if (request.parent == null) "New main category" else "New sub-category in ${request.parent.name}",
            hint = if (request.parent == null) {
                "It starts with one sub-category, Others. Add more after."
            } else {
                "It appears in the Sub list whenever ${request.parent.name} is picked."
            },
            onDismiss = { naming = null },
            onSave = { name ->
                if (request.parent == null) vm.addCategory(name) else vm.addSubcategory(request.parent.id, name)
                naming = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameSheet(title: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            ActionButton(
                text = "Add",
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
        }
    }
}
