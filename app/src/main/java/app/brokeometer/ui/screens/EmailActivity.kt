package app.brokeometer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import app.brokeometer.ui.EmailBrowse
import app.brokeometer.ui.MailViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.brokeometer.mail.MailAccountEntity
import app.brokeometer.mail.MailActions
import app.brokeometer.mail.MailLogEntity
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.SectionTitle
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Time filters on the Activity list. */
enum class Since(val label: String) {
    ANY("Any time"),
    DAY("Last 24 hours"),
    WEEK("Last week"),
    MONTH("Last month"),
    CUSTOM("Pick dates"),
}

private enum class Panel { NONE, ACCOUNTS, DATES }

/**
 * Activity's filters. Kept with the email view model rather than the page, so they stay the same
 * however the page is reached (swiping in from another part, tapping Email, coming back from an email).
 */
class ActivityFilters {
    var accountId by mutableStateOf<Long?>(null)
    var since by mutableStateOf(Since.ANY)
    var fromDay by mutableStateOf<String?>(null)
    var toDay by mutableStateOf<String?>(null)
    var unreadOnly by mutableStateOf(false)
}

/** Everything the Activity page needs from the Email page. */
class ActivityState(
    val log: List<MailLogEntity>,
    val accounts: List<MailAccountEntity>,
    val problem: String?,
    val working: Set<Long>,
    val checking: Boolean,
    val markingAll: Boolean,
    val agentOn: Boolean,
)

data class ActivityActions(
    val open: (MailLogEntity) -> Unit,
    val markRead: (MailLogEntity) -> Unit,
    val markUnread: (MailLogEntity) -> Unit,
    val spam: (MailLogEntity) -> Unit,
    val notSpam: (MailLogEntity) -> Unit,
    val markAllRead: (List<MailLogEntity>) -> Unit,
    val checkNow: () -> Unit,
    val tidyUp: () -> Unit,
    val dismissProblem: () -> Unit,
    val pickMany: (List<MailLogEntity>, MailViewModel.Pick) -> Unit,
    val restore: (MailLogEntity) -> Unit,
)

