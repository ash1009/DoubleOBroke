package app.brokeometer.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.mail.MailAccountEntity
import app.brokeometer.mail.MailCategoryEntity
import app.brokeometer.mail.MailProgress
import app.brokeometer.mail.MailProviders
import app.brokeometer.mail.MailSenderEntity
import app.brokeometer.mail.UnsubscribeOutcome
import app.brokeometer.mail.UnsubscribeWays
import app.brokeometer.ui.MailViewModel
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.CategoryDot
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.components.SectionTitle
import app.brokeometer.ui.rememberSetupStatus
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates

/** Email's pages in the app's swipe line, in order. */
internal val emailPageNames = listOf("Activity", "Subscriptions", "What matters")

/**
 * One of Email's pages: Activity, Subscriptions or What matters. They sit side by side in the app's
 * swipe line; Setup (accounts, the AI key, reading and sorting) is a page of its own.
 */
@Composable
internal fun EmailPage(
    page: Int,
    listState: LazyListState,
    onOpenEmail: (Long) -> Unit,
    onOpenSetup: () -> Unit,
    vm: MailViewModel,
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val senders by vm.senders.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val agentOn by vm.agentOn.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val markingAll by vm.markingAll.collectAsStateWithLifecycle()
    val listening = rememberSetupStatus().readsBankAlerts

    var moving by remember { mutableStateOf<MailSenderEntity?>(null) }
    var leaving by remember { mutableStateOf<MailSenderEntity?>(null) }
    val unsubscribe by vm.unsubscribeResult.collectAsStateWithLifecycle()

    // Only work in progress or a problem shows here; finished reads show on Setup.
    val showProgress = progress.busy || progress.stage == MailProgress.Stage.FAILED ||
        (progress.message != null && progress.stage == MailProgress.Stage.DONE)
    val progressItem: LazyListScope.() -> Unit = {
        if (showProgress) {
            item("progress") { ProgressCard(progress, vm.brainName(), onStop = { vm.stop() }, onDismiss = { vm.dismissProgress() }) }
        }
        if (accounts.isEmpty() || categories.isEmpty()) {
            item("start") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (accounts.isEmpty()) "Add your email accounts" else "Read and sort your email",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (accounts.isEmpty()) "Add each Gmail and Yahoo account with an app password, then the AI sorts who emails you."
                        else "The AI groups everyone who emails you, then you choose what matters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ActionButton("Open email setup", onClick = onOpenSetup, height = 44.dp)
                }
            }
        }
    }

    when (page) {
        0 -> ActivityPage(
            state = ActivityState(log, accounts, problem, working, checking, markingAll, agentOn),
            actions = ActivityActions(
                open = { onOpenEmail(it.id) },
                markRead = { vm.markRead(it) },
                markUnread = { vm.markUnread(it) },
                spam = { vm.markSpam(it) },
                notSpam = { vm.notSpam(it) },
                markAllRead = { vm.markAllRead(it) },
                checkNow = { vm.checkNow() },
                tidyUp = { vm.tidyUp() },
                dismissProblem = { vm.dismissProblem() },
                pickMany = { list, kind -> vm.pickMany(list, kind) },
                restore = { vm.restore(it) },
            ),
            listState = listState,
            top = progressItem,
            vm = vm,
        )
        1 -> SubscriptionsPage(
            accounts = accounts,
            senders = senders,
            listState = listState,
            onLeave = { leaving = it },
            top = progressItem,
        )
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 110.dp)) {
            progressItem()
            item("agent") {
                AgentCard(
                    on = agentOn,
                    listening = listening,
                    ready = categories.isNotEmpty(),
                    onChange = { vm.setAgentOn(it) },
                )
            }
            whatMatters(
                categories, senders,
                onToggle = { c, on -> vm.setImportant(c, on) },
                onMove = { moving = it },
                busy = progress.busy,
                onSortRest = { vm.sortRest() },
            )
        }
    }

    moving?.let { sender ->
        MoveDialog(sender, categories, onPick = { id ->
            vm.moveSender(sender.address, id)
            moving = null
        }, onDismiss = { moving = null })
    }

    leaving?.let { sender ->
        val ways = UnsubscribeWays.parse(sender.unsubscribe, sender.oneClick)
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Unsubscribe from ${displayName(sender)}?") },
            text = {
                Text(
                    when {
                        ways.oneClick -> "The app tells them straight away. You can't undo this from here."
                        ways.web != null -> "Opens their unsubscribe page, where you finish it."
                        else -> "Opens an email to them that you send to unsubscribe."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.unsubscribe(sender)
                    leaving = null
                }) { Text("Unsubscribe", color = LocalBrand.current.action) }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Cancel") } },
        )
    }

    // The answer to an unsubscribe shows once, on Subscriptions where it was asked.
    if (page == 1) {
        unsubscribe?.let { (sender, outcome) ->
            LaunchedEffect(outcome) {
                when (outcome) {
                    is UnsubscribeOutcome.OpenWeb -> open(context, Intent(Intent.ACTION_VIEW, Uri.parse(outcome.url)))
                    is UnsubscribeOutcome.OpenMail -> open(context, Intent(Intent.ACTION_SENDTO, Uri.parse(outcome.uri)))
                    else -> Unit
                }
            }
            val brand = LocalBrand.current
            when (outcome) {
                UnsubscribeOutcome.Done -> AlertDialog(
                    onDismissRequest = { vm.dismissUnsubscribe() },
                    text = { Text("Unsubscribed from ${displayName(sender)}.") },
                    confirmButton = { TextButton(onClick = { vm.dismissUnsubscribe() }) { Text("OK", color = brand.action) } },
                )
                is UnsubscribeOutcome.Failed -> AlertDialog(
                    onDismissRequest = { vm.dismissUnsubscribe() },
                    text = { Text(outcome.message) },
                    confirmButton = { TextButton(onClick = { vm.dismissUnsubscribe() }) { Text("OK", color = brand.action) } },
                )
                else -> AlertDialog(
                    onDismissRequest = { vm.dismissUnsubscribe() },
                    title = { Text("Did it work?") },
                    text = { Text("Once you have unsubscribed from ${displayName(sender)}, mark it here so the list stays tidy.") },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.markUnsubscribed(sender)
                            vm.dismissUnsubscribe()
                        }) { Text("Yes, mark it", color = brand.action) }
                    },
                    dismissButton = { TextButton(onClick = { vm.dismissUnsubscribe() }) { Text("Not yet") } },
                )
            }
        }
    }
}

