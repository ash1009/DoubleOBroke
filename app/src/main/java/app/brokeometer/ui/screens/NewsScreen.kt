package app.brokeometer.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.BrokeApp
import app.brokeometer.news.Briefing
import app.brokeometer.news.NewsRules
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import kotlinx.coroutines.launch

/** The News page's link to the briefing. */
class NewsViewModel(application: Application) : AndroidViewModel(application) {
    private val news = (application as BrokeApp).container.news
    val on = news.on
    val topics = news.topics
    val every = news.every
    val briefings = news.briefings
    val making = news.making
    val problem = news.problem
    val quietAt = news.quietAt

    fun setOn(value: Boolean) = news.setOn(value)
    fun setTopics(value: List<String>) = news.setTopics(value)
    fun setEvery(hours: Int) = news.setEvery(hours)
    fun nextAround(): Long? = news.nextAround()
    fun makeNow() {
        if (news.making.value) return
        viewModelScope.launch { news.make(notify = false) }
    }
}

/** "Today, 10:02", "Yesterday, 23:40" or "Saturday 17 October, 16:12". */
internal fun briefingWhen(at: Long): String {
    val day = Dates.localDate(at)
    val today = java.time.LocalDate.now()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dayName(day)
    }
    return "$label, ${Dates.clock(at)}"
}

private fun dayName(day: java.time.LocalDate): String {
    val today = java.time.LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.UK))
    }
}

/** News on a page of its own (from More, or a notification). */
@Composable
fun NewsScreen(onBack: () -> Unit, vm: NewsViewModel = viewModel()) {
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BrokeTopBar("News", onBack = onBack) {
            IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Tune, contentDescription = "Topics and timing") }
        }
        Box(Modifier.weight(1f).navigationBarsPadding()) {
            NewsBody(vm, editing = editing, onEditing = { editing = it })
        }
    }
}

/**
 * News itself: when the next one comes, the latest briefing (or one picked from the history), each
 * story with its source (tap to read it), then every briefing so far by day, with a search.
 */
