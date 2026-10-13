package app.brokeometer.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.data.TxStatus
import app.brokeometer.travel.Motion
import app.brokeometer.ui.TimelineRow
import app.brokeometer.ui.TravelDay
import app.brokeometer.ui.TravelViewModel
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.components.SectionTitle
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** How each kind of stretch is named on the page. */
private fun Motion.label() = when (this) {
    Motion.STILL -> "Stationary"
    Motion.WALKING -> "Walking"
    Motion.FAST -> "Moving fast"
}

/**
 * Travel's pages, side by side in the app's swipe line: Activity (the day's stays, walks and rides, as
 * noted) and Setup. Each night the day's tube and bus trips go to Payments as waiting, with TfL's fares.
 */
@Composable
internal fun TravelPage(
    page: Int,
    vm: TravelViewModel,
    listState: LazyListState,
    onOpenPayments: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    if (page == 0) TravelActivity(vm, listState, onOpenPayments, onOpenSetup) else TravelSetup(vm, listState, onOpenPayments)
}

private enum class TravelPanel { NONE, DAY, KIND }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TravelActivity(vm: TravelViewModel, listState: LazyListState, onOpenPayments: () -> Unit, onOpenSetup: () -> Unit) {
    val tracking by vm.tracking.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    var panel by rememberSaveable { mutableStateOf(TravelPanel.NONE) }
    // Kept on the view model, so a number tapped on Home opens the list showing just that kind.
    val kind by vm.kind.collectAsStateWithLifecycle()
    var pickingDay by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()

    // The answer of a check shows at the top of the list, so go there to see it.
    LaunchedEffect(message) { if (message != null) listState.animateScrollToItem(0) }

    val shown = timeline?.takeIf { it.day == day }
    // Newest first, like Email's activity.
    val rows = remember(shown, kind) { shown?.rows.orEmpty().filter { kind == null || it.segment.kind == kind }.asReversed() }
    val lastRow = shown?.rows?.lastOrNull()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .nestedScroll(KeepInRow)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(dayLabel(day, today), selected = day != today, open = panel == TravelPanel.DAY) {
                panel = if (panel == TravelPanel.DAY) TravelPanel.NONE else TravelPanel.DAY
            }
            Pill(kind?.label() ?: "All kinds", selected = kind != null, open = panel == TravelPanel.KIND) {
                panel = if (panel == TravelPanel.KIND) TravelPanel.NONE else TravelPanel.KIND
            }
            Pill(if (checking) "Checking" else "Check trips", busy = checking) { vm.checkDay() }
            // The whole day in one file, to send for debugging.
            Pill(if (exporting) "Exporting" else "Export day", busy = exporting) {
                if (!exporting) scope.launch {
                    exporting = true
                    val file = runCatching { vm.exportDay(day) }.getOrNull()
                    exporting = false
                    if (file == null) Toast.makeText(context, "Nothing noted on this day.", Toast.LENGTH_SHORT).show()
                    else shareExport(context, file)
                }
            }
        }
        AnimatedVisibility(panel == TravelPanel.DAY, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Choices {
                Choice("Today", day == today) {
                    vm.setDay(today)
                    panel = TravelPanel.NONE
                }
                Choice("Yesterday", day == today.minusDays(1)) {
                    vm.setDay(today.minusDays(1))
                    panel = TravelPanel.NONE
                }
                Choice("Pick a day...", day.isBefore(today.minusDays(1))) { pickingDay = true }
            }
        }
        AnimatedVisibility(panel == TravelPanel.KIND, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Choices {
                Choice("All kinds", kind == null) {
                    vm.setKind(null)
                    panel = TravelPanel.NONE
                }
                Motion.entries.forEach { m ->
                    Choice(m.label(), kind == m) {
                        vm.setKind(m)
                        panel = TravelPanel.NONE
                    }
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
            message?.let { m ->
                item("message") { NoteCard(m, MaterialTheme.colorScheme.onSurface) { vm.dismissMessage() } }
            }
            if (!tracking && day == today) {
                item("off") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Note my trips is off", style = MaterialTheme.typography.titleMedium)
                        Text("Turn it on under Setup to see where you stayed, walked and travelled.", style = MaterialTheme.typography.bodyMedium, color = muted)
                        QuietButton("Open Setup", onClick = onOpenSetup, height = 40.dp)
                    }
                }
            }
            if (shown == null) return@LazyColumn
            if (shown.rows.isNotEmpty()) {
                item("totals") { Totals(shown, kind) { k -> vm.setKind(if (kind == k) null else k) } }
            }
            item("noted") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        if (shown.points == 0) "Nothing noted on this day." else
                            "${"%,d".format(shown.points)} places noted" + (shown.last?.let { ", the last at ${Dates.clock(it)}" } ?: "") + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                    if (shown.trips.isNotEmpty()) {
                        val total = shown.trips.sumOf { it.amountMinor }
                        val waiting = shown.trips.count { it.status == TxStatus.PENDING }
                        Text(
                            "${shown.trips.size} ${if (shown.trips.size == 1) "trip" else "trips"} on Payments, ${Money.format(total, "GBP")}" +
                                (if (waiting > 0) ", $waiting waiting for you" else "") + ". Open Payments",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalBrand.current.action,
                            modifier = Modifier.padding(top = 4.dp).clickable(onClick = onOpenPayments),
                        )
                    }
                }
            }
            if (shown.rows.isNotEmpty()) {
                item("title") { SectionTitle(kind?.label() ?: "Through the day") }
            }
            if (rows.isEmpty() && shown.rows.isNotEmpty()) {
                item("none") { EmptyNote(title = "Nothing here", body = "Nothing ${kind?.label()?.lowercase()} on this day.") }
            }
            items(rows, key = { "s${it.index}" }) { row ->
                val ongoing = day == today && row.index == lastRow?.index && System.currentTimeMillis() - row.segment.end < 10 * 60_000L
                TimelineItem(row, ongoing) {
                    if (row.trip != null) onOpenPayments() else openMap(context, row)
                }
            }
        }
    }

    if (pickingDay) {
        val zoneToday = today
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // Places are kept for 30 days.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isAfter(zoneToday) && !d.isBefore(zoneToday.minusDays(30))
                }

                override fun isSelectableYear(year: Int): Boolean = year in zoneToday.minusDays(30).year..zoneToday.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedDateMillis != null,
                    onClick = {
                        picker.selectedDateMillis?.let { vm.setDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        panel = TravelPanel.NONE
                        pickingDay = false
                    },
                ) { Text("Show this day", color = LocalBrand.current.action) }
            },
            dismissButton = { TextButton(onClick = { pickingDay = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = picker, showModeToggle = false)
        }
    }
}

