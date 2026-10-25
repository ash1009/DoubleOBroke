package app.brokeometer.ui.screens

import app.brokeometer.ui.components.ActionButton
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import android.os.Build
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.BuildConfig
import app.brokeometer.data.BackupManager
import app.brokeometer.data.ImportPlan
import app.brokeometer.data.RestorePlan
import app.brokeometer.data.Prefs
import app.brokeometer.data.TxSource
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.ImportState
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.CategoryDot
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.components.SectionTitle
import app.brokeometer.ui.openNotificationAccess
import app.brokeometer.ui.rememberSetupStatus
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenLog: () -> Unit, onOpenEmail: () -> Unit = {}) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val keepAlerts by vm.keepAlerts.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val setup = rememberSetupStatus()
    val context = LocalContext.current
    val brand = LocalBrand.current
    val hasTestPayments = pending.any { it.source == TxSource.TEST }
    val importState by vm.importState.collectAsStateWithLifecycle()
    val trips by vm.awayRanges.collectAsStateWithLifecycle()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readImport(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BrokeTopBar(title = "Settings", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            SectionTitle("Look")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp),
            ) {
                ThemeChoice("Dark", theme == Prefs.THEME_DARK, { vm.setTheme(Prefs.THEME_DARK) }, Modifier.weight(1f))
                ThemeChoice("Light", theme == Prefs.THEME_LIGHT, { vm.setTheme(Prefs.THEME_LIGHT) }, Modifier.weight(1f))
                ThemeChoice(
                    "Same as phone",
                    theme == Prefs.THEME_SYSTEM,
                    { vm.setTheme(Prefs.THEME_SYSTEM) },
                    Modifier.weight(1.4f),
                )
            }

            HomeCardsSettings(vm)
            BottomBarSettings(vm)

            SectionTitle("Bank alerts")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { openNotificationAccess(context) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryDot(if (setup.readsBankAlerts) brand.fine else brand.broke, size = 12.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (setup.readsBankAlerts) "Reading bank payment alerts" else "Not reading bank alerts",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (setup.readsBankAlerts) {
                            "HSBC Money Out alerts and ICICI debit SMS become entries. Everything else is ignored."
                        } else {
                            "Tap to open notification access and turn on Double O Broke."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingRow(
                title = "Alert log",
                body = "What the app did with each bank alert and email notification it saw. Useful when something is missing.",
                onClick = onOpenLog,
            )

            SectionTitle("Email")
            SettingRow(
                title = "Email accounts and what matters",
                body = "Add Gmail and Yahoo, choose which kinds of email get a notification, and see your subscriptions.",
                onClick = onOpenEmail,
            )

            SectionTitle("Security")
            LockSetting(vm)

            SectionTitle("Where you paid")
            LocationSetting(vm)

            SectionTitle("AI for new shops")
            AiSetting(vm)

            SectionTitle("Your alerts")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.setKeepAlerts(!keepAlerts) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep each alert until you deal with it", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The alert stays in the shade until you press Accept or Update.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = keepAlerts,
                    onCheckedChange = { vm.setKeepAlerts(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = brand.action,
                        checkedThumbColor = brand.onAction,
                    ),
                )
            }
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Try it", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sends a made-up £4.20 Subway payment through the app, so you can see the alert " +
                        "and press its buttons. It is marked as a test and never counted unless you accept it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuietButton("Send a test payment", onClick = { vm.sendTestPayment() })
                    if (hasTestPayments) {
                        QuietButton("Remove tests", onClick = { vm.removeTestPayments() })
                    }
                }
            }

            SectionTitle("Backup")
            BackupSection(vm)
            SettingRow(
                title = "Restore or import",
                body = "Restore a Double O Broke backup, or add past spending from a CSV file. " +
                    "Nothing already in the app is changed or deleted.",
                onClick = { pickFile.launch(arrayOf("*/*")) },
            )
            if (trips.isNotEmpty()) {
                Column(
                    Modifier.padding(start = 66.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Trips, left out of averages", style = MaterialTheme.typography.titleSmall)
                    trips.forEach { trip ->
                        Row {
                            Text(
                                trip.label.ifBlank { "Away" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                tripDates(trip.startDay, trip.endDay),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Double O Broke ${BuildConfig.VERSION_NAME}. Your spending stays on this phone and in your own backups. " +
                    "Only dates go online for exchange rates. If you set them up, new shop names go to Gemini " +
                    "and email senders and subjects go to Claude or Gemini to be sorted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }

    ImportDialog(state = importState, onConfirm = { vm.confirmImport() }, onDismiss = { vm.dismissImport() })
}

@Composable
private fun ImportDialog(state: ImportState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val brand = LocalBrand.current
    when (state) {
        ImportState.Idle -> Unit
        ImportState.Working -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Reading the file") },
            text = {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = brand.action)
                }
            },
        )
        is ImportState.Ready -> {
            val plan = state.plan
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        if (plan.rows.isEmpty()) "Add ${plural(plan.away.size, "trip")}?"
                        else "Add ${plural(plan.rows.size, "entry", "entries")}?"
                    )
                },
                text = { PlanFacts(plan) },
                confirmButton = {
                    TextButton(onClick = onConfirm) { Text("Import", color = brand.action) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        is ImportState.NothingNew -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nothing new in this file") },
            text = { PlanFacts(state.plan) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = brand.action) } },
        )
        is ImportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Imported") },
            text = {
                Text(
                    buildList {
                        add("Added ${plural(state.added, "entry", "entries")}")
                        if (state.trips > 0) add(plural(state.trips, "trip"))
                    }.joinToString(" and ") + ". You will find them under Months."
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = brand.action) } },
        )
        is ImportState.RestoreReady -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Restore this backup?") },
            text = { RestoreFacts(state.plan) },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Restore", color = brand.action) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is ImportState.RestoreNothingNew -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nothing new in this backup") },
            text = { RestoreFacts(state.plan) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = brand.action) } },
        )
        is ImportState.Restored -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Restored") },
            text = {
                val r = state.result
                Text(
                    buildList {
                        add("Added ${plural(r.added, "entry", "entries")}")
                        if (r.trips > 0) add(plural(r.trips, "trip"))
                        if (r.categories > 0) add(plural(r.categories, "category", "categories"))
                        if (r.goals) add("your goals")
                        if (r.vault) add("your passwords (open them with your master password)")
                        if (r.notes > 0) add(plural(r.notes, "note"))
                    }.joinToString(", ") + "."
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = brand.action) } },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Could not import") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = brand.action) } },
        )
    }
}

