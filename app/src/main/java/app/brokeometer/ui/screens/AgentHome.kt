package app.brokeometer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.data.TxStatus
import app.brokeometer.mail.MailActions
import app.brokeometer.mail.MailLogEntity
import app.brokeometer.travel.Motion
import app.brokeometer.travel.TravelLines
import app.brokeometer.ui.AppViewModel
import app.brokeometer.ui.LimitsViewModel
import app.brokeometer.ui.MailViewModel
import app.brokeometer.ui.TravelDay
import app.brokeometer.ui.TravelViewModel
import app.brokeometer.ui.summarise
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.ui.usualMonthMinor
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToLong

/** Where Home's cards lead. */
internal class HomeActions(
    val openEmail: () -> Unit,
    val openEmailItem: (MailLogEntity, List<Long>) -> Unit,
    val openUnread: () -> Unit,
    val openEmailSetup: () -> Unit,
    val openMoney: () -> Unit,
    val openPayments: () -> Unit,
    val openTravel: (Motion?) -> Unit,
    val openTravelSetup: () -> Unit,
    val openLimits: () -> Unit,
)

/**
 * Home: a card for each agent, most important first (the order is set in Settings). Each says what
 * needs you and what it handled today. Passwords has no card; it lives under More.
 */
@Composable
internal fun HomePage(
    order: List<String>,
    app: AppViewModel,
    mail: MailViewModel,
    travel: TravelViewModel,
    limits: LimitsViewModel,
    listState: LazyListState,
    actions: HomeActions,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.refreshToday()
        travel.refreshToday()
        limits.refresh()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        order.forEach { key ->
            when (key) {
                "email" -> item("email") { EmailCard(mail, actions) }
                "money" -> item("money") { MoneyCard(app, actions) }
                "travel" -> item("travel") { TravelCard(travel, actions) }
                "limits" -> item("limits") { LimitsCard(limits, actions.openLimits) }
            }
        }
    }
}

@Composable
internal fun AgentCard(
    title: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            badge?.invoke()
        }
        content()
    }
}

@Composable
private fun Chip(text: String, color: Color, onColor: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = onColor,
        modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

// Email

@Composable
private fun EmailCard(mail: MailViewModel, actions: HomeActions) {
    val accounts by mail.accounts.collectAsStateWithLifecycle()
    val agentOn by mail.agentOn.collectAsStateWithLifecycle()
    val log by mail.log.collectAsStateWithLifecycle()
    val working by mail.working.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val needsYou = remember(log) {
        log.filter { MailActions.attention(it) != null }
            .sortedWith(compareBy<MailLogEntity> { MailActions.attention(it) }.thenByDescending { it.at })
    }
    val todays = remember(log) {
        val from = LocalDate.now().atStartOfDay(Dates.zone()).toInstant().toEpochMilli()
        // Older emails sorted by a tidy-up aren't today's news.
        log.filter { it.at >= from && !it.summary.startsWith("Tidy-up") }
    }

    AgentCard(
        title = "Email",
        icon = Icons.Rounded.Mail,
        tint = brand.fine,
        onClick = actions.openEmail,
        badge = {
            when {
                needsYou.isNotEmpty() -> Chip(if (needsYou.size == 1) "1 needs you" else "${needsYou.size} need you", brand.fine, MaterialTheme.colorScheme.background)
                accounts.isNotEmpty() && agentOn -> AllClear()
            }
        },
    ) {
        if (accounts.isEmpty()) {
            Text("Add your email accounts and the agent sorts what comes in.", style = MaterialTheme.typography.bodyMedium, color = muted)
            Text("Open email setup", style = MaterialTheme.typography.labelLarge, color = brand.action, modifier = Modifier.clickable(onClick = actions.openEmailSetup))
            return@AgentCard
        }
        val shown = needsYou.take(2)
        val ids = needsYou.map { it.id }
        shown.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.openEmailItem(e, ids) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (MailActions.leftUnread(e)) brand.action else Color.Transparent))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.fromName.ifBlank { e.fromAddress }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when {
                    e.id in working -> CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(20.dp))
                    MailActions.leftUnread(e) -> IconButton(onClick = { mail.markRead(e) }) {
                        Icon(Icons.Outlined.Drafts, contentDescription = "Mark read", tint = brand.action)
                    }
                }
            }
        }
        if (needsYou.size > shown.size) {
            val more = needsYou.size - shown.size
            Text(
                "and $more more",
                style = MaterialTheme.typography.labelLarge,
                color = brand.action,
                modifier = Modifier.clickable(onClick = actions.openUnread).padding(vertical = 2.dp),
            )
        }
        if (shown.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text(
            if (!agentOn) "The agent is off, so new email isn't handled. Turn it on under What matters." else handledToday(todays, needsYou.size),
            style = MaterialTheme.typography.bodyMedium,
            color = if (shown.isEmpty()) MaterialTheme.colorScheme.onSurface else muted,
        )
    }
}