/** Time spent staying put, walking and moving fast; tapping one shows only that kind. */
@Composable
private fun Totals(day: TravelDay, selected: Motion?, onPick: (Motion) -> Unit) {
    val t = day.totals
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            Triple(Motion.STILL, t.still, null),
            Triple(Motion.WALKING, t.walking, t.walkMetres),
            Triple(Motion.FAST, t.fast, t.fastMetres),
        ).forEach { (m, millis, metres) ->
            val (icon, tint) = look(m, null)
            val on = selected == m
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .then(if (on) Modifier.border(2.dp, LocalBrand.current.action, RoundedCornerShape(18.dp)) else Modifier)
                    .clickable { onPick(m) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Text(m.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text(duration(millis), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (metres != null) {
                    Text(distance(metres), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m == Motion.WALKING && day.steps != null) {
                    Text("${"%,d".format(day.steps)} steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(row: TimelineRow, ongoing: Boolean, onClick: () -> Unit) {
    val s = row.segment
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, tint) = look(s.kind, row)
    val title = when {
        s.kind == Motion.FAST && row.trip != null -> row.trip.merchantName
        s.kind == Motion.FAST && s.underground -> "Underground"
        else -> s.kind.label()
    }
    val time = if (ongoing) "Since ${Dates.clock(s.start)}" else "${Dates.clock(s.start)} to ${Dates.clock(s.end)}"
    val detail = when (s.kind) {
        Motion.STILL -> row.near?.let { "Near $it" } ?: "Tap to see it on the map"
        Motion.WALKING -> "${distance(s.metres)} at about ${kmh(s.speed)} km/h"
        Motion.FAST -> buildString {
            append(distance(s.metres))
            if (s.underground) append(", no signal") else append(", top ${kmh(s.top)} km/h")
            if (row.trip == null && row.near != null) append(", to near ${row.near}")
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(duration(s.millis), style = MaterialTheme.typography.labelLarge, color = muted)
            }
            Text("$time, $detail", style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.trip?.let { t ->
                val waiting = t.status == TxStatus.PENDING
                Text(
                    if (row.changed) "${t.place}, after changing trains, so no second fare"
                    else "${t.place}, ${Money.format(t.amountMinor, t.currency)}, ${if (waiting) "waiting on Payments" else "accepted"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (waiting) brand.action else brand.fine,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun look(kind: Motion, row: TimelineRow?): Pair<ImageVector, Color> {
    val brand = LocalBrand.current
    return when (kind) {
        Motion.STILL -> Icons.Rounded.Place to MaterialTheme.colorScheme.onSurfaceVariant
        Motion.WALKING -> Icons.AutoMirrored.Rounded.DirectionsWalk to brand.fine
        Motion.FAST -> when {
            row?.trip?.merchantKey == "TFL BUS TRIP" -> Icons.Rounded.DirectionsBus
            row?.trip != null || row?.segment?.underground == true -> Icons.Rounded.DirectionsSubway
            else -> Icons.Rounded.Speed
        } to brand.action
    }
}

@Composable
private fun TravelSetup(vm: TravelViewModel, listState: LazyListState, onOpenPayments: () -> Unit) {
    val tracking by vm.tracking.collectAsStateWithLifecycle()
    val railcard by vm.railcard.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current

    // Location "all the time" is what lets it keep noting after the phone restarts. Checked again on coming back.
    var always by remember { mutableStateOf(locationAlways(context)) }
    var stepsOk by remember { mutableStateOf(vm.stepsReady()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            always = locationAlways(context)
            stepsOk = vm.stepsReady()
        }
    }
    val askSteps = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        stepsOk = vm.stepsReady()
        if (granted) vm.stepsAllowedNow()
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
        item("switch") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (tracking) brand.action.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Note my trips", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (tracking) "On. Each night at about 11:50pm, the day's tube and bus trips go to Payments for you to check."
                            else "Off. Nothing is noted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = tracking,
                        onCheckedChange = { vm.setTracking(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
                    )
                }
                if (!tracking) {
                    Text(
                        "Notes where the phone goes through the day (more often while you're moving, to save battery), " +
                            "and keeps it on this phone only. A small notification shows while it does.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (!always) {
                    Text(
                        "Needs location set to \"Allow all the time\". Tap to open the app's settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = brand.broke,
                        modifier = Modifier.padding(top = 6.dp).clickable { openAppSettings(context) },
                    )
                }
            }
        }

        item("steps") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Count my steps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !vm.hasStepCounter() -> "This phone has no step counter."
                            stepsOk && tracking -> "On. The phone's own step counter, read while Note my trips is on."
                            stepsOk -> "Allowed. Counts while Note my trips is on."
                            else -> "Uses the phone's own step counter. Needs \"Physical activity\" allowed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                if (!stepsOk && vm.hasStepCounter()) {
                    Spacer(Modifier.width(12.dp))
                    QuietButton("Allow", onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askSteps.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }, height = 40.dp)
                }
            }
        }

        item("railcard") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Railcard on your Oyster", style = MaterialTheme.typography.titleMedium)
                Text("A third off off-peak train fares and caps. Buses stay the same.", style = MaterialTheme.typography.bodySmall, color = muted)
                Row(
                    Modifier.padding(top = 8.dp).nestedScroll(KeepInRow).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("" to "None", "16-25" to "16-25 Railcard", "26-30" to "26-30 Railcard").forEach { (value, label) ->
                        Pill(label, selected = railcard == value) { vm.setRailcard(value) }
                    }
                }
            }
        }

        item("how") {
            SectionTitle("How it works")
            Text(
                "Activity: anywhere you stay within about 100 metres for 3 minutes or more is stationary, faster than " +
                    "about 11 km/h (or vanishing underground) is moving fast, and the rest is walking.\n\n" +
                    "Trips: a stretch with no position that ends far away is the tube (no signal underground); fast above " +
                    "ground is a train. It starts and ends at the nearest stations, and a short walk between trains is changing, " +
                    "so one fare. A bus counts when TfL has a bus between the two stops taking about as long. Fares are TfL's " +
                    "(peak on weekday mornings), buses within an hour of the last paid bus are free, and the day stops at the cap. " +
                    "A contactless TfL charge takes the place of the trips it pays for, and Oyster top-ups aren't counted, so " +
                    "nothing counts twice. Paid for a friend too? Change that trip on Payments.\n\n" +
                    "Places noted stay on this phone for 30 days and are never in backups. Only where each trip starts and " +
                    "ends goes to TfL, to find stations and fares.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            QuietButton("Open Payments", onClick = onOpenPayments, height = 40.dp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(shortDay)
}

/** "Under 1 min", "8 min", "1 h 20 min". */
private fun duration(millis: Long): String {
    val minutes = (millis / 60_000L).toInt()
    return when {
        minutes < 1 -> "Under 1 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

/** "640 m", "6.2 km". */
private fun distance(metres: Double): String = when {
    metres < 50 -> "a few steps"
    metres < 1_000 -> "${(metres / 10).roundToInt() * 10} m"
    else -> "%.1f km".format(Locale.UK, metres / 1_000)
}

private fun kmh(metresPerSecond: Double): Int = (metresPerSecond * 3.6).roundToInt()

/** Stays open on the map; walks and rides open as directions from where they began to where they ended. */
private fun openMap(context: Context, row: TimelineRow) {
    val s = row.segment
    val uri = if (s.kind == Motion.STILL) {
        Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}")
    } else {
        val mode = if (s.kind == Motion.WALKING) "walking" else "transit"
        Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${s.fromLat},${s.fromLon}&destination=${s.lat},${s.lon}&travelmode=$mode")
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun locationAlways(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    return fine && background
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Shares an exported day: save it to Files or Drive, or send it straight to a chat. */
private fun shareExport(context: Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(send, "Send ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