@Composable
private fun RestoreFacts(plan: RestorePlan) {
    val lines = buildList {
        val backup = plan.backup
        if (backup.exportedAt > 0) add("Backup from ${Dates.pretty(Dates.localDate(backup.exportedAt))}, ${Dates.clock(backup.exportedAt)}.")
        val first = plan.firstDay
        val last = plan.lastDay
        if (plan.rows.isNotEmpty() && first != null && last != null) {
            add("Adds ${plural(plan.rows.size, "entry", "entries")}, from ${Dates.pretty(first)} to ${Dates.pretty(last)}.")
        }
        if (plan.away.isNotEmpty()) add("Adds ${plural(plan.away.size, "trip")}.")
        if (plan.newCategories.isNotEmpty()) add("Adds categories you made: ${plan.newCategories.joinToString(", ")}.")
        val extraSubs = plan.missingSubs.size - plan.newCategories.size
        if (plan.newCategories.isEmpty() && extraSubs > 0) add("Adds ${plural(extraSubs, "sub-category", "sub-categories")} you made.")
        if (plan.restoresGoals) add("Brings back your goals.")
        if (plan.restoresVault) add("Brings back your passwords, still locked with your master password.")
        if (plan.alreadyThere > 0) add("${plural(plan.alreadyThere, "entry", "entries")} already in the app, skipped.")
        add("Nothing already in the app is changed or deleted.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun BackupSection(vm: AppViewModel) {
    val brand = LocalBrand.current
    val folder by vm.backupFolder.collectAsStateWithLifecycle()
    val lastAt by vm.lastBackupAt.collectAsStateWithLifecycle()
    val failed by vm.backupFailed.collectAsStateWithLifecycle()
    val working by vm.backupWorking.collectAsStateWithLifecycle()
    val message by vm.backupMessage.collectAsStateWithLifecycle()
    val folderName = remember(folder) { vm.backupFolderName() ?: "your folder" }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.backupTo(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.startWeeklyBackup(uri)
    }

    Column(Modifier.padding(start = 46.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
        Text("Google backup", style = MaterialTheme.typography.titleMedium)
        Text(
            "When Google backup is on (phone Settings, then Google, then Backup), your phone saves this app " +
                "to your Google account every day or so. Keys you pasted in, like Gemini, are never included.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingRow(
        title = "Save a backup file",
        body = "Every entry, category, trip and goal in one file. Save it to Google Drive or anywhere you like.",
        onClick = { saveFile.launch("${BackupManager.FILE_PREFIX}${LocalDate.now()}.json") },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { pickFolder.launch(null) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text("Weekly backup", style = MaterialTheme.typography.titleMedium)
            val body = when {
                folder == null -> "Saves a backup file every week to a folder you pick on this phone. Keeps the newest ${BackupManager.KEEP_WEEKLY}."
                failed -> "Could not save the last one. Tap to pick the folder again."
                lastAt > 0 -> "To $folderName. Last saved ${Dates.pretty(Dates.localDate(lastAt))}, ${Dates.clock(lastAt)}. Tap to change folder."
                else -> "To $folderName. Tap to change folder."
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (folder != null && failed) brand.broke else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = folder != null,
            onCheckedChange = { on -> if (on) pickFolder.launch(null) else vm.stopWeeklyBackup() },
            colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
        )
    }

    if (working) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Saving the backup") },
            text = {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = brand.action)
                }
            },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.dismissBackupMessage() },
            text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { vm.dismissBackupMessage() }) { Text("OK", color = brand.action) } },
        )
    }
}