/** Email setup: accounts, the AI key, and reading and sorting. Opened from Email's top bar. */
@Composable
fun EmailSetupScreen(onBack: () -> Unit, vm: MailViewModel = viewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val senders by vm.senders.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val showProgress = progress.busy || progress.stage == MailProgress.Stage.FAILED || progress.message != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BrokeTopBar(title = "Email setup", onBack = onBack) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (showProgress) {
                item("progress") { ProgressCard(progress, vm.brainName(), onStop = { vm.stop() }, onDismiss = { vm.dismissProgress() }) }
            }
            setup(accounts, categories, senders, progress, vm, onAdd = { adding = true })
        }
    }
    if (adding) AddAccountSheet(vm, onDone = { adding = false })
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nothing on the phone can open it; the "Did it work?" question still shows.
    }
}

internal fun displayName(s: MailSenderEntity): String = s.name.ifBlank { s.address }

// What matters

private fun LazyListScope.whatMatters(
    categories: List<MailCategoryEntity>,
    senders: List<MailSenderEntity>,
    onToggle: (MailCategoryEntity, Boolean) -> Unit,
    onMove: (MailSenderEntity) -> Unit,
    busy: Boolean = false,
    onSortRest: () -> Unit = {},
) {
    if (categories.isEmpty()) {
        item("empty") {
            EmptyNote(
                title = "No categories yet",
                body = "Go to Setup, add your email accounts and press Read my email. The AI groups everyone who emails you, then you choose what matters.",
            )
        }
        return
    }
    item("explain") {
        Text(
            "Each kind of email: on means you get a notification, off means marked read and archived.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
    val byCategory = senders.groupBy { it.categoryId }
    for (category in categories) {
        item("c${category.id}") {
            CategoryCard(category, byCategory[category.id].orEmpty(), onToggle, onMove)
        }
    }
    val loose = senders.filter { s -> s.categoryId == null || categories.none { it.id == s.categoryId } }
    if (loose.isNotEmpty()) {
        item("loose") {
            Column {
                CategoryCard(
                    MailCategoryEntity(id = -1, name = "Not sorted yet", description = "The AI has not put these in a category yet.", important = false, position = 999),
                    loose,
                    onToggle = null,
                    onMove = onMove,
                )
                if (!busy) {
                    ActionButton(
                        "Sort these now",
                        onClick = onSortRest,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        height = 44.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: MailCategoryEntity,
    senders: List<MailSenderEntity>,
    onToggle: ((MailCategoryEntity, Boolean) -> Unit)?,
    onMove: (MailSenderEntity) -> Unit,
) {
    val brand = LocalBrand.current
    var open by rememberSaveable(category.id) { mutableStateOf(false) }
    var all by rememberSaveable(category.id) { mutableStateOf(false) }
    val merged = remember(senders) { senders.groupBy { it.address }.map { (_, rows) -> rows.maxBy { it.total } to rows.sumOf { it.total } }.sortedByDescending { it.second } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(category.name, style = MaterialTheme.typography.titleMedium)
                if (category.description.isNotBlank()) {
                    Text(
                        category.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${count(merged.size, "sender")}, ${count(merged.sumOf { it.second }, "email")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (onToggle != null) {
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = category.important,
                    onCheckedChange = { onToggle(category, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
                )
            }
        }
        if (open) {
            val shown = if (all) merged else merged.take(15)
            shown.forEach { (sender, total) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onMove(sender) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(displayName(sender), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            sender.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (sender.categorySource == "USER") "$total, moved" else "$total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (merged.size > 15) {
                TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 8.dp)) {
                    Text(if (all) "Show fewer" else "Show all ${merged.size}", color = brand.action)
                }
            }
            Text(
                "Tap a sender to move them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun AgentCard(on: Boolean, listening: Boolean, ready: Boolean, onChange: (Boolean) -> Unit) {
    val brand = LocalBrand.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) brand.action.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Let the agent handle new email", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (on) "On. New email is read as it arrives and checked every 15 minutes; spam every 3 hours."
                    else "Off. Your email is not touched.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = on,
                enabled = ready,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
            )
        }
        if (!on) {
            Text(
                "When Gmail or Yahoo shows a new email, the agent reads it, and it checks the inbox every 15 minutes for emails that come in silently. If its kind is switched on, you get the " +
                    "agent's notification instead of the email app's. If not, it is marked read and archived. Real spam " +
                    "goes to the bin (emptied after 30 days); spam that might be real gets a notification. " +
                    "Starts with new email only, and anything can be undone under Activity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!listening) {
            Text(
                "Needs notification access, to see when Gmail or Yahoo gets an email. Tap to turn it on.",
                style = MaterialTheme.typography.bodyMedium,
                color = brand.broke,
                modifier = Modifier.clickable { app.brokeometer.ui.openNotificationAccess(context) },
            )
        }
    }
}

@Composable
private fun MoveDialog(
    sender: MailSenderEntity,
    categories: List<MailCategoryEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${displayName(sender)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                categories.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c.id == sender.categoryId, onClick = { onPick(c.id) })
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Subscriptions

@Composable
internal fun ListRow(sender: MailSenderEntity, onLeave: (MailSenderEntity) -> Unit) {
    val opened = if (sender.total > 0) (sender.total - sender.unread) * 100 / sender.total else 0
    val perMonth = (sender.recent + 1) / 3
    val often = when {
        sender.recent == 0 -> "None lately, last ${Dates.pretty(Dates.localDate(sender.lastAt))}"
        perMonth <= 1 -> "About 1 a month"
        else -> "About $perMonth a month"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(displayName(sender), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$often, opened $opened%",
                style = MaterialTheme.typography.bodyMedium,
                color = if (opened < 20) LocalBrand.current.careful else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                sender.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (sender.unsubscribedAt != null) {
            Text("Unsubscribed", style = MaterialTheme.typography.labelLarge, color = LocalBrand.current.fine)
        } else {
            QuietButton("Unsubscribe", onClick = { onLeave(sender) }, height = 40.dp)
        }
    }
}

// Setup

private fun LazyListScope.setup(
    accounts: List<MailAccountEntity>,
    categories: List<MailCategoryEntity>,
    senders: List<MailSenderEntity>,
    progress: MailProgress,
    vm: MailViewModel,
    onAdd: () -> Unit,
) {
    item("accounts") { SectionTitle("Accounts") }
    if (accounts.isEmpty()) {
        item("no-accounts") {
            Text(
                "Add each Gmail and Yahoo account. The app signs in with an app password, which you can cancel any time in your Google or Yahoo account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
    items(accounts, key = { "a${it.id}" }) { account -> AccountRow(account, onRemove = { vm.removeAccount(account) }) }
    item("add") {
        QuietButton("Add an account", onClick = onAdd, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), height = 44.dp)
    }
    item("ai-title") { SectionTitle("AI") }
    item("ai") { ClaudeKey(vm) }
    item("read-title") { SectionTitle("Read and sort") }
    item("read") {
        var confirmAgain by remember { mutableStateOf(false) }
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Reads who sent your newest emails (up to 15,000 an account) and their subjects, without opening or " +
                    "marking anything. The AI then groups the senders. It can take a while; you can leave the app and " +
                    "a notification shows how it's going.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val unsorted = senders.filter { s -> s.categoryId == null || categories.none { it.id == s.categoryId } }
                .map { it.address }.distinct().size
            if (categories.isNotEmpty() && unsorted > 0) {
                ActionButton(
                    text = "Sort the rest (${"%,d".format(unsorted)} senders)",
                    onClick = { vm.sortRest() },
                    enabled = !progress.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val readText = if (categories.isEmpty()) "Read my email" else "Read again and sort new senders"
            if (categories.isNotEmpty() && unsorted > 0) {
                QuietButton(readText, onClick = { vm.readAndSort() }, enabled = accounts.isNotEmpty() && !progress.busy, modifier = Modifier.fillMaxWidth(), height = 44.dp)
            } else {
                ActionButton(
                    text = readText,
                    onClick = { vm.readAndSort() },
                    enabled = accounts.isNotEmpty() && !progress.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (categories.isNotEmpty()) {
                QuietButton(
                    "Make the categories again",
                    onClick = { confirmAgain = true },
                    enabled = !progress.busy,
                    modifier = Modifier.fillMaxWidth(),
                    height = 44.dp,
                )
            }
        }
        if (confirmAgain) {
            AlertDialog(
                onDismissRequest = { confirmAgain = false },
                title = { Text("Make the categories again?") },
                text = { Text("The AI starts over. Senders you moved by hand stay put when their category comes back, and switches you set are kept for categories with the same name.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.makeCategoriesAgain()
                        confirmAgain = false
                    }) { Text("Start over", color = LocalBrand.current.action) }
                },
                dismissButton = { TextButton(onClick = { confirmAgain = false }) { Text("Cancel") } },
            )
        }
    }
    item("privacy") {
        Text(
            "App passwords and keys stay on this phone and are never in backups. Sender names, addresses and " +
                "subjects go to the AI to sort them, and with the agent on, the start of each new email too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun AccountRow(account: MailAccountEntity, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(if (account.scannedAt > 0) LocalBrand.current.fine else LocalBrand.current.careful, size = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(account.address, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (account.scannedAt > 0) {
                    "Read ${"%,d".format(account.scannedCount)} emails, ${Dates.pretty(Dates.localDate(account.scannedAt))}"
                } else {
                    "Connected, not read yet"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { confirm = true }) { Text("Remove") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove ${account.address}?") },
            text = { Text("The app forgets the account and its app password. Your emails are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove()
                    confirm = false
                }) { Text("Remove", color = LocalBrand.current.broke) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClaudeKey(vm: MailViewModel) {
    val saved by vm.claudeKey.collectAsStateWithLifecycle()
    val gemini by vm.geminiKey.collectAsStateWithLifecycle()
    val status by vm.keyStatus.collectAsStateWithLifecycle()
    var typed by remember(saved) { mutableStateOf(saved) }
    var show by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Claude sorts your email while your Max plan's monthly API credits last. After that the app uses " +
                "Gemini (the key in Settings, AI for new shops).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.trim() },
            label = { Text("Claude API key") },
            placeholder = { Text("Paste it from the Claude Console") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (typed != saved) {
                ActionButton("Save key", onClick = { vm.setClaudeKey(typed) }, height = 44.dp)
            } else if (saved.isNotBlank()) {
                QuietButton("Test the key", onClick = { vm.testClaude() }, height = 44.dp)
                QuietButton("Remove", onClick = { vm.setClaudeKey("") }, height = 44.dp)
            }
        }
        Text(
            status ?: when {
                saved.isNotBlank() -> "Claude key saved."
                gemini.isNotBlank() -> "No Claude key, so Gemini will sort your email."
                else -> "No AI key yet. Add a Claude key here or a Gemini key in Settings."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressCard(progress: MailProgress, brain: String, onStop: () -> Unit, onDismiss: () -> Unit = {}) {
    val brand = LocalBrand.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val title = when (progress.stage) {
            MailProgress.Stage.READING -> "Reading ${progress.account}"
            MailProgress.Stage.SORTING -> if (progress.total > 0) "$brain is sorting senders" else (progress.message ?: "Sorting")
            MailProgress.Stage.TIDYING -> "Tidying up ${progress.account ?: "old emails"}"
            MailProgress.Stage.FAILED -> "Not finished"
            else -> progress.message ?: ""
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (progress.busy) {
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                    color = brand.action,
                    trackColor = brand.track,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${"%,d".format(progress.done)} of ${"%,d".format(progress.total)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(color = brand.action, trackColor = brand.track, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onStop) { Text("Stop", color = brand.action) }
        } else {
            if (progress.stage == MailProgress.Stage.FAILED && progress.message != null) {
                Text(progress.message, style = MaterialTheme.typography.bodyMedium, color = brand.broke)
            }
            TextButton(onClick = onDismiss) { Text("OK", color = brand.action) }
        }
    }
}

@Composable
private fun AddAccountSheet(vm: MailViewModel, onDone: () -> Unit) {
    val brand = LocalBrand.current
    val context = LocalContext.current
    val state by vm.addState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var provider by rememberSaveable { mutableStateOf(MailProviders.GMAIL) }
    var address by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state == MailViewModel.AddState.Added) {
            vm.resetAdd()
            onDone()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            vm.resetAdd()
            onDone()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add an account", style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp),
            ) {
                listOf(MailProviders.GMAIL to "Gmail", MailProviders.YAHOO to "Yahoo").forEach { (id, label) ->
                    val on = provider == id
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (on) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { provider = id }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) brand.onAction else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val steps = if (provider == MailProviders.GMAIL) {
                listOf(
                    "Turn on 2-Step Verification for this Google account, if it is not on yet.",
                    "Open Google app passwords (button below) and sign in.",
                    "Type a name such as Double O Broke and press Create.",
                    "Copy the 16 letters and paste them below.",
                )
            } else {
                listOf(
                    "Open Yahoo account security (button below) and sign in.",
                    "Press Generate app password (or Generate and manage app passwords).",
                    "Type a name such as Double O Broke and press Generate.",
                    "Copy the password and paste it below.",
                )
            }
            steps.forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
            QuietButton(
                if (provider == MailProviders.GMAIL) "Open Google app passwords" else "Open Yahoo account security",
                onClick = {
                    val url = if (provider == MailProviders.GMAIL) "https://myaccount.google.com/apppasswords"
                    else "https://login.yahoo.com/myaccount/security"
                    open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                height = 44.dp,
            )
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it.trim()
                    MailProviders.guess(address)?.let { guess -> provider = guess }
                },
                label = { Text("Email address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("App password") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            (state as? MailViewModel.AddState.Problem)?.let {
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = brand.broke)
            }
            if (state == MailViewModel.AddState.Checking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = brand.action, modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Signing in...", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                ActionButton(
                    "Connect",
                    onClick = { vm.addAccount(address, provider, password) },
                    enabled = address.contains('@') && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "The password stays on this phone. It only lets the app read and sort your mail, and you can cancel it any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun count(n: Int, one: String): String = "${"%,d".format(n)} ${if (n == 1) one else one + "s"}"
