package app.brokeometer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.mail.MailReader
import app.brokeometer.ui.ComposeViewModel
import app.brokeometer.ui.ComposeViewModel.Original
import app.brokeometer.ui.ComposeViewModel.Sending
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.theme.LocalBrand

/** Writing an email, new or a reply. It goes from one of your accounts with its app password. */
@Composable
fun ComposeScreen(onBack: () -> Unit, vm: ComposeViewModel = viewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val original by vm.original.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    var from by rememberSaveable { mutableStateOf<Long?>(null) }
    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var showCc by rememberSaveable { mutableStateOf(false) }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var filled by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val attached by vm.attached.collectAsStateWithLifecycle()

    // Suggested addresses: people who have emailed you, and your phone's contacts once you allow it.
    val canReadContacts = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    var askedContacts by rememberSaveable { mutableStateOf(false) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.loadContacts(withPhone = granted)
    }
    LaunchedEffect(Unit) { vm.loadContacts(withPhone = canReadContacts) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris)
    }

    // A reply starts filled in: from the account it came to, to whoever sent it, "Re:" the subject.
    LaunchedEffect(original, accounts) {
        val o = original
        if (!filled && o is Original.Ready) {
            from = o.log.accountId
            to = o.email.replyTo ?: o.email.fromAddress
            subject = MailReader.replySubject(o.email.subject)
            filled = true
        }
        if (from == null && vm.replyId < 0 && accounts.isNotEmpty()) from = accounts.first().id
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val leave: () -> Unit = {
        keyboard?.hide()
        onBack()
    }
    LaunchedEffect(sending) {
        if (sending == Sending.Sent) {
            Toast.makeText(context, "Sent", Toast.LENGTH_SHORT).show()
            leave()
        }
    }
    val started = body.isNotBlank() || (vm.replyId < 0 && (to.isNotBlank() || subject.isNotBlank()))
    BackHandler(enabled = started && sending != Sending.Busy) { leaving = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrokeTopBar(
                title = if (vm.replyId >= 0) "Reply" else "New email",
                onBack = { if (started) leaving = true else leave() },
            ) {
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = sending != Sending.Busy) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "Attach files")
                }
                if (sending == Sending.Busy) {
                    CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(22.dp))
                } else {
                    IconButton(
                        onClick = { vm.send(from, to, cc, subject, body) },
                        enabled = to.isNotBlank() && original !is Original.Loading,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = if (to.isNotBlank()) brand.action else muted)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // From: one pill per account; a reply goes from the account the email came to.
            if (accounts.size > 1) {
                Text("From", style = MaterialTheme.typography.labelLarge, color = muted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    accounts.forEach { a ->
                        val on = a.id == from
                        Text(
                            a.address,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) brand.onAction else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (on) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { from = a.id }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            } else if (accounts.size == 1) {
                Text("From ${accounts.first().address}", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            AddressField("To", to, onChange = { text ->
                to = text
                if (!canReadContacts && !askedContacts && text.isNotBlank()) {
                    askedContacts = true
                    askContacts.launch(Manifest.permission.READ_CONTACTS)
                }
            }, suggest = vm::suggest)
            if (showCc) {
                AddressField("Cc", cc, onChange = { cc = it }, suggest = vm::suggest)
            } else {
                TextButton(onClick = { showCc = true }) { Text("Add Cc", color = brand.action) }
            }
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
            )
            // Attached files, each with a cross to take it off.
            attached.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f.size >= 0) Text(sizeOf(f.size), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    IconButton(onClick = { vm.detach(f) }) { Icon(Icons.Rounded.Close, contentDescription = "Take off ${f.name}") }
                }
            }
            TextButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text(if (attached.isEmpty()) "Attach files" else "Attach more", color = brand.action) }
            when (val o = original) {
                Original.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("  Getting the email you're answering...", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                is Original.Problem -> Column {
                    Text(o.message, style = MaterialTheme.typography.bodySmall, color = brand.broke)
                    TextButton(onClick = { vm.loadOriginal() }) { Text("Try again", color = brand.action) }
                }
                is Original.Ready -> {
                    Text("Your reply has the email below quoted under it.", style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(
                        MailReader.quote(o.email).lines().take(12).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(12.dp),
                    )
                }
                Original.None -> Unit
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    (sending as? Sending.Problem)?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.dismissProblem() },
            title = { Text("Not sent") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = { vm.dismissProblem() }) { Text("OK", color = brand.action) } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Throw this email away?") },
            text = { Text("It hasn't been sent.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    leave()
                }) { Text("Throw away", color = brand.broke) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Keep writing") } },
        )
    }
}

private fun sizeOf(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes bytes"
}

/**
 * An address box that suggests people as you type: the last name or address typed (after a comma)
 * is matched, and tapping a suggestion puts in their address.
 */
@Composable
private fun AddressField(label: String, value: String, onChange: (String) -> Unit, suggest: (String) -> List<ComposeViewModel.Contact>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val head = value.substringBeforeLast(',', "").let { if (it.isEmpty()) "" else "$it, " }
    val typing = value.substringAfterLast(',').trim()
    val matches = remember(typing) { if (typing.contains('@') && typing.contains('.')) emptyList() else suggest(typing) }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (matches.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                matches.forEach { c ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChange(head + c.address + ", ") }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        if (c.name.isNotBlank()) Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.address, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