@Composable
internal fun NewsBody(
    vm: NewsViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    editing: Boolean = false,
    onEditing: (Boolean) -> Unit = {},
) {
    val on by vm.on.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val every by vm.every.collectAsStateWithLifecycle()
    val briefings by vm.briefings.collectAsStateWithLifecycle()
    val making by vm.making.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val quietAt by vm.quietAt.collectAsStateWithLifecycle()
    var editingHere by remember { mutableStateOf(false) }
    var shownAt by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val briefing = briefings.firstOrNull { it.at == shownAt } ?: briefings.firstOrNull()
    val history = remember(briefings, query) { NewsRules.byDay(NewsRules.search(briefings, query), java.time.ZoneId.systemDefault()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("status") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        topics.isEmpty() -> "Pick your topics"
                        on -> if (every == 1) "Every hour while you're awake" else "Every $every hours while you're awake"
                        else -> "Briefings are off"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (topics.isEmpty()) "Like a news app: follow the topics you care about. Claude searches the web and writes short briefings."
                    else topics.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                val next = vm.nextAround()
                if (on && next != null) {
                    val nextText = if (next <= System.currentTimeMillis()) "Next one when you're next on your phone." else "Next around ${Dates.clock(next)}."
                    Text("$nextText Each one only has news since the last.", style = MaterialTheme.typography.bodyMedium)
                }
                val latest = briefings.firstOrNull()?.at ?: 0L
                if (quietAt > latest) Text("Nothing new at ${Dates.clock(quietAt)}, so nothing was sent.", style = MaterialTheme.typography.bodySmall, color = muted)
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = brand.broke) }
                if (making) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Claude is reading the news. This takes a minute or two.", style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                } else if (topics.isEmpty()) {
                    ActionButton("Pick topics", onClick = { editingHere = true }, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuietButton("Get one now", onClick = {
                            shownAt = null
                            vm.makeNow()
                        }, modifier = Modifier.weight(1f), height = 44.dp)
                        QuietButton("Topics", onClick = { editingHere = true }, modifier = Modifier.weight(1f), height = 44.dp)
                    }
                }
            }
        }
        if (briefing == null) {
            if (topics.isNotEmpty()) item("none") { EmptyNote("No briefing yet", "Get one now, or the first comes while you're on your phone.") }
            return@LazyColumn
        }
        item("when") {
            Text(briefingWhen(briefing.at), style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
        briefing.sections.filter { it.stories.isNotEmpty() }.forEachIndexed { si, section ->
            item("t-$si") {
                Text(section.topic, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            section.stories.forEachIndexed { j, story -> item("s-$si-$j") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(enabled = story.url.startsWith("http")) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(story.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(story.headline, style = MaterialTheme.typography.titleMedium)
                    if (story.summary.isNotBlank()) Text(story.summary, style = MaterialTheme.typography.bodyMedium, color = muted)
                    if (story.source.isNotBlank()) Text(story.source, style = MaterialTheme.typography.labelMedium, color = brand.action)
                }
            } }
        }
        item("history-head") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search old news") },
                    singleLine = true,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (history.isEmpty()) {
            item("history-none") { Text("Nothing matches.", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(start = 4.dp)) }
        }
        history.forEach { (day, list) ->
            item("d-$day") {
                Text(dayName(day), style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
            list.forEach { b ->
                item("b-${b.at}") {
                    val picked = b.at == briefing.at
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                shownAt = b.at
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(Dates.clock(b.at), style = MaterialTheme.typography.titleSmall, color = if (picked) brand.action else MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(NewsRules.headlines(b, 1).firstOrNull().orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val n = NewsRules.count(b)
                            Text(if (picked) "$n ${if (n == 1) "story" else "stories"}, showing now" else "$n ${if (n == 1) "story" else "stories"}", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
            }
        }
    }
    if (editing || editingHere) {
        TopicsDialog(vm, onDismiss = {
            editingHere = false
            onEditing(false)
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicsDialog(vm: NewsViewModel, onDismiss: () -> Unit) {
    val on by vm.on.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val every by vm.every.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val add: () -> Unit = {
        vm.setTopics(NewsRules.addTopic(topics, typed))
        typed = ""
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    Text("Topics and timing", style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Briefings", style = MaterialTheme.typography.titleMedium)
                            Text("A notification when there's something new.", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        Switch(
                            checked = on,
                            onCheckedChange = { vm.setOn(it) },
                            enabled = topics.isNotEmpty() || on,
                            colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("While you're awake, every", style = MaterialTheme.typography.titleMedium)
                            Text("Awake means a few minutes on your phone in the last half hour. Up after sleeping, one comes straight away.", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        IconButton(onClick = { vm.setEvery(every - 1) }, enabled = every > NewsRules.EVERY_HOURS.first) { Icon(Icons.Rounded.Remove, contentDescription = "More often") }
                        Text(if (every == 1) "1 hour" else "$every hours", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.width(72.dp))
                        IconButton(onClick = { vm.setEvery(every + 1) }, enabled = every < NewsRules.EVERY_HOURS.last) { Icon(Icons.Rounded.Add, contentDescription = "Less often") }
                    }
                    Text("Your topics", style = MaterialTheme.typography.titleMedium)
                    if (topics.isEmpty()) {
                        Text("None yet. Add your own below, or tap some popular ones.", style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        topics.forEach { t ->
                            TopicChip(t, picked = true) { vm.setTopics(topics - t) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it.take(60) },
                            placeholder = { Text("Add your own, like UK fintech") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = add, enabled = typed.isNotBlank()) { Text("Add", color = brand.action) }
                    }
                    val popular = NewsRules.PRESETS.filter { p -> topics.none { it.equals(p, ignoreCase = true) } }
                    if (popular.isNotEmpty()) {
                        Text("Popular topics", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            popular.forEach { p -> TopicChip(p, picked = false) { vm.setTopics(topics + p) } }
                        }
                    }
                    Text(
                        "Claude searches the web for each topic, so every briefing uses some of your Claude credits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicChip(text: String, picked: Boolean, onClick: () -> Unit) {
    val brand = LocalBrand.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (picked) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (picked) 8.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (picked) brand.onAction else MaterialTheme.colorScheme.onSurface)
        if (picked) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Close, contentDescription = "Remove $text", tint = brand.onAction, modifier = Modifier.size(16.dp))
        }
    }
}
