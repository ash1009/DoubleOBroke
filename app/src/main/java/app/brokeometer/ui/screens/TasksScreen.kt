package app.brokeometer.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.tasks.TaskEntity
import app.brokeometer.tasks.TaskRules
import app.brokeometer.tasks.TaskSource
import app.brokeometer.ui.TasksViewModel
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.components.SectionTitle
import app.brokeometer.ui.theme.LocalBrand
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Tasks' pages, in order. */
internal val taskPageNames = listOf("Upcoming", "Done", "Setup")

/** One of Tasks' pages: what's still to do, what's done, or where tasks come from. */
@Composable
internal fun TasksBody(
    vm: TasksViewModel,
    page: Int,
    listState: LazyListState = rememberLazyListState(),
    onOpenEmail: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
) {
    when (page) {
        0 -> UpcomingPage(vm, listState, onOpenEmail, onOpenNote)
        1 -> DonePage(vm, listState)
        else -> TasksSetupPage(vm, listState)
    }
}

/** Still to do: overdue first, then today, tomorrow, this week, later, and ones with no date. */
@Composable
private fun UpcomingPage(vm: TasksViewModel, listState: LazyListState, onOpenEmail: (Long) -> Unit, onOpenNote: (Long) -> Unit) {
    val open by vm.open.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf<TaskEntity?>(null) }
    var changing by remember { mutableStateOf<TaskEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val brand = LocalBrand.current
    val today = LocalDate.now()
    val groups = remember(open, today) { open.groupBy { TaskRules.group(TaskRules.day(it.day), today) } }
    LaunchedEffect(Unit) { vm.refresh() }

    val tick: (TaskEntity) -> Unit = { t ->
        vm.setDone(t, true)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Done: ${t.title}", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.setDone(t, false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 110.dp)) {
            if (open.isEmpty()) {
                item("empty") {
                    EmptyNote(
                        "Nothing to do",
                        "Tasks from your emails, calendar and notes show here, with ones you add. Each evening you get tomorrow's in one notification.",
                    )
                }
            }
            TaskRules.Group.entries.forEach { g ->
                val list = groups[g] ?: return@forEach
                item("g-$g") { SectionTitle(g.label) }
                items(list, key = { it.id }) { t ->
                    TaskRow(t, today, overdue = g == TaskRules.Group.OVERDUE, onTick = { tick(t) }, onClick = { showing = t })
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            containerColor = brand.action,
            contentColor = brand.onAction,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("Task") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
    }

    if (adding) {
        TaskEditDialog(null, onDismiss = { adding = false }) { title, day, time -> vm.add(title, day, time) }
    }
    changing?.let { t ->
        TaskEditDialog(t, onDismiss = { changing = null }) { title, day, time -> vm.change(t, title, day, time) }
    }
    showing?.let { t ->
        TaskSheet(
            t, today,
            onDismiss = { showing = null },
            onDone = {
                showing = null
                tick(t)
            },
            onChange = {
                showing = null
                changing = t
            },
            onOpen = when {
                t.source == TaskSource.EMAIL && (t.ref != null || t.messageId != null) -> {
                    {
                        showing = null
                        vm.emailFor(t)?.let(onOpenEmail)
                    }
                }
                (t.source == TaskSource.NOTE || t.source == TaskSource.NOTE_REMINDER) && t.ref != null -> {
                    {
                        showing = null
                        t.ref?.let(onOpenNote)
                    }
                }
                else -> null
            },
            onDelete = {
                showing = null
                vm.delete(t)
            },
        )
    }
}

@Composable
private fun TaskRow(t: TaskEntity, today: LocalDate, overdue: Boolean, done: Boolean = false, onTick: () -> Unit, onClick: () -> Unit) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onTick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (done) "Not done" else "Done",
                tint = if (done) brand.action else muted,
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(TaskRules.whenText(TaskRules.day(t.day), t.time, today), TaskSource.label(t.source)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) brand.broke else muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A task's details, and what you can do with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskSheet(
    t: TaskEntity,
    today: LocalDate,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onChange: () -> Unit,
    onOpen: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val brand = LocalBrand.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleLarge)
            Text(TaskRules.whenText(TaskRules.day(t.day), t.time, today) + " · " + TaskSource.label(t.source), style = MaterialTheme.typography.bodyMedium, color = muted)
            if (t.detail.isNotBlank()) Text(t.detail, style = MaterialTheme.typography.bodyMedium, color = muted)
            Spacer(Modifier.size(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Done", onClick = onDone, height = 40.dp)
                onOpen?.let { QuietButton(if (t.source == TaskSource.EMAIL) "Open the email" else "Open the note", onClick = it, height = 40.dp) }
                QuietButton("Change", onClick = onChange, height = 40.dp)
                QuietButton("Delete", onClick = onDelete, height = 40.dp, color = brand.broke)
            }
        }
    }
}

/** Adding a task, or changing one: its words, and a day and time if it has them. */
@Composable
private fun TaskEditDialog(task: TaskEntity?, onDismiss: () -> Unit, onSave: (String, LocalDate?, Int?) -> Unit) {
    val brand = LocalBrand.current
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var day by remember { mutableStateOf(TaskRules.day(task?.day)) }
    var time by remember { mutableStateOf(task?.time) }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "New task" else "Change task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    placeholder = { Text("What to do") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickingDay = true }) {
                        Text(day?.let { TaskRules.whenText(it, null, LocalDate.now()) } ?: "Add a day", color = brand.action)
                    }
                    if (day != null) {
                        TextButton(onClick = { pickingTime = true }) {
                            Text(time?.let { TaskRules.clock(it) } ?: "Add a time", color = brand.action)
                        }
                        TextButton(onClick = {
                            day = null
                            time = null
                        }) { Text("No date") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(title, day, time)
                onDismiss()
            }) { Text("Save", color = brand.action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickingDay) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (day ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDay = false
                }) { Text("OK", color = brand.action) }
            },
            dismissButton = { TextButton(onClick = { pickingDay = false }) { Text("Cancel") } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
    if (pickingTime) {
        TimeDialog(time ?: 9 * 60, onDismiss = { pickingTime = false }, onClear = { time = null }) { time = it }
    }
}