@Composable
private fun PlanFacts(plan: ImportPlan) {
    val lines = buildList {
        val first = plan.firstDay
        val last = plan.lastDay
        if (first != null && last != null) {
            add("From ${Dates.pretty(first)} to ${Dates.pretty(last)}.")
            add(
                "In total " + plan.totalsByCurrency.entries
                    .sortedBy { it.key }
                    .joinToString(" and ") { (currency, minor) -> Money.formatWhole(minor, currency) } + "."
            )
        }
        if (plan.away.isNotEmpty()) add("${plural(plan.away.size, "trip")}, left out of averages.")
        if (plan.alreadyThere > 0) add("${plural(plan.alreadyThere, "entry", "entries")} already in the app, skipped.")
        if (plan.trackedByBank > 0) {
            add("${plural(plan.trackedByBank, "entry", "entries")} from days the app already tracks from HSBC alerts, skipped.")
        }
        if (plan.unmatched.isNotEmpty()) {
            add(
                "${plural(plan.unmatched.values.sum(), "entry", "entries")} with categories the app does not have, " +
                    "skipped: ${plan.unmatched.keys.joinToString(", ")}."
            )
        }
        if (plan.problems.isNotEmpty()) add("${plural(plan.problems.size, "line")} could not be read.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun plural(n: Int, one: String, many: String = one + "s"): String =
    "${"%,d".format(n)} ${if (n == 1) one else many}"

/** "1 to 12 Apr 2026" or "25 Jul to 23 Sep 2025". */
private fun tripDates(startDay: String, endDay: String): String {
    val a = Dates.parseDay(startDay)
    val b = Dates.parseDay(endDay)
    val start = when {
        a.year != b.year -> Dates.pretty(a)
        a.month != b.month -> "${a.dayOfMonth} ${a.month.getDisplayName(TextStyle.SHORT, Locale.UK)}"
        else -> a.dayOfMonth.toString()
    }
    return "$start to ${Dates.pretty(b)}"
}

@Composable
private fun LockSetting(vm: AppViewModel) {
    val context = LocalContext.current
    val brand = LocalBrand.current
    val on by vm.appLock.collectAsStateWithLifecycle()
    val available = remember { app.brokeometer.ui.AppLock.available(context) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = available) { vm.setAppLock(!on) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Lock with fingerprint", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !available -> "Set up a fingerprint or screen lock in the phone's settings first."
                    on -> "Asks when you open the app, or come back after 30 seconds. The phone's PIN works too."
                    else -> "Off. Anyone holding your unlocked phone can open the app."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on && available,
            enabled = available,
            onCheckedChange = { vm.setAppLock(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
        )
    }
}

@Composable
private fun AiSetting(vm: AppViewModel) {
    val saved by vm.geminiKey.collectAsStateWithLifecycle()
    val status by vm.aiStatus.collectAsStateWithLifecycle()
    var typed by remember(saved) { mutableStateOf(saved) }
    var show by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "When a payment comes from a shop the app has never seen, it asks Google Gemini where it goes. " +
                "Only the shop, town, amount and time are sent. Your key stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.trim() },
            label = { Text("Gemini API key") },
            placeholder = { Text("Paste it from AI Studio") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (typed != saved) {
                ActionButton("Save key", onClick = { vm.setGeminiKey(typed) }, height = 44.dp)
            } else if (saved.isNotBlank()) {
                QuietButton("Test the key", onClick = { vm.testGemini() }, height = 44.dp)
                QuietButton("Remove", onClick = { vm.setGeminiKey("") }, height = 44.dp)
            }
        }
        Text(
            status ?: if (saved.isBlank()) "No key yet, so new shops wait for you to choose." else "Key saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationSetting(vm: AppViewModel) {
    val context = LocalContext.current
    val brand = LocalBrand.current
    val on by vm.saveLocation.collectAsStateWithLifecycle()
    var foreground by remember { mutableStateOf(vm.location.hasForeground()) }
    var background by remember { mutableStateOf(vm.location.hasBackground()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        foreground = vm.location.hasForeground()
        background = vm.location.hasBackground()
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        background = vm.location.hasBackground()
    }
    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        foreground = vm.location.hasForeground()
        if (foreground && !vm.location.hasBackground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    fun askWhatIsMissing() {
        when {
            !vm.location.hasForeground() -> askForeground.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            !vm.location.hasBackground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                vm.setSaveLocation(!on)
                if (!on) askWhatIsMissing()
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Save the spot for each card payment", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !on -> "Off. The town from the bank alert is still shown."
                    !foreground -> "Needs location permission. Tap to allow."
                    !background -> "Set location to Allow all the time, so it works while the app is closed."
                    else -> "On. Open a payment and tap See on map."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (on && (!foreground || !background)) brand.careful else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = { want ->
                vm.setSaveLocation(want)
                if (want) askWhatIsMissing()
            },
            colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
        )
    }
    if (on && foreground && !background) {
        QuietButton(
            "Open app settings",
            onClick = { app.brokeometer.ui.openAppInfo(context) },
            height = 44.dp,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun ThemeChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val brand = LocalBrand.current
    Box(
        modifier
            .clip(CircleShape)
            .background(if (selected) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) brand.onAction else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingRow(title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The cards on Home: each on or off, and moved up or down. With all on, Home just scrolls. */
@Composable
private fun HomeCardsSettings(vm: AppViewModel) {
    val cards by vm.homeCards.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val brand = LocalBrand.current
    SectionTitle("Home")
    Text(
        "The cards on Home, top to bottom. Switch off any you don't want; Home just scrolls when they're all on.",
        style = MaterialTheme.typography.bodyMedium,
        color = muted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    cards.forEachIndexed { i, card ->
        val name = app.brokeometer.data.Layout.name(card.key)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = muted, modifier = Modifier.width(28.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, color = if (card.on) MaterialTheme.colorScheme.onSurface else muted, modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = { vm.moveCard(i, -1) }, enabled = i > 0) {
                Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, contentDescription = "Move $name up")
            }
            androidx.compose.material3.IconButton(onClick = { vm.moveCard(i, 1) }, enabled = i < cards.size - 1) {
                Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, contentDescription = "Move $name down")
            }
            androidx.compose.material3.Switch(
                checked = card.on,
                onCheckedChange = { vm.setCardOn(card.key, it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
            )
        }
    }
}

/**
 * The bottom bar: tick up to three features (Home and More are always there). The ticked ones are
 * also the order you swipe through; the rest are under More, in this same order.
 */
@Composable
private fun BottomBarSettings(vm: AppViewModel) {
    val order by vm.barOrder.collectAsStateWithLifecycle()
    val ticked by vm.barTicked.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val brand = LocalBrand.current
    var full by remember { mutableStateOf(false) }
    SectionTitle("Bottom bar")
    Text(
        "Tick up to ${app.brokeometer.data.Layout.MAX_BAR}. Home and More are always there. The ticked ones are also the order you swipe through; the rest live under More, in this order.",
        style = MaterialTheme.typography.bodyMedium,
        color = muted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    if (full) {
        Text(
            "The bar is full. Untick one first.",
            style = MaterialTheme.typography.bodySmall,
            color = brand.careful,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }
    order.forEachIndexed { i, key ->
        val name = app.brokeometer.data.Layout.name(key)
        val on = key in ticked
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(
                checked = on,
                onCheckedChange = { want -> full = !vm.tickBar(key, want) },
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = brand.action, checkmarkColor = brand.onAction),
            )
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(if (on) "In the bar" else "Under More", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            androidx.compose.material3.IconButton(onClick = { vm.moveBar(i, -1) }, enabled = i > 0) {
                Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, contentDescription = "Move $name up")
            }
            androidx.compose.material3.IconButton(onClick = { vm.moveBar(i, 1) }, enabled = i < order.size - 1) {
                Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, contentDescription = "Move $name down")
            }
        }
    }
}

internal fun moreName(key: String): String = when (key) {
    "limits" -> "App limits"
    "passwords" -> "Passwords"
    "notes" -> "Notes"
    "news" -> "News"
    "tasks" -> "Tasks"
    else -> key
}

internal fun agentName(key: String): String = when (key) {
    "email" -> "Email"
    "money" -> "Money"
    "travel" -> "Travel"
    "limits" -> "App limits"
    else -> key
}