@Composable
private fun AllClear() {
    val brand = LocalBrand.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = brand.fine, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("All clear", style = MaterialTheme.typography.labelLarge, color = brand.fine)
    }
}

/** "Today 26 came in: 21 sorted and archived, 2 spam binned, 3 need you." */
private fun handledToday(todays: List<MailLogEntity>, needingYou: Int): String {
    if (todays.isEmpty()) return "Nothing new has come in today."
    val archived = todays.count { !it.undone && it.action == MailActions.ARCHIVED }
    val binned = todays.count { !it.undone && it.action == MailActions.SPAM_DELETED }
    val waiting = todays.count { !it.undone && it.action == MailActions.SPAM_UNCHECKED }
    val needYou = todays.count { MailActions.attention(it) != null }
    val rest = todays.size - archived - binned - waiting - needYou
    val parts = buildList {
        if (archived > 0) add("$archived sorted and archived")
        if (binned > 0) add("$binned spam binned")
        if (waiting > 0) add("$waiting spam not checked yet")
        if (needYou > 0) add("$needYou need you")
        if (rest > 0) add("$rest read")
    }
    val came = if (todays.size == 1) "1 came in" else "${todays.size} came in"
    return "Today $came: ${parts.joinToString(", ")}." + if (needingYou == 0) " Nothing needs you." else ""
}

// Money

@Composable
private fun MoneyCard(app: AppViewModel, actions: HomeActions) {
    val entries by app.thisMonth.collectAsStateWithLifecycle()
    val pending by app.pending.collectAsStateWithLifecycle()
    val dayTotals by app.dayTotals.collectAsStateWithLifecycle()
    val away by app.away.collectAsStateWithLifecycle()
    val catalog by app.catalog.collectAsStateWithLifecycle()
    val today by app.today.collectAsStateWithLifecycle()
    val insights by app.insights.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val month = YearMonth.from(today)
    val summary = remember(entries, catalog, today, away) { summarise(entries, catalog, month, today, away) }
    val usual = remember(dayTotals, month, away) { usualMonthMinor(dayTotals, month, away) }
    val spentToday = summary.perDay.getOrNull(today.dayOfMonth - 1) ?: 0L

    // A day's amount: what's left of the month's limit when today began, shared over the days left;
    // without a limit, your usual day.
    val allowance = insights?.allowance
    val daily: Long? = when {
        allowance != null && allowance.daysLeft > 0 ->
            ((allowance.limitMinor - (allowance.spentMinor - spentToday)) / allowance.daysLeft).coerceAtLeast(0)
        else -> insights?.history?.usualDaily?.roundToLong()
    }
    val onTrack = daily == null || spentToday <= daily

    AgentCard(
        title = "Money",
        icon = Icons.Rounded.Speed,
        tint = brand.action,
        onClick = actions.openMoney,
        badge = {
            if (daily != null) {
                Text(
                    if (onTrack) "On track" else "Over today",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (onTrack) brand.fine else brand.broke,
                )
            }
        },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Money.format(spentToday, summary.currency), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    daily == null -> "spent today"
                    allowance != null -> "today, of ${Money.format(daily)} a day"
                    else -> "today, your usual is ${Money.format(daily)} a day"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (daily != null && daily > 0) {
            LinearProgressIndicator(
                progress = { (spentToday.toFloat() / daily).coerceIn(0f, 1f) },
                color = if (onTrack) brand.fine else brand.broke,
                trackColor = brand.track,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                drawStopIndicator = {},
            )
        }
        if (pending.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(brand.action)
                    .clickable(onClick = actions.openPayments)
                    .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (pending.size == 1) "1 payment waiting" else "${pending.size} payments waiting") +
                        ", ${Money.format(pending.filter { it.currency == "GBP" }.sumOf { it.amountMinor })}",
                    style = MaterialTheme.typography.titleSmall,
                    color = brand.onAction,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = brand.onAction)
            }
        }
        val monthName = today.month.getDisplayName(TextStyle.FULL, Locale.UK)
        Text(
            "$monthName so far ${Money.format(summary.totalMinor, summary.currency)}" +
                (usual?.takeIf { it > 0 }?.let { ", ${summary.totalMinor * 100 / it}% of a usual month." } ?: "."),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
    }
}

