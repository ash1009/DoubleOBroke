package app.brokeometer.ui.screens

import androidx.compose.foundation.layout.WindowInsets

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.BuildConfig
import app.brokeometer.ui.VaultViewModel
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.components.SectionTitle
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.vault.VaultEntry
import app.brokeometer.vault.VaultState

/** Passwords' pages, in order. */
internal val passwordsPageNames = listOf("Passwords", "Setup")

/**
 * One of Passwords' pages: what's saved, or Setup (bringing them in, fingerprint, the master password).
 * Both need the vault open first. Messages and questions about the vault show from the first page only,
 * which is always there beside Setup, so they never show twice.
 */
@Composable
fun PasswordsScreen(vm: VaultViewModel = viewModel(), page: Int = 0) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val exportFile by vm.exportFile.collectAsStateWithLifecycle()
    val waiting by vm.waitingFile.collectAsStateWithLifecycle()
    val first = page == 0
    // A file shared from another app is read as soon as the vault is open.
    androidx.compose.runtime.LaunchedEffect(state is VaultState.Open, waiting, first) {
        if (first && state is VaultState.Open && waiting != null) vm.importWaiting()
    }
    val brand = LocalBrand.current
    val context = LocalContext.current

    // No screenshots or app-switcher previews of this page in the app that goes on a phone.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        if (!BuildConfig.DEBUG) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!BuildConfig.DEBUG && first) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    var viewing by remember { mutableStateOf<VaultEntry?>(null) }
    var editing by remember { mutableStateOf<VaultEntry?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var googleHelp by remember { mutableStateOf(false) }
    var changingMaster by remember { mutableStateOf(false) }
    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGoogle(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Inside the app's frame: its top and bottom bars already keep clear of the phone's own bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (first && state is VaultState.Open) {
                ExtendedFloatingActionButton(
                    onClick = { editing = VaultEntry(title = "") },
                    containerColor = brand.action,
                    contentColor = brand.onAction,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                // Setup waits for the vault to be made or opened on the first page.
                VaultState.None, is VaultState.Locked -> if (!first) {
                    EmptyNote(
                        if (s is VaultState.None) "No vault yet" else "Passwords are locked",
                        if (s is VaultState.None) "Make your vault on the Passwords page first." else "Unlock them on the Passwords page first.",
                    )
                    return@Box
                }
                else -> Unit
            }
            when (val s = state) {
                VaultState.None -> CreateVault(busy, waiting != null, onCreate = { a, b -> vm.create(a, b) })
                is VaultState.Locked -> Unlock(
                    waiting = waiting != null,
                    fingerprint = s.fingerprint,
                    busy = busy,
                    onFingerprint = { (context as? FragmentActivity)?.let { vm.unlockWithFingerprint(it) } },
                    onPassword = { vm.unlock(it) },
                )
                is VaultState.Open -> if (first) {
                    OpenVault(
                        entries = s.entries,
                        onOpen = { viewing = it },
                        onLock = { vm.lock() },
                    )
                } else {
                    VaultSetup(
                        fingerprint = s.fingerprint,
                        canFingerprint = vm.canUseFingerprint(),
                        onPaste = { pasting = true },
                        onGoogle = { googleHelp = true },
                        onFingerprint = { on ->
                            if (on) (context as? FragmentActivity)?.let { vm.enableFingerprint(it) } else vm.disableFingerprint()
                        },
                        onChangeMaster = { changingMaster = true },
                    )
                }
            }
        }
    }

    viewing?.let { entry ->
        EntryView(
            entry = entry,
            onCopy = { label, secret -> vm.copy(label, secret) },
            onEdit = {
                viewing = null
                editing = entry
            },
            onDelete = {
                vm.delete(entry)
                viewing = null
            },
            onDismiss = { viewing = null },
        )
    }
    editing?.let { entry ->
        EntryEdit(entry, onSave = {
            vm.save(it)
            editing = null
        }, onDismiss = { editing = null })
    }
    if (pasting) {
        PasteNote(onFind = { text ->
            vm.importNote(text)
            pasting = false
        }, onDismiss = { pasting = false })
    }
    if (googleHelp) {
        AlertDialog(
            onDismissRequest = { googleHelp = false },
            title = { Text("From Google Password Manager") },
            text = {
                Text(
                    "1. On this phone open Settings, then Google, then Autofill, then Google Password Manager.\n" +
                        "2. Tap the gear (Settings), then Export passwords, and save the file.\n" +
                        "3. Come back here and pick that file (from Downloads, Drive or wherever you saved it). " +
                        "Or share it to Double O Broke straight from WhatsApp.\n\n" +
                        "The file has your passwords in plain text, so the app offers to delete it once they are saved here. " +
                        "Passkeys can't be exported, so those stay in Google."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    googleHelp = false
                    pickCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream", "*/*"))
                }) { Text("Pick the file", color = brand.action) }
            },
            dismissButton = { TextButton(onClick = { googleHelp = false }) { Text("Cancel") } },
        )
    }
    if (changingMaster) ChangeMaster(onChange = { a, b -> vm.changeMaster(a, b) { changingMaster = false } }, onDismiss = { changingMaster = false })
    if (!first) return
    pending?.let { found ->
        if (state is VaultState.Open) ReviewImport(found, onSave = { vm.saveImport(it) }, onDismiss = { vm.cancelImport() })
    }
    exportFile?.let {
        AlertDialog(
            onDismissRequest = { vm.keepExportFile() },
            title = { Text("Delete the exported file?") },
            text = { Text("It has every password in plain text, so anyone who finds it can read them. They are safe in your vault now.") },
            confirmButton = { TextButton(onClick = { vm.deleteExportFile() }) { Text("Delete it", color = brand.broke) } },
            dismissButton = { TextButton(onClick = { vm.keepExportFile() }) { Text("Keep it") } },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.dismissMessage() },
            text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { vm.dismissMessage() }) { Text("OK", color = brand.action) } },
        )
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (show) "Hide" else "Show")
            }
        },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hero(title: String, body: String) {
    val brand = LocalBrand.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(brand.action.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Key, contentDescription = null, tint = brand.action, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CreateVault(busy: Boolean, waiting: Boolean, onCreate: (String, String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Hero(
            "Your passwords, locked away",
            (if (waiting) "Make your vault first, then the file you shared comes in. " else "") +
                "Everything is encrypted on this phone. You open it with your fingerprint day to day, and with a master password on a new phone.",
        )
        Spacer(Modifier.height(4.dp))
        SecretField(first, { first = it }, "Master password")
        SecretField(second, { second = it }, "Type it again")
        Text(
            "Pick something long that you'll remember, like three random words. Nobody can get it back for you, " +
                "not even me, and without it a backup can't be opened on a new phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LocalBrand.current.action) }
        } else {
            ActionButton("Create my vault", onClick = { onCreate(first, second) }, enabled = first.isNotEmpty(), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Unlock(waiting: Boolean, fingerprint: Boolean, busy: Boolean, onFingerprint: () -> Unit, onPassword: (String) -> Unit) {
    var master by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Hero(
            "Passwords are locked",
            (if (waiting) "Unlock to bring in the file you shared. " else "") +
                if (fingerprint) "Use your fingerprint, or your master password." else "Type your master password.",
        )
        Spacer(Modifier.height(4.dp))
        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LocalBrand.current.action) }
            return@Column
        }
        if (fingerprint) {
            ActionButton("Unlock with fingerprint", onClick = onFingerprint, modifier = Modifier.fillMaxWidth())
        }
        SecretField(master, { master = it }, "Master password")
        if (fingerprint) {
            QuietButton("Unlock with master password", onClick = { onPassword(master) }, enabled = master.isNotEmpty(), modifier = Modifier.fillMaxWidth(), height = 48.dp)
        } else {
            ActionButton("Unlock", onClick = { onPassword(master) }, enabled = master.isNotEmpty(), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun OpenVault(
    entries: List<VaultEntry>,
    onOpen: (VaultEntry) -> Unit,
    onLock: () -> Unit,
) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var query by remember { mutableStateOf("") }
    val shown = remember(entries, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) entries else entries.filter { q in it.title.lowercase() || q in it.username.lowercase() || q in it.url.lowercase() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item("open") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open. Locks a minute after you leave the app.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onLock) { Text("Lock now", color = brand.action) }
            }
        }
        item("search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${entries.size} saved") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (entries.isEmpty()) {
            item("empty") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Text("Nothing saved yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add one with the Add button, or bring them all in at once from your Keep note or Google Password Manager in Setup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                }
            }
        }
        items(shown, key = { it.id }) { entry -> EntryRow(entry) { onOpen(entry) } }
    }
}

/** Passwords' Setup: bringing them in, fingerprint and the master password. */
@Composable
private fun VaultSetup(
    fingerprint: Boolean,
    canFingerprint: Boolean,
    onPaste: () -> Unit,
    onGoogle: () -> Unit,
    onFingerprint: (Boolean) -> Unit,
    onChangeMaster: () -> Unit,
) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    CardList {
        item("import") {
            PageCard {
                CardHead("Bring them in", "Sorting happens on this phone only. Nothing is sent to any AI or website.")
                QuietButton("Paste a note (like your Keep note)", onClick = onPaste, modifier = Modifier.fillMaxWidth(), height = 48.dp)
                QuietButton("From Google Password Manager", onClick = onGoogle, modifier = Modifier.fillMaxWidth(), height = 48.dp)
            }
        }
        item("lock") {
            PageCard {
                CardHead("Unlocking")
                if (canFingerprint) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Unlock with fingerprint", style = MaterialTheme.typography.titleMedium)
                            Text("Only on this phone. A new phone needs the master password.", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        Switch(
                            checked = fingerprint,
                            onCheckedChange = onFingerprint,
                            colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
                        )
                    }
                }
                QuietButton("Change master password", onClick = onChangeMaster, modifier = Modifier.fillMaxWidth(), height = 44.dp)
            }
        }
    }
}

@Composable
private fun EntryRow(entry: VaultEntry, onClick: () -> Unit) {
    val brand = LocalBrand.current
    val letter = entry.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(brand.category(entry.title.lowercase().hashCode())),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.background)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entry.username.ifBlank { if (entry.password.isNotEmpty()) "Password only" else "Note" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EntryView(
    entry: VaultEntry,
    onCopy: (String, String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val brand = LocalBrand.current
    var show by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(entry.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.headlineSmall)
            if (entry.username.isNotEmpty()) {
                Field("Username", entry.username) {
                    IconButton(onClick = { onCopy("Username", entry.username) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy username") }
                }
            }
            if (entry.password.isNotEmpty()) {
                Field("Password", if (show) entry.password else "•".repeat(entry.password.length.coerceIn(8, 16))) {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (show) "Hide password" else "Show password")
                    }
                    IconButton(onClick = { onCopy("Password", entry.password) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy password") }
                }
            }
            if (entry.url.isNotEmpty()) Field("Website", entry.url) {}
            if (entry.note.isNotEmpty()) Field("Note", entry.note) {}
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Edit", onClick = onEdit, modifier = Modifier.weight(1f), height = 48.dp)
                QuietButton("Delete", onClick = { confirmDelete = true }, modifier = Modifier.weight(1f), height = 48.dp, color = brand.broke)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${entry.title}?") },
            text = { Text("It is gone from the vault for good. Backups made before now still have it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = brand.broke) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, actions: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        actions()
    }
}

@Composable
private fun EntryEdit(entry: VaultEntry, onSave: (VaultEntry) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(entry.title) }
    var username by remember { mutableStateOf(entry.username) }
    var password by remember { mutableStateOf(entry.password) }
    var url by remember { mutableStateOf(entry.url) }
    var note by remember { mutableStateOf(entry.note) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (entry.title.isEmpty()) "Add a password" else "Edit", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(title, { title = it }, label = { Text("Site, app or name") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, label = { Text("Username or email") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
            SecretField(password, { password = it }, "Password")
            OutlinedTextField(url, { url = it }, label = { Text("Website (optional)") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, minLines = 2, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
            ActionButton(
                "Save",
                onClick = { onSave(entry.copy(title = title.trim(), username = username.trim(), password = password, url = url.trim(), note = note.trim())) },
                enabled = title.isNotBlank() && (password.isNotEmpty() || note.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PasteNote(onFind: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Paste a note", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Copy the whole note from Keep and paste it here. Lines like \"Spotify: name\" followed by \": password\" are " +
                    "read the way you wrote them; you check everything before it is saved.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Paste here") },
                minLines = 8,
                maxLines = 16,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            ActionButton("Find passwords", onClick = { onFind(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth())
            Text(
                "Once they are saved here, delete the note from Keep.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ReviewImport(found: List<VaultEntry>, onSave: (List<VaultEntry>) -> Unit, onDismiss: () -> Unit) {
    val brand = LocalBrand.current
    val chosen = remember(found) { mutableStateListOf<String>().apply { addAll(found.map { it.id }) } }
    val edits = remember(found) { mutableStateListOf<VaultEntry>().apply { addAll(found) } }
    var editing by remember { mutableStateOf<VaultEntry?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Found ${found.size} to save", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Untick any that are wrong, or tap one to fix it. Ones already in the vault are left out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
                items(edits, key = { it.id }) { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = e }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = e.id in chosen,
                            onCheckedChange = { on -> if (on) chosen.add(e.id) else chosen.remove(e.id) },
                            colors = CheckboxDefaults.colors(checkedColor = brand.action, checkmarkColor = brand.onAction),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    e.username.takeIf { it.isNotEmpty() },
                                    if (e.password.isNotEmpty()) "password " + "•".repeat(e.password.length.coerceIn(4, 10)) else null,
                                    e.note.takeIf { it.isNotEmpty() }?.let { "note" },
                                ).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f), height = 48.dp)
                ActionButton(
                    "Save ${chosen.size}",
                    onClick = { onSave(edits.filter { it.id in chosen }) },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                )
            }
        }
    }
    editing?.let { e ->
        EntryEdit(e, onSave = { fixed ->
            val i = edits.indexOfFirst { it.id == fixed.id }
            if (i >= 0) edits[i] = fixed
            editing = null
        }, onDismiss = { editing = null })
    }
}

@Composable
private fun ChangeMaster(onChange: (String, String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New master password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecretField(first, { first = it }, "New master password")
                SecretField(second, { second = it }, "Type it again")
                Text("Backups made from now on use the new one.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onChange(first, second) }) { Text("Change", color = LocalBrand.current.action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
