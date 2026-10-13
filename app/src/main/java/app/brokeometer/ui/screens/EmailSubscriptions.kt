package app.brokeometer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.brokeometer.mail.MailAccountEntity
import app.brokeometer.mail.MailSenderEntity
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.SectionTitle
import app.brokeometer.ui.theme.LocalBrand

private enum class ListStatus(val label: String) { ALL("All lists"), ON("Subscribed"), OFF("Unsubscribed") }

private enum class ListSort(val label: String) { MOST("Most emails"), LEAST_OPENED("Least opened"), NEWEST("Newest"), NAME("A to Z") }

private enum class ListPanel { NONE, ACCOUNTS, STATUS, SORT, SEARCH }

private fun openedOf(s: MailSenderEntity): Int = if (s.total > 0) (s.total - s.unread) * 100 / s.total else 0

/**
 * Every mailing list, with filters like Activity's: which account, subscribed or not, the ones you
 * rarely open, how they are sorted, and a search by name or address.
 */
@Composable
fun SubscriptionsPage(
    accounts: List<MailAccountEntity>,
    senders: List<MailSenderEntity>,
    listState: LazyListState,
    onLeave: (MailSenderEntity) -> Unit,
    top: LazyListScope.() -> Unit,
) {
    var accountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var status by rememberSaveable { mutableStateOf(ListStatus.ALL) }
    var rarely by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(ListSort.MOST) }
    var search by rememberSaveable { mutableStateOf("") }
    var panel by remember { mutableStateOf(ListPanel.NONE) }

    val lists = remember(senders) { senders.filter { it.unsubscribe != null } }
    val shown = remember(lists, accountId, status, rarely, sort, search) {
        val q = search.trim().lowercase()
        lists.filter { s ->
            (accountId == null || s.accountId == accountId) &&
                when (status) {
                    ListStatus.ALL -> true
                    ListStatus.ON -> s.unsubscribedAt == null
                    ListStatus.OFF -> s.unsubscribedAt != null
                } &&
                (!rarely || openedOf(s) < 20) &&
                (q.isEmpty() || s.name.lowercase().contains(q) || s.address.contains(q))
        }.sortedWith(
            when (sort) {
                ListSort.MOST -> compareBy<MailSenderEntity> { it.unsubscribedAt != null }.thenByDescending { it.recent }.thenByDescending { it.total }
                ListSort.LEAST_OPENED -> compareBy<MailSenderEntity> { it.unsubscribedAt != null }.thenBy { openedOf(it) }.thenByDescending { it.total }
                ListSort.NEWEST -> compareByDescending<MailSenderEntity> { it.lastAt }
                ListSort.NAME -> compareBy<MailSenderEntity> { displayName(it).lowercase() }
            }
        )
    }

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
            fun flip(p: ListPanel) {
                panel = if (panel == p) ListPanel.NONE else p
            }
            Pill(accounts.firstOrNull { it.id == accountId }?.address ?: "All emails", selected = accountId != null, open = panel == ListPanel.ACCOUNTS) { flip(ListPanel.ACCOUNTS) }
            Pill(status.label, selected = status != ListStatus.ALL, open = panel == ListPanel.STATUS) { flip(ListPanel.STATUS) }
            Pill("Rarely opened", selected = rarely, tick = rarely) { rarely = !rarely }
            Pill(sort.label, selected = sort != ListSort.MOST, open = panel == ListPanel.SORT) { flip(ListPanel.SORT) }
            Pill(if (search.isBlank()) "Search" else "\"${search.trim()}\"", selected = search.isNotBlank(), open = panel == ListPanel.SEARCH) { flip(ListPanel.SEARCH) }
        }
        AnimatedVisibility(panel == ListPanel.ACCOUNTS, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Choices {
                Choice("All emails", accountId == null) {
                    accountId = null
                    panel = ListPanel.NONE
                }
                accounts.forEach { a ->
                    Choice(a.address, accountId == a.id) {
                        accountId = a.id
                        panel = ListPanel.NONE
                    }
                }
            }
        }
        AnimatedVisibility(panel == ListPanel.STATUS, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Choices {
                ListStatus.entries.forEach { st ->
                    Choice(st.label, status == st) {
                        status = st
                        panel = ListPanel.NONE
                    }
                }
            }
        }
        AnimatedVisibility(panel == ListPanel.SORT, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Choices {
                ListSort.entries.forEach { so ->
                    Choice(so.label, sort == so) {
                        sort = so
                        panel = ListPanel.NONE
                    }
                }
            }
        }
        AnimatedVisibility(panel == ListPanel.SEARCH, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Name or address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                )
                if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("Clear", color = LocalBrand.current.action) }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 110.dp)) {
            top()
            when {
                lists.isEmpty() -> item("empty") {
                    EmptyNote(
                        title = "No mailing lists yet",
                        body = "Once the app has read your email, every newsletter and shop list you are on shows here, with how often they write and how often you open them.",
                    )
                }
                shown.isEmpty() -> item("none") { EmptyNote(title = "Nothing here", body = "No lists match these filters.") }
                else -> {
                    item("explain") {
                        Text(
                            "${sort.label} first. \"Opened\" counts the emails you have read.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    for (account in accounts) {
                        val mine = shown.filter { it.accountId == account.id }
                        if (mine.isEmpty()) continue
                        item("h${account.id}") {
                            SectionTitle("${account.address} (${mine.count { it.unsubscribedAt == null }})")
                        }
                        items(mine, key = { "l${it.id}" }) { sender -> ListRow(sender, onLeave) }
                    }
                }
            }
        }
    }
}