/**
 * The Activity page: filters along the top (scroll them sideways), then the emails. While some still
 * need you (unread, or spam that might be real), those come first, most important first; the rest
 * follow by day. Once all are dealt with, it is simply newest first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ActivityPage(
    state: ActivityState,
    actions: ActivityActions,
    listState: LazyListState,
    top: LazyListScope.() -> Unit,
    vm: MailViewModel? = null,
) {
    val filters = vm?.activityFilters ?: remember { ActivityFilters() }
    var accountId by filters::accountId
    var since by filters::since
    var fromDay by filters::fromDay
    var toDay by filters::toDay
    var unreadOnly by filters::unreadOnly
    // Home's "and N more" asks for the unread ones.
    val unreadAsked = vm?.showUnread?.collectAsStateWithLifecycle()?.value == true
    LaunchedEffect(unreadAsked) {
        if (unreadAsked) {
            unreadOnly = true
            accountId = null
            since = Since.ANY
            vm?.showUnreadDone()
        }
    }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var pickingDates by remember { mutableStateOf(false) }
    var confirmTidy by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val shown = remember(state.log, accountId, since, fromDay, toDay, unreadOnly) {
        val start = when (since) {
            Since.ANY -> null
            Since.DAY -> now - TimeUnit.HOURS.toMillis(24)
            Since.WEEK -> now - TimeUnit.DAYS.toMillis(7)
            Since.MONTH -> now - TimeUnit.DAYS.toMillis(30)
            Since.CUSTOM -> fromDay?.let { Dates.parseDay(it).atStartOfDay(Dates.zone()).toInstant().toEpochMilli() }
        }
        val end = if (since == Since.CUSTOM) toDay?.let { Dates.parseDay(it).plusDays(1).atStartOfDay(Dates.zone()).toInstant().toEpochMilli() } else null
        state.log.filter { e ->
            (accountId == null || e.accountId == accountId) &&
                (start == null || e.at >= start) &&
                (end == null || e.at < end) &&
                (!unreadOnly || MailActions.leftUnread(e))
        }
    }
    // While some emails still need you, they come first, most important first; the rest go by day.
    val needsYou = remember(shown) {
        shown.filter { MailActions.attention(it) != null }
            .sortedWith(compareBy<MailLogEntity> { MailActions.attention(it) }.thenByDescending { it.at })
    }
    val rest = remember(shown, needsYou) {
        val ids = needsYou.map { it.id }.toSet()
        shown.filter { it.id !in ids }
    }
    // Opening an email remembers the list as shown, so the email page can swipe to the next one and back.
    val order = remember(needsYou, rest) { needsYou.map { it.id } + rest.map { it.id } }
    val rowActions = remember(actions, order) {
        actions.copy(open = { e ->
            EmailBrowse.ids = order
            actions.open(e)
        })
    }

    // Picking several: a long press starts it, taps add or take away, Back or the cross ends it.
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val picking = picked.isNotEmpty()
    BackHandler(enabled = picking) { picked = emptyList() }
    val pickedRows = remember(picked, shown) { shown.filter { it.id in picked } }
    val toggle: (MailLogEntity) -> Unit = { e -> picked = if (e.id in picked) picked - e.id else picked + e.id }
    val pickAction: (MailViewModel.Pick) -> Unit = { kind ->
        actions.pickMany(pickedRows, kind)
        picked = emptyList()
    }
    val unreadShown = shown.count { MailActions.leftUnread(it) }
    val showAccount = state.accounts.size > 1

    Column(Modifier.fillMaxSize()) {
        if (picking) {
            PickBar(
                count = pickedRows.size,
                onClose = { picked = emptyList() },
                onAll = { picked = shown.map { it.id } },
                onPick = pickAction,
            )
        } else {
        // The filters and buttons: one row that scrolls sideways, with choices opening just below it.
        Row(
            Modifier
                .fillMaxWidth()
                .nestedScroll(KeepInRow)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accountLabel = state.accounts.firstOrNull { it.id == accountId }?.address ?: "All emails"
            Pill(accountLabel, selected = accountId != null, open = panel == Panel.ACCOUNTS) {
                panel = if (panel == Panel.ACCOUNTS) Panel.NONE else Panel.ACCOUNTS
            }
            val dateLabel = if (since == Since.CUSTOM && fromDay != null) rangeLabel(fromDay!!, toDay ?: fromDay!!) else since.label
            Pill(dateLabel, selected = since != Since.ANY, open = panel == Panel.DATES) {
                panel = if (panel == Panel.DATES) Panel.NONE else Panel.DATES
            }
            Pill("Unread", selected = unreadOnly, tick = unreadOnly) { unreadOnly = !unreadOnly }
            if (state.agentOn) Pill(if (state.checking) "Checking" else "Check now", busy = state.checking) { actions.checkNow() }
            Pill("Tidy up old emails") { confirmTidy = true }
            if (unreadShown > 0 || state.markingAll) {
                Pill(if (state.markingAll) "Marking read" else "Mark all read", busy = state.markingAll) {
                    if (!state.markingAll) confirmAll = true
                }
            }
        }
        AnimatedVisibility(panel == Panel.ACCOUNTS, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Choices {
                Choice("All emails", accountId == null) {
                    accountId = null
                    panel = Panel.NONE
                }
                state.accounts.forEach { a ->
                    Choice(a.address, accountId == a.id) {
                        accountId = a.id
                        panel = Panel.NONE
                    }
                }
            }
        }
        AnimatedVisibility(panel == Panel.DATES, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Choices {
                Since.entries.forEach { s ->
                    Choice(if (s == Since.CUSTOM) "Pick dates..." else s.label, since == s) {
                        if (s == Since.CUSTOM) {
                            pickingDates = true
                        } else {
                            since = s
                            panel = Panel.NONE
                        }
                    }
                }
            }
        }

        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 110.dp)) {
            top()
            if (state.problem != null) {
                item("problem") { NoteCard(state.problem, LocalBrand.current.broke, actions.dismissProblem) }
            }
            if (shown.isEmpty()) {
                item("empty") {
                    EmptyNote(
                        title = if (state.log.isEmpty()) "Nothing yet" else "Nothing here",
                        body = when {
                            state.log.isNotEmpty() -> "No emails match these filters."
                            state.agentOn -> "Every email the agent handles shows here, newest first."
                            else -> "Turn on the agent under What matters. Every email it handles then shows here."
                        },
                    )
                }
                return@LazyColumn
            }
            if (needsYou.isNotEmpty()) {
                item("needs") { SectionTitle("Needs you") }
                items(needsYou, key = { "n${it.id}" }) { e ->
                    ActivityRow(e, withDay = true, account = accountOf(state, e, showAccount), busy = e.id in state.working, actions = rowActions, picking = picking, picked = e.id in picked, onToggle = toggle)
                }
            }
            var lastDay = ""
            for (e in rest) {
                val day = Dates.dayOf(e.at)
                if (day != lastDay) {
                    lastDay = day
                    item("d$day") { SectionTitle(Dates.pretty(day)) }
                }
                item("m${e.id}") {
                    ActivityRow(e, withDay = false, account = accountOf(state, e, showAccount), busy = e.id in state.working, actions = rowActions, picking = picking, picked = e.id in picked, onToggle = toggle)
                }
            }
        }
    }

    if (pickingDates) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickingDates = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedStartDateMillis != null,
                    onClick = {
                        val start = picker.selectedStartDateMillis
                        if (start != null) {
                            fromDay = utcDay(start)
                            toDay = utcDay(picker.selectedEndDateMillis ?: start)
                            since = Since.CUSTOM
                            panel = Panel.NONE
                        }
                        pickingDates = false
                    },
                ) { Text("Show these days", color = LocalBrand.current.action) }
            },
            dismissButton = { TextButton(onClick = { pickingDates = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = picker, modifier = Modifier.weight(1f), showModeToggle = false)
        }
    }
    if (confirmTidy) {
        AlertDialog(
            onDismissRequest = { confirmTidy = false },
            title = { Text("Tidy up old emails?") },
            text = {
                Text(
                    "Goes through every unread email in all your accounts. Kinds switched off under What matters are " +
                        "marked read and archived; kinds switched on, and anything unclear, stay unread. No notifications. " +
                        "Every email is listed here. It can take a while; a notification shows how it's going."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTidy = false
                    actions.tidyUp()
                }) { Text("Tidy up", color = LocalBrand.current.action) }
            },
            dismissButton = { TextButton(onClick = { confirmTidy = false }) { Text("Cancel") } },
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Mark all read?") },
            text = { Text("The ${"%,d".format(unreadShown)} unread ${if (unreadShown == 1) "email" else "emails"} shown here are marked read. They stay in your inbox.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    actions.markAllRead(shown)
                }) { Text("Yes, mark read", color = LocalBrand.current.action) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Sliding the filter row past its end stops there, instead of flipping to the next page. Swiping
 * anywhere else still moves between pages.
 */