/** Picking a time of day; [onClear] takes it off. */
@Composable
private fun TimeDialog(start: Int, onDismiss: () -> Unit, onClear: (() -> Unit)? = null, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = start / 60, initialMinute = start % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour * 60 + state.minute)
                onDismiss()
            }) { Text("OK", color = LocalBrand.current.action) }
        },
        dismissButton = {
            if (onClear != null) {
                TextButton(onClick = {
                    onClear()
                    onDismiss()
                }) { Text("No time") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Done: newest first. Tap one to put it back. */
@Composable
private fun DonePage(vm: TasksViewModel, listState: LazyListState) {
    val done by vm.done.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)) {
        if (done.isEmpty()) {
            item("empty") { EmptyNote("Nothing done yet", "Tick a task and it comes here. Tap one here to put it back.") }
        }
        items(done, key = { it.id }) { t ->
            TaskRow(t, today, overdue = false, done = true, onTick = { vm.setDone(t, false) }, onClick = { vm.setDone(t, false) })
        }
    }
}

/** Where tasks come from, and when you hear about them. */
@Composable
private fun TasksSetupPage(vm: TasksViewModel, listState: LazyListState) {
    val fromEmail by vm.fromEmail.collectAsStateWithLifecycle()
    val fromCalendar by vm.fromCalendar.collectAsStateWithLifecycle()
    val fromNotes by vm.fromNotes.collectAsStateWithLifecycle()
    val summaryAt by vm.summaryAt.collectAsStateWithLifecycle()
    val own by vm.ownReminders.collectAsStateWithLifecycle()
    val lookBack by vm.lookBack.collectAsStateWithLifecycle()
    val lookingBack by vm.lookingBack.collectAsStateWithLifecycle()
    var pickingSummary by remember { mutableStateOf(false) }
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setFromCalendar(true)
    }
    CardList(listState) {
        item("when") {
            PageCard {
                CardHead("Reminders")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tomorrow's tasks, each evening at", style = MaterialTheme.typography.titleMedium)
                        Text("One notification listing everything due the next day.", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    TextButton(onClick = { pickingSummary = true }) { Text(TaskRules.clock(summaryAt), color = brand.action, style = MaterialTheme.typography.titleMedium) }
                }
                SetupSwitch(
                    "A day before things with a time",
                    "Interviews, appointments and the like also get a notification of their own, 24 hours before.",
                    own,
                ) { vm.setOwnReminders(it) }
            }
        }
        item("from") {
            PageCard {
                CardHead("Where tasks come from", "Ones you add yourself always show.")
                SetupSwitch(
                    "Emails",
                    "Every email is read for tests, assessments, interviews, deadlines and bills. \"Within 5 days\" counts from the day the email came, " +
                        "the sooner of two limits is used, and a test with no date gets 3 days.",
                    fromEmail,
                ) { vm.setFromEmail(it) }
                SetupSwitch("Calendar", "Only events you add to your own calendar, read on the phone. Birthdays, holidays, calendars you follow and invitations are left out.", fromCalendar) { on ->
                    if (!on) vm.setFromCalendar(false)
                    else if (vm.canReadCalendar()) vm.setFromCalendar(true)
                    else askCalendar.launch(Manifest.permission.READ_CALENDAR)
                }
                SetupSwitch("Notes", "When you leave a note, the AI looks for dates in it. You can also set Remind me on a note yourself.", fromNotes) { vm.setFromNotes(it) }
            }
        }
        if (fromEmail) {
            item("back") {
                PageCard {
                    CardHead("Emails from the last two weeks", lookBack ?: "Looked through once, for deadlines already in your inbox.")
                    if (lookingBack) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Looking through your emails…", style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    } else {
                        QuietButton(if (lookBack == null) "Look through them" else "Look again", onClick = { vm.lookBackAgain() }, modifier = Modifier.fillMaxWidth(), height = 44.dp)
                    }
                }
            }
        }
    }
    if (pickingSummary) {
        TimeDialog(summaryAt, onDismiss = { pickingSummary = false }) { vm.setSummaryAt(it) }
    }
}

@Composable
private fun SetupSwitch(title: String, body: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val brand = LocalBrand.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction))
    }
}

/** Tasks on Home: the next few, and a button for a new one. */
@Composable
internal fun TasksHomeCard(vm: TasksViewModel, onOpen: () -> Unit) {
    val open by vm.open.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val today = LocalDate.now()
    val next = open.filter { it.day != null }.take(4)
    AgentCard(
        title = "Tasks",
        icon = Icons.Rounded.TaskAlt,
        tint = brand.action,
        onClick = onOpen,
        badge = {
            if (open.isNotEmpty()) Text("${open.size} to do", style = MaterialTheme.typography.bodySmall, color = muted)
        },
    ) {
        if (next.isEmpty()) {
            Text(
                if (open.isEmpty()) "Nothing to do. Tasks from your emails, calendar and notes show here." else "Nothing with a date coming up.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            return@AgentCard
        }
        next.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                val g = TaskRules.group(TaskRules.day(t.day), today)
                Text(
                    TaskRules.whenText(TaskRules.day(t.day), t.time, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (g == TaskRules.Group.OVERDUE) brand.broke else muted,
                )
            }
        }
    }
}
