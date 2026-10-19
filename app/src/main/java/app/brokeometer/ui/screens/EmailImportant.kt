package app.brokeometer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.mail.MailAccountEntity
import app.brokeometer.mail.MailLogEntity
import app.brokeometer.mail.MailStarredEntity
import app.brokeometer.mail.StarredMail
import app.brokeometer.ui.EmailBrowse
import app.brokeometer.ui.MailViewModel
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates

/**
 * Important: every starred email, and in Gmail every one marked important, newest first. Pull down to
 * match it with Gmail and Yahoo; it also does that every few minutes when opened. Tap the star to
 * unstar one (in the mailbox too).
 */
@Composable
internal fun ImportantPage(
    vm: MailViewModel,
    accounts: List<MailAccountEntity>,
    log: List<MailLogEntity>,
    listState: LazyListState,
    top: LazyListScope.() -> Unit,
    onOpenEmail: (Long) -> Unit,
) {
    val rows by vm.starred.collectAsStateWithLifecycle()
    val starring by vm.starring.collectAsStateWithLifecycle()
    val syncing by vm.syncingStars.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.syncStars(force = false) }

    // An email the agent handled opens as its Activity entry, so everything there works on it too.
    val handled = remember(log) {
        log.filter { it.messageId != null }
            .groupBy { it.accountId to it.messageId!! }
            .mapValues { (_, list) -> list.maxBy { it.id }.id }
    }
    val ids = remember(rows, handled) { rows.map { r -> handled[r.accountId to r.messageId] ?: -r.id } }
    val showAccount = accounts.size > 1

    RefreshBox(busy = syncing, onRefresh = { vm.syncStars(force = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 110.dp)) {
            top()
            if (rows.isEmpty()) {
                item("empty") {
                    EmptyNote(
                        title = if (syncing) "Looking for starred emails..." else "Nothing starred",
                        body = "Starred emails show here, and in Gmail the ones marked important too. Star one here, in Gmail or in Yahoo.",
                    )
                }
                return@LazyColumn
            }
            items(rows.indices.toList(), key = { rows[it].id }) { i ->
                val r = rows[i]
                val changing = starring[MailViewModel.starKey(r.accountId, r.messageId)]
                StarredRow(
                    r,
                    account = if (showAccount) accounts.firstOrNull { it.id == r.accountId }?.address else null,
                    starred = changing ?: true,
                    onOpen = {
                        EmailBrowse.ids = ids
                        onOpenEmail(ids[i])
                    },
                    onStar = {
                        vm.setStar(r.accountId, r.messageId, false, StarredMail.About(r.fromName, r.fromAddress, r.subject, r.at, r.seen))
                    },
                )
            }
        }
    }
}

/**
 * Pull to refresh that always lets go. Material's pull keeps its arrow on screen until it sees a
 * refresh start and then end; a check that was turned away (one was already running) or that was
 * over before the next frame never shows as started, and the arrow just sat there. Here the spinner
 * runs from the pull until nothing is busy (including [waitFor], something already running that the
 * pull waits on), and a moment more.
 */
@Composable
internal fun RefreshBox(
    busy: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    waitFor: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(pulled, busy || waitFor) {
        if (pulled && !busy && !waitFor) {
            delay(700)
            pulled = false
        }
    }
    PullToRefreshBox(
        isRefreshing = busy || pulled,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun StarredRow(r: MailStarredEntity, account: String?, starred: Boolean, onOpen: () -> Unit, onStar: () -> Unit) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val unread = !r.seen
    val day = Dates.dayOf(r.at)
    val time = if (day != Dates.dayOf(System.currentTimeMillis())) "${Dates.prettyShort(day)} ${Dates.clock(r.at)}" else Dates.clock(r.at)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (unread) brand.action.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(10.dp), contentAlignment = Alignment.Center) {
            if (unread) Box(Modifier.size(8.dp).clip(CircleShape).background(brand.action))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.fromName.ifBlank { r.fromAddress }.ifBlank { "Unknown sender" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                r.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(time)
                    if (r.important && !r.starred) append(", marked important")
                },
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
            )
            if (account != null) Text(account, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onStar, enabled = starred) {
            Icon(
                if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = "Unstar",
                tint = if (starred) brand.action else muted,
            )
        }
    }
}