internal object KeepInRow : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available.copy(y = 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available.copy(y = 0f)
}

private fun accountOf(state: ActivityState, e: MailLogEntity, show: Boolean): String? =
    if (show) state.accounts.firstOrNull { it.id == e.accountId }?.address else null

private fun utcDay(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

private fun rangeLabel(from: String, to: String): String {
    val f = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    val a = LocalDate.parse(from)
    val b = LocalDate.parse(to)
    return if (a == b) a.format(f) else "${a.format(f)} to ${b.format(f)}"
}

/** A rounded button in the filter row. With [open] it shows an arrow, for filters that open choices below. */
@Composable
internal fun Pill(
    label: String,
    selected: Boolean = false,
    open: Boolean? = null,
    tick: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val brand = LocalBrand.current
    val container = if (selected) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (selected) brand.onAction else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (open != null) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        } else if (tick) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        if (open != null) {
            Spacer(Modifier.width(2.dp))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun Choices(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .nestedScroll(KeepInRow)
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** One choice under an opened filter: an outlined pill, filled when it is the current one. */
@Composable
internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val brand = LocalBrand.current
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(if (selected) brand.action.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) brand.action else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** What happened to an email, in a few words, and the colour it shows in. */
@Composable
fun outcomeOf(e: MailLogEntity): Pair<String, Color> {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val a = MailActions
    if (e.undone) return "Back in your inbox, unread" to brand.action
    return when (e.action) {
        a.NOTIFIED -> "Notified you" to brand.action
        a.ARCHIVED -> "Marked read and archived" to muted
        a.READ_ELSEWHERE -> "Left alone, you'd read it" to muted
        a.UNDECIDED -> "Couldn't decide, left in your inbox" to brand.careful
        a.SPAM_DELETED -> "Spam, moved to the bin" to brand.broke
        a.SPAM_FLAGGED -> "In spam, might be real" to brand.careful
        a.NOT_SPAM -> "Not spam, moved to your inbox" to brand.fine
        a.MOVED_FROM_SPAM -> "Moved out of spam" to brand.fine
        a.KEPT -> "Left unread" to muted
        a.MARKED_READ -> "Marked read" to muted
        a.MARKED_UNREAD -> "Marked unread" to brand.action
        a.DELETED -> "Deleted, in the bin" to muted
        else -> e.action to muted
    }
}

/**
 * One email: who it's from, the subject, what happened to it, and which account it came to. Tap for the
 * whole email; press and hold to start picking several.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActivityRow(
    e: MailLogEntity,
    withDay: Boolean,
    account: String?,
    busy: Boolean,
    actions: ActivityActions,
    picking: Boolean,
    picked: Boolean,
    onToggle: (MailLogEntity) -> Unit,
) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (label, color) = outcomeOf(e)
    val unread = MailActions.leftUnread(e)
    val day = Dates.dayOf(e.at)
    val time = if (withDay && day != Dates.dayOf(System.currentTimeMillis())) "${Dates.prettyShort(day)} ${Dates.clock(e.at)}" else Dates.clock(e.at)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    picked -> brand.action.copy(alpha = 0.22f)
                    unread -> brand.action.copy(alpha = 0.09f)
                    else -> Color.Transparent
                }
            )
            .combinedClickable(
                onClick = { if (picking) onToggle(e) else actions.open(e) },
                onLongClick = { onToggle(e) },
            )
            .padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Unread: a dot on the left, a tinted row and bold words, as in an email app. Picking: a tick.
        Box(Modifier.width(if (picking) 30.dp else 10.dp), contentAlignment = Alignment.Center) {
            when {
                picking -> Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (picked) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) Icon(Icons.Rounded.Check, contentDescription = "Picked", tint = brand.onAction, modifier = Modifier.size(16.dp))
                }
                unread -> Box(Modifier.size(8.dp).clip(CircleShape).background(brand.action))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.fromName.ifBlank { e.fromAddress }.ifBlank { "Unknown sender" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                e.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(label)
                    append(", ")
                    append(time)
                    if (e.categoryName.isNotBlank() && e.folder == "inbox") append(", ${e.categoryName}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account != null) Text(account, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val a = MailActions
        when {
            busy -> CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(22.dp))
            picking -> Unit
            a.binned(e) -> RowIcon(Icons.AutoMirrored.Rounded.Undo, "Put back in my inbox", muted) { actions.restore(e) }
            !e.undone && e.action == a.SPAM_FLAGGED -> Row {
                RowIcon(Icons.Rounded.Check, "Spam, move it to the bin", brand.broke) { actions.spam(e) }
                RowIcon(Icons.Rounded.Close, "Not spam, move it to my inbox", brand.fine) { actions.notSpam(e) }
            }
            !e.undone && e.messageId != null && e.action == a.SPAM_DELETED ->
                RowIcon(Icons.Rounded.Close, "Not spam, move it to my inbox", brand.fine) { actions.notSpam(e) }
            unread -> RowIcon(Icons.Outlined.Drafts, "Mark read", brand.action) { actions.markRead(e) }
            a.canMarkUnread(e) -> RowIcon(Icons.Outlined.Email, "Mark unread", muted) { actions.markUnread(e) }
        }
    }
}

/** What shows instead of the filters while emails are picked: how many, and what to do with them. */
@Composable
private fun PickBar(count: Int, onClose: () -> Unit, onAll: () -> Unit, onPick: (MailViewModel.Pick) -> Unit) {
    val brand = LocalBrand.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Stop picking") }
        Text("$count picked", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onAll) { Icon(Icons.Rounded.SelectAll, contentDescription = "Pick all shown") }
        IconButton(onClick = { onPick(MailViewModel.Pick.READ) }) { Icon(Icons.Outlined.Drafts, contentDescription = "Mark read", tint = brand.action) }
        IconButton(onClick = { onPick(MailViewModel.Pick.UNREAD) }) { Icon(Icons.Outlined.Email, contentDescription = "Mark unread") }
        IconButton(onClick = { onPick(MailViewModel.Pick.ARCHIVE) }) { Icon(Icons.Outlined.Archive, contentDescription = "Archive") }
        IconButton(onClick = { onPick(MailViewModel.Pick.DELETE) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = brand.broke) }
    }
}

@Composable
private fun RowIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description, tint = tint) }
}

@Composable
fun NoteCard(text: String, color: Color, onOk: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        TextButton(onClick = onOk) { Text("OK") }
    }
}
