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
    val time = news.time
    val briefings = news.briefings
    val making = news.making
    val problem = news.problem

    fun setOn(value: Boolean) = news.setOn(value)
    fun setTopics(value: List<String>) = news.setTopics(value)
    fun setTime(minuteOfDay: Int) = news.setTime(minuteOfDay)
    fun makeNow() {
        if (news.making.value) return
        viewModelScope.launch { news.make(notify = false) }
    }
}

/**
 * News: the latest briefing (or an earlier one), each story with its source, tapping one opens the
 * article. Topics and the time it comes are set from the button at the top.
 */
@Composable
fun NewsScreen(onBack: () -> Unit, vm: NewsViewModel = viewModel()) {
    val on by vm.on.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val time by vm.time.collectAsStateWithLifecycle()
    val briefings by vm.briefings.collectAsStateWithLifecycle()
    val making by vm.making.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(0) }
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    val briefing = briefings.getOrNull(shown) ?: briefings.firstOrNull()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BrokeTopBar("News", onBack = onBack) {
            IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Tune, contentDescription = "Topics and time") }
        }
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        when {
                            topics.isEmpty() -> "Pick your topics"
                            on -> "Every day at ${NewsRules.clock(time)}"
                            else -> "Daily briefing is off"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (topics.isEmpty()) "Like a news app: follow the topics you care about. Claude searches the web each day and writes a short briefing."
                        else topics.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = brand.broke) }
                    if (topics.isEmpty()) {
                        ActionButton("Pick topics", onClick = { editing = true }, modifier = Modifier.fillMaxWidth())
                    } else if (making) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Claude is reading today's news. This takes a minute or two.", style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    } else {
                        QuietButton("Get a briefing now", onClick = {
                            shown = 0
                            vm.makeNow()
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (briefing == null) {
                if (topics.isNotEmpty()) item("none") { EmptyNote("No briefing yet", "The first one comes at ${NewsRules.clock(time)}, or get one now.") }
                return@LazyColumn
            }
            item("when") {
                Text(
                    "${Dates.pretty(Dates.dayOf(briefing.at))}, ${Dates.clock(briefing.at)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
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
            if (briefings.size > 1) {
                item("earlier") {
                    Text("Earlier briefings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 16.dp))
                }
                items(briefings.indices.toList(), key = { "b-$it" }) { i ->
                    val b: Briefing = briefings[i]
                    Text(
                        "${Dates.pretty(Dates.dayOf(b.at))}, ${Dates.clock(b.at)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (b === briefing) brand.action else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { shown = i }.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    if (editing) {
        TopicsDialog(vm, onDismiss = { editing = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicsDialog(vm: NewsViewModel, onDismiss: () -> Unit) {
    val on by vm.on.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val time by vm.time.collectAsStateWithLifecycle()
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
                    Text("Topics and time", style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Daily briefing", style = MaterialTheme.typography.titleMedium)
                            Text("A notification when it's ready.", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        Switch(
                            checked = on,
                            onCheckedChange = { vm.setOn(it) },
                            enabled = topics.isNotEmpty() || on,
                            colors = SwitchDefaults.colors(checkedTrackColor = brand.action, checkedThumbColor = brand.onAction),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Comes at", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.setTime(NewsRules.step(time, -1)) }) { Icon(Icons.Rounded.Remove, contentDescription = "Earlier") }
                        Text(NewsRules.clock(time), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
                        IconButton(onClick = { vm.setTime(NewsRules.step(time, 1)) }) { Icon(Icons.Rounded.Add, contentDescription = "Later") }
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
