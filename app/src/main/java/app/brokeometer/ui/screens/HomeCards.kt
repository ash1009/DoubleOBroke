package app.brokeometer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.news.NewsRules
import app.brokeometer.ui.NotesViewModel
import app.brokeometer.ui.VaultViewModel
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import app.brokeometer.vault.VaultState

/**
 * News on Home: the latest briefing, one topic per card, swiped sideways, each with its headlines and
 * a line on each. Tapping anywhere opens News.
 */
@Composable
internal fun NewsHomeCard(vm: NewsViewModel, onOpen: () -> Unit) {
    val on by vm.on.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val briefings by vm.briefings.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val latest = briefings.firstOrNull()
    val sections = latest?.sections?.filter { it.stories.isNotEmpty() }.orEmpty()
    AgentCard(
        title = "News",
        icon = Icons.AutoMirrored.Rounded.Article,
        tint = brand.action,
        onClick = onOpen,
        badge = { if (latest != null) Text(Dates.clock(latest.at), style = MaterialTheme.typography.bodySmall, color = muted) },
    ) {
        when {
            topics.isEmpty() -> {
                Text("Pick the topics you follow, and Claude writes a short briefing every couple of hours while you're awake.", style = MaterialTheme.typography.bodyMedium, color = muted)
                Text("Pick topics", style = MaterialTheme.typography.labelLarge, color = brand.action, modifier = Modifier.clickable(onClick = onOpen))
            }
            sections.isEmpty() -> Text(
                if (on) "The first briefing comes while you're on your phone, or get one now on News." else "Briefings are off. Turn them on in News, Topics.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            else -> {
                val pager = rememberPagerState { sections.size }
                // The card reaches to the edges, so the next topic peeks in from the right.
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp),
                    contentPadding = PaddingValues(end = 48.dp),
                    pageSpacing = 10.dp,
                    verticalAlignment = Alignment.Top,
                ) { i ->
                    val s = sections[i]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = onOpen)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(s.topic.uppercase(), style = MaterialTheme.typography.labelMedium, color = brand.action, maxLines = 1)
                        s.stories.take(NewsRules.PER_TOPIC).forEach { st ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(st.headline, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (st.summary.isNotBlank()) Text(st.summary, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sections.indices.forEach { i ->
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(width = if (i == pager.currentPage) 18.dp else 6.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(if (i == pager.currentPage) brand.action else MaterialTheme.colorScheme.outline),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    vm.nextAround()?.let { next ->
                        Text(
                            if (next <= System.currentTimeMillis()) "Next when you're on your phone" else "Next around ${Dates.clock(next)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
            }
        }
    }
}

/** Notes on Home: pinned ones first, then the newest, in their colours, and a button for a new note. */
@Composable
internal fun NotesHomeCard(vm: NotesViewModel, onOpen: () -> Unit, onNote: (Long) -> Unit, onNew: () -> Unit) {
    val notes by vm.allNotes.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AgentCard(
        title = "Notes",
        icon = Icons.Rounded.Lightbulb,
        tint = brand.action,
        onClick = onOpen,
        badge = {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(brand.action)
                    .clickable(onClick = onNew)
                    .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = brand.onAction, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Note", style = MaterialTheme.typography.labelLarge, color = brand.onAction)
            }
        },
    ) {
        if (notes.isEmpty()) {
            Text("No notes yet. Write one, or bring yours in from Google Keep on Notes.", style = MaterialTheme.typography.bodyMedium, color = muted)
            return@AgentCard
        }
        notes.take(4).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { n -> Box(Modifier.weight(1f)) { NoteCard(n, compact = true) { onNote(n.id) } } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            "Pinned first, then the newest. ${notes.size} ${if (notes.size == 1) "note" else "notes"}.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

/** Passwords on Home: only whether it's locked. Nothing from inside it ever shows here. */
@Composable
internal fun PasswordsHomeCard(vault: VaultViewModel, onOpen: () -> Unit) {
    val state by vault.state.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (chip, line, button) = when (state) {
        is VaultState.None -> Triple("Not set up", "Keep your passwords here, locked with a master password.", "Set up")
        is VaultState.Locked -> Triple("Locked", "Unlock to search and copy. Nothing shows here while it's locked.", "Unlock")
        is VaultState.Open -> Triple("Open", "Open until you leave the app.", "Open")
    }
    AgentCard(
        title = "Passwords",
        icon = Icons.Rounded.Key,
        tint = brand.action,
        onClick = onOpen,
        badge = {
            Text(
                chip,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f).padding(end = 12.dp))
            QuietButton(button, onClick = onOpen, height = 44.dp)
        }
    }
}

/** A gap at the bottom of Home, so the last card clears the bottom bar. */
@Composable
internal fun HomeEnd() {
    Spacer(Modifier.height(8.dp))
}