// Travel

@Composable
private fun TravelCard(travel: TravelViewModel, actions: HomeActions) {
    val tracking by travel.tracking.collectAsStateWithLifecycle()
    val day by travel.today.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val blue = travelTint()

    AgentCard(title = "Travel", icon = Icons.Rounded.DirectionsSubway, tint = blue, onClick = { actions.openTravel(null) }) {
        if (!tracking) {
            Text("Note my trips is off, so nothing is noted.", style = MaterialTheme.typography.bodyMedium, color = muted)
            Text("Turn it on in Travel setup", style = MaterialTheme.typography.labelLarge, color = brand.action, modifier = Modifier.clickable(onClick = actions.openTravelSetup))
            return@AgentCard
        }
        val d = day
        val segments = d?.rows?.map { it.segment }.orEmpty()
        val line = remember(d) { TravelLines.pick(segments, d?.steps, System.currentTimeMillis()) }
        Text(line, style = MaterialTheme.typography.titleMedium)
        if (d != null) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Walked", distanceText(d.totals.walkMetres), d.steps?.let { "${"%,d".format(it)} steps" }, Modifier.weight(1f)) { actions.openTravel(Motion.WALKING) }
                Stat("Moving fast", durationText(d.totals.fast), null, Modifier.weight(1f)) { actions.openTravel(Motion.FAST) }
                Stat("Stationary", durationText(d.totals.still), null, Modifier.weight(1f)) { actions.openTravel(Motion.STILL) }
            }
            Text(tripsLine(d), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, extra: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Rides so far, counting a change of trains as one; once the night's check has run, the trips it found. */
private fun tripsLine(d: TravelDay): String {
    if (d.trips.isNotEmpty()) {
        val total = d.trips.sumOf { it.amountMinor }
        val waiting = d.trips.count { it.status == TxStatus.PENDING }
        return "${d.trips.size} ${if (d.trips.size == 1) "trip" else "trips"} found, ${Money.format(total)}" +
            if (waiting > 0) ", waiting on Payments." else "."
    }
    var rides = 0
    var lastEnd = Long.MIN_VALUE
    for (s in d.rows.map { it.segment }.filter { it.kind == Motion.FAST }) {
        if (s.start - lastEnd > 10 * 60_000L) rides++
        lastEnd = s.end
    }
    return when (rides) {
        0 -> "No rides yet today. Trips and fares are worked out tonight at 23:50."
        1 -> "1 ride so far. Fares are worked out tonight at 23:50."
        else -> "$rides rides so far. Fares are worked out tonight at 23:50."
    }
}

@Composable
internal fun travelTint(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF9DB4FF) else Color(0xFF3A55B0)

private fun durationText(millis: Long): String {
    val minutes = (millis / 60_000L).toInt()
    return when {
        minutes < 1 -> "0 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

private fun distanceText(metres: Double): String =
    if (metres < 1_000) "${(metres / 10).toInt() * 10} m" else "%.1f km".format(Locale.UK, metres / 1_000)

/** "Morning", "Afternoon" or "Evening", for the greeting at the top of Home. */
internal fun partOfDay(hour: Int): String = when (hour) {
    in 5..11 -> "Morning"
    in 12..16 -> "Afternoon"
    else -> "Evening"
}
