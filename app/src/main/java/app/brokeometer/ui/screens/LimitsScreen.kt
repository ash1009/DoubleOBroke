package app.brokeometer.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.limits.AppLimit
import app.brokeometer.limits.LimitAdmin
import app.brokeometer.limits.LimitRules
import app.brokeometer.limits.LimitWatcher
import app.brokeometer.limits.PhoneApp
import app.brokeometer.ui.Hold
import app.brokeometer.ui.LimitsViewModel
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.theme.LocalBrand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val limitPages = listOf("Today", "Limits", "Setup")

/** Which app's limit is being set: a new one, or one already on the list. */
private data class Editing(val pkg: String, val label: String)

/**
 * App limits: Today (screen time and each limit), Limits (the apps and their limits), and Setup
 * (on or off, and what it needs from the phone). Swipe between them; swiping right on Today goes back.
 */
@Composable
fun LimitsScreen(startPage: Int, onBack: () -> Unit, vm: LimitsViewModel = viewModel()) {
    val hold by vm.hold.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(done) {
        val d = done ?: return@LaunchedEffect
        vm.doneShown()
        snackbar.showSnackbar(d)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refresh()
        vm.keepFresh(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.keepFresh(false) }

    val h = hold
    if (h != null) {
        HoldScreen(vm, h)
        return
    }

    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, limitPages.size - 1)) { limitPages.size }
    val scope = rememberCoroutineScope()
    PageHistory(pager)
    val pull = rememberMenuPull(pager, onBack)
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    val go: (Int) -> Unit = { scope.launch { pager.animateScrollToPage(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BrokeTopBar("App limits", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Tabs(limitPages, pager.currentPage) { go(it) }
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(pull),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> TodayPage(vm, onAdd = { picking = true }, onLimit = { editing = it }, onSetup = { go(2) })
                    1 -> ListPage(vm, onAdd = { picking = true }, onEdit = { editing = it })
                    else -> SetupPage(vm)
                }
            }
        }
    }
    if (picking) {
        LaunchedEffect(Unit) { vm.loadApps() }
        AppPicker(vm, onPick = { a ->
            picking = false
            editing = Editing(a.pkg, a.label)
        }, onDismiss = { picking = false })
    }
    editing?.let { e -> LimitDialog(vm, e, onDismiss = { editing = null }) }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

// Today

@Composable
private fun TodayPage(vm: LimitsViewModel, onAdd: () -> Unit, onLimit: (Editing) -> Unit, onSetup: () -> Unit) {
    val time by vm.time.collectAsStateWithLifecycle()
    val list by vm.limits.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val on by vm.on.collectAsStateWithLifecycle()
    val perms by vm.perms.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val brand = LocalBrand.current
    val t = time
    LazyColumn(
        Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!perms.usage || !perms.overlay) {
            item("setup") {
                Card {
                    Text("Not set up yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "App limits needs a few permissions to count your time and close apps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                    ActionButton("Set up", onClick = onSetup)
                }
            }
        }
        if (!on && list.isNotEmpty()) {
            item("off") {
                Text("App limits is off, so nothing is closed.", style = MaterialTheme.typography.bodyMedium, color = brand.careful, modifier = Modifier.padding(start = 4.dp))
            }
        }
        // There from the start (while today's numbers load), so the list doesn't jump.
        if (perms.usage) {
            item("total") {
                Card {
                    Text("Screen time today", style = MaterialTheme.typography.labelLarge, color = muted)
                    Text(if (t != null && t.allowed) LimitRules.hm(t.total) else "...", style = MaterialTheme.typography.displaySmall)
                    val usual = t?.typical?.values?.sum() ?: 0L
                    if (usual > 0) Text("Usually ${LimitRules.hm(usual)} a day", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
        }
        item("limits-head") { Heading("Your limits") }
        if (list.isEmpty()) {
            item("none") {
                Card {
                    Text("No limits yet", style = MaterialTheme.typography.titleMedium)
                    Text("Pick an app and how long a day it gets. Once its time is used, it closes until midnight.", style = MaterialTheme.typography.bodyMedium, color = muted)
                    ActionButton("Add an app", onClick = onAdd)
                }
            }
        } else {
            items(list, key = { "l-" + it.pkg }) { l ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onLimit(Editing(l.pkg, l.label)) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LimitLine(vm, l, t?.perApp?.get(l.pkg) ?: 0L, day.extra[l.pkg] ?: 0)
                    waitingLine(l)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = brand.careful) }
                }
            }
        }
        if (t != null && t.allowed) {
            val limited = list.map { it.pkg }.toSet()
            // Apps you can open, with at least a minute today.
            val top = t.perApp.filter { (pkg, ms) -> pkg !in limited && pkg in t.openable && ms >= 60_000 }
                .entries.sortedByDescending { it.value }.take(6)
            if (top.isNotEmpty()) {
                item("top-head") { Heading("Most used today") }
                items(top, key = { "t-" + it.key }) { (pkg, ms) ->
                    val label = t.labels[pkg] ?: pkg
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(vm, pkg, label, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val usual = t.typical[pkg]
                            Text(
                                LimitRules.hm(ms) + (usual?.let { " today, usually ${LimitRules.hm(it)}" } ?: " today"),
                                style = MaterialTheme.typography.bodySmall,
                                color = muted,
                            )
                        }
                        TextButton(onClick = { onLimit(Editing(pkg, label)) }) { Text("Limit", color = brand.action) }
                    }
                }
            }
        }
    }
}

/** What's waiting to change for an app, if anything. */
private fun waitingLine(l: AppLimit): String? {
    val c = l.next ?: return null
    if (c.remove) return "Comes off your limits tomorrow"
    val parts = buildList {
        if (c.minutes != l.minutes) add(LimitRules.hmMinutes(c.minutes) + " a day")
        if (c.wayOut && !l.wayOut) add("way out back on")
    }
    return "From tomorrow: " + parts.joinToString(", ")
}

// Limits

@Composable
private fun ListPage(vm: LimitsViewModel, onAdd: () -> Unit, onEdit: (Editing) -> Unit) {
    val list by vm.limits.collectAsStateWithLifecycle()
    val on by vm.on.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val brand = LocalBrand.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("add") { ActionButton("Add an app", onClick = onAdd, modifier = Modifier.fillMaxWidth()) }
        items(list, key = { it.pkg }) { l ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onEdit(Editing(l.pkg, l.label)) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(vm, l.pkg, l.label, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            "${LimitRules.hmMinutes(l.minutes)} a day",
                            if (l.warnMinutes > 0) "heads-up ${l.warnMinutes} min before" else "no heads-up",
                            if (l.wayOut) "way out on" else "no way out",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                    waitingLine(l)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = brand.careful) }
                }
            }
        }
        item("rules") {
            Text(
                if (on) {
                    "Making a limit stricter starts now. Loosening one (more time, a way out back on, or taking an app off) " +
                        "starts tomorrow, after a ${LimitRules.LOOSEN_WAIT} minute wait and typing a sentence."
                } else {
                    "App limits is off, so changes start now."
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

// Setup

@Composable
private fun SetupPage(vm: LimitsViewModel) {
    val perms by vm.perms.collectAsStateWithLifecycle()
    val on by vm.on.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val admin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("status") {
            Card {
                Text(if (on) "App limits is on" else "App limits is off", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (on) {
                        "Turning it off takes a ${LimitRules.TURN_OFF_WAIT} minute wait on this screen."
                    } else {
                        "Nothing is counted against your limits or closed."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                if (on) QuietButton("Turn off", onClick = { vm.turnOff() }) else ActionButton("Turn on", onClick = { vm.turnOn() })
            }
        }
        item("perms-head") { Heading("What it needs") }
        item("perms") {
            Card {
                PermRow("Usage access", "To see which app is open and count your time in it.", perms.usage) { openUsage(context) }
                PermRow("Display over other apps", "To put the block screen over an app once its time is used.", perms.overlay) {
                    open(context, LimitWatcher.overlayIntent(context))
                }
                PermRow("Device admin", "So the app can't be uninstalled while App limits is on.", perms.admin) {
                    runCatching { admin.launch(adminIntent(context)) }
                }
                PermRow("Keep running", "So Android doesn't stop it in the background.", perms.battery) {
                    open(context, LimitWatcher.batteryIntent(context))
                }
                PermRow("Automatic date and time", "With it off, limited apps stay closed.", perms.autoTime) {
                    open(context, Intent(Settings.ACTION_DATE_SETTINGS))
                }
            }
        }
    }
}

@Composable
private fun PermRow(title: String, why: String, done: Boolean, onAllow: () -> Unit) {
    val brand = LocalBrand.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (done) {
            Icon(Icons.Rounded.Check, contentDescription = "$title given", tint = brand.fine)
        } else {
            TextButton(onClick = onAllow) { Text("Allow", color = brand.action) }
        }
    }
}

private fun open(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

private fun openUsage(context: Context) {
    val exact = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
    if (Build.VERSION.SDK_INT < 29 || !open(context, exact)) open(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

/**
 * Android's "Activate device admin" page. It closes itself straight away if opened as a new task,
 * so it's opened from this screen and waited on.
 */
private fun adminIntent(context: Context): Intent =
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, LimitAdmin::class.java))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Only so Double O Broke can't be uninstalled while App limits is on. It gets no other powers over your phone.")

// Picking an app

@Composable
private fun AppPicker(vm: LimitsViewModel, onPick: (PhoneApp) -> Unit, onDismiss: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val list by vm.limits.collectAsStateWithLifecycle()
    val time by vm.time.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    Text("Pick an app", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                val limited = list.map { it.pkg }.toSet()
                val shown = apps.orEmpty().filter { it.pkg !in limited && (query.isBlank() || it.label.contains(query.trim(), ignoreCase = true)) }
                if (apps == null) {
                    Text("Loading your apps...", color = muted, modifier = Modifier.padding(20.dp))
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(a) }.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(vm, a.pkg, a.label, 40.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val usual = time?.typical?.get(a.pkg)
                                if (usual != null && usual >= 60_000) {
                                    Text("Usually ${LimitRules.hm(usual)} a day", style = MaterialTheme.typography.bodySmall, color = muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Setting a limit

@Composable
private fun LimitDialog(vm: LimitsViewModel, e: Editing, onDismiss: () -> Unit) {
    val list by vm.limits.collectAsStateWithLifecycle()
    val on by vm.on.collectAsStateWithLifecycle()
    val existing = list.firstOrNull { it.pkg == e.pkg }
    val typical = vm.typical(e.pkg)
    val suggested = LimitRules.suggest(typical)
    var minutes by remember { mutableIntStateOf(existing?.minutes ?: suggested) }
    var warn by remember { mutableIntStateOf(existing?.warnMinutes ?: 10) }
    var wayOut by remember { mutableStateOf(existing?.wayOut ?: true) }
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val loosening = existing != null && on && LimitRules.loosens(existing, minutes, wayOut)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(vm, e.pkg, e.label, 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(e.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (typical != null && typical >= 60_000) "Usually ${LimitRules.hm(typical)} a day over the last week." else "Not enough use yet to know your usual day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                if (existing == null && typical != null && typical >= 60_000) {
                    Text("Suggested: ${LimitRules.hmMinutes(suggested)}, a quarter less.", style = MaterialTheme.typography.bodyMedium, color = brand.action)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A day", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { minutes = (minutes - 15).coerceAtLeast(15) }) { Icon(Icons.Rounded.Remove, contentDescription = "Less time") }
                    Text(LimitRules.hmMinutes(minutes), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
                    IconButton(onClick = { minutes = (minutes + 15).coerceAtMost(12 * 60) }) { Icon(Icons.Rounded.Add, contentDescription = "More time") }
                }
                Text("Heads-up before the limit", style = MaterialTheme.typography.titleMedium)
                Tabs(
                    LimitRules.WARNINGS.map { if (it == 0) "Off" else "$it min" },
                    LimitRules.WARNINGS.indexOf(warn).coerceAtLeast(0),
                ) { warn = LimitRules.WARNINGS[it] }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Way out", style = MaterialTheme.typography.titleMedium)
                        Text("On the block screen: wait for 10 more minutes. The wait doubles each time (1, 2, 4, 8 minutes...).", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Switch(
                        checked = wayOut,
                        onCheckedChange = { wayOut = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
                    )
                }
                existing?.let { l ->
                    waitingLine(l)?.let { w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w, style = MaterialTheme.typography.bodySmall, color = brand.careful, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                vm.cancelChange(l.pkg)
                                onDismiss()
                            }) { Text("Cancel it", color = brand.action) }
                        }
                    }
                }
                if (loosening) {
                    Text(
                        "This gives you more room, so it starts tomorrow, after a ${LimitRules.LOOSEN_WAIT} minute wait and typing a sentence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = brand.careful,
                    )
                }
                if (existing != null) {
                    TextButton(onClick = {
                        vm.remove(existing)
                        onDismiss()
                    }) { Text("Take ${e.label} off my limits", color = brand.broke) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.save(e.pkg, e.label, minutes, warn, wayOut)
                onDismiss()
            }) { Text(if (loosening) "Continue" else "Save", color = brand.action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = muted) } },
    )
}

// The wait before loosening

@Composable
private fun HoldScreen(vm: LimitsViewModel, hold: Hold) {
    val until by vm.holdUntil.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var typed by remember { mutableStateOf("") }
    val view = LocalView.current
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Leaving the screen starts the wait again.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.startWait() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.stopWait()
        typed = ""
    }
    LaunchedEffect(until) {
        while (until != 0L) {
            now = System.currentTimeMillis()
            if (now >= until) break
            delay(250)
        }
    }
    BackHandler { vm.dropHold() }
    val waited = until != 0L && now >= until
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BrokeTopBar(hold.what, onBack = { vm.dropHold() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!waited) {
                Text("Hold on", style = MaterialTheme.typography.headlineMedium)
                val left = if (until == 0L) vm.waitMsFor(hold) else (until - now).coerceAtLeast(0)
                val secs = (left + 999) / 1000
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.displayLarge, color = brand.action)
                }
                Text("Stay on this screen. Leaving it starts the wait again.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (hold) {
                        is Hold.TurnOff -> "Then App limits turns off, and its permissions can be removed."
                        else -> "Then you'll type a sentence, and the change starts tomorrow."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                QuietButton("Never mind", onClick = { vm.dropHold() }, modifier = Modifier.fillMaxWidth())
            } else if (hold.promise) {
                Text("Type this, word for word", style = MaterialTheme.typography.headlineSmall)
                Text(
                    LimitRules.PROMISE,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(16.dp),
                )
                OutlinedTextField(
                    value = typed,
                    // Typed, not pasted: a whole sentence arriving at once is ignored.
                    onValueChange = { v -> if (v.length - typed.length <= 20) typed = v },
                    placeholder = { Text("Type it here") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionButton(
                    if (hold is Hold.Limit && hold.remove) "Take it off tomorrow" else "Start it tomorrow",
                    onClick = { vm.finishHold() },
                    enabled = LimitRules.promised(typed),
                    modifier = Modifier.fillMaxWidth(),
                )
                QuietButton("Never mind", onClick = { vm.dropHold() }, modifier = Modifier.fillMaxWidth(), color = muted)
            } else {
                Text("Done waiting", style = MaterialTheme.typography.headlineSmall)
                ActionButton("Turn App limits off", onClick = { vm.finishHold() }, modifier = Modifier.fillMaxWidth())
                QuietButton("Keep it on", onClick = { vm.dropHold() }, modifier = Modifier.fillMaxWidth(), color = muted)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
