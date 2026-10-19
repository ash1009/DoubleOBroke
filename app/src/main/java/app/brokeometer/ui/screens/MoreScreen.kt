package app.brokeometer.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.limits.AppLimit
import app.brokeometer.limits.LimitRules
import app.brokeometer.ui.LimitsViewModel
import app.brokeometer.ui.VaultViewModel
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.ShareBar
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.vault.VaultState

/** The colour of App limits: a soft violet, apart from the other agents' colours. */
@Composable
internal fun limitsTint(): Color = if (LocalBrand.current.isDark) Color(0xFFB79CFF) else Color(0xFF6B4BD8)

/** More: the features without a place of their own in the bottom bar. */
@Composable
internal fun MorePage(
    limits: LimitsViewModel,
    vault: VaultViewModel,
    listState: LazyListState,
    /** The features with no place in the bar, in the order chosen in Settings, Bottom bar. */
    order: List<String>,
    onOpen: (String) -> Unit,
) {
    val onLimits = { onOpen("limits") }
    val onPasswords = { onOpen("passwords") }
    val onNews = { onOpen("news") }
    val onNotes = { onOpen("notes") }
    val list by limits.limits.collectAsStateWithLifecycle()
    val on by limits.on.collectAsStateWithLifecycle()
    val time by limits.time.collectAsStateWithLifecycle()
    val vaultState by vault.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (order.isEmpty()) {
            item("empty") {
                Text(
                    "Everything has a place in the bottom bar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        order.forEach { key ->
            when (key) {
                "email" -> item("email") { MoreRow(Icons.Rounded.Mail, LocalBrand.current.action, "Email", "Your inbox, sorted by the agent", { onOpen("email") }) }
                "money" -> item("money") { MoreRow(Icons.Rounded.Speed, LocalBrand.current.action, "Money", "Spending, payments and insights", { onOpen("money") }) }
                "travel" -> item("travel") { MoreRow(Icons.Rounded.DirectionsSubway, travelTint(), "Travel", "Trips, fares and your day", { onOpen("travel") }) }
                "limits" -> item("limits") {
                    val line = when {
                        list.isEmpty() -> "Daily limits for the apps that eat your time"
                        !on -> "Off"
                        time?.allowed == false -> "Needs Usage access"
                        else -> {
                            val busiest = list.maxByOrNull { (time?.perApp?.get(it.pkg) ?: 0L).toDouble() / (it.minutes.coerceAtLeast(1)) }!!
                            "${busiest.label}: ${LimitRules.hm(time?.perApp?.get(busiest.pkg) ?: 0L)} of ${LimitRules.hmMinutes(busiest.minutes)} today"
                        }
                    }
                    MoreRow(Icons.Rounded.HourglassBottom, limitsTint(), "App limits", line, onLimits)
                }
                "passwords" -> item("passwords") {
                    val line = when (vaultState) {
                        is VaultState.None -> "Not set up yet"
                        is VaultState.Locked -> "Locked"
                        is VaultState.Open -> "Open"
                    }
                    MoreRow(Icons.Rounded.Key, LocalBrand.current.action, "Passwords", line, onPasswords)
                }
                "notes" -> item("notes") {
                    MoreRow(Icons.Rounded.Lightbulb, LocalBrand.current.action, "Notes", "Notes and lists, like Keep", onNotes)
                }
                "news" -> item("news") {
                    val news = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.brokeometer.BrokeApp).container.news
                    val topics by news.topics.collectAsStateWithLifecycle()
                    val newsOn by news.on.collectAsStateWithLifecycle()
                    val every by news.every.collectAsStateWithLifecycle()
                    val line = when {
                        topics.isEmpty() -> "Briefings on the topics you follow"
                        newsOn -> if (every == 1) "Every hour while you're awake" else "Every $every hours while you're awake"
                        else -> "Off"
                    }
                    MoreRow(Icons.AutoMirrored.Rounded.Article, LocalBrand.current.action, "News", line, onNews)
                }
            }
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, tint: Color, title: String, line: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Passwords, as a page of its own (opened from More). */
@Composable
fun PasswordsPage(onBack: () -> Unit, vault: VaultViewModel = viewModel()) {
    val state by vault.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BrokeTopBar("Passwords", onBack = onBack) {
            if (state is VaultState.Open) {
                IconButton(onClick = { vault.lock() }) { Icon(Icons.Rounded.Lock, contentDescription = "Lock passwords") }
            }
        }
        Box(Modifier.weight(1f).navigationBarsPadding()) { PasswordsScreen(vault) }
    }
}

/** An app's own icon, or its first letter while there's none. */
@Composable
internal fun AppIcon(vm: LimitsViewModel, pkg: String, label: String, size: Dp = 40.dp) {
    val bmp = remember(pkg) { vm.icon(pkg)?.asImageBitmap() }
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
    }
}

/** How full an app's day is: green, then amber near the end, then red once it's used. */
@Composable
internal fun usageColor(fraction: Float): Color {
    val brand = LocalBrand.current
    return when {
        fraction >= 1f -> brand.broke
        fraction >= 0.75f -> brand.careful
        else -> brand.fine
    }
}

/** An app's time today against its limit, with a bar. */
@Composable
internal fun LimitLine(vm: LimitsViewModel, limit: AppLimit, usedMs: Long, extraMinutes: Int, iconSize: Dp = 36.dp) {
    val allowed = LimitRules.allowedMs(limit, extraMinutes)
    val fraction = if (allowed > 0) usedMs.toFloat() / allowed else 1f
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(vm, limit.pkg, limit.label, iconSize)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(limit.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = if (usedMs >= allowed) "Closed till midnight" else "${LimitRules.hm(allowed - usedMs)} left"
                Text(status, style = MaterialTheme.typography.labelLarge, color = if (usedMs >= allowed) LocalBrand.current.broke else muted)
            }
            ShareBar(fraction.coerceIn(0f, 1f), usageColor(fraction))
            val extra = if (extraMinutes > 0) " (with ${extraMinutes} m extra)" else ""
            Text("${LimitRules.hm(usedMs)} of ${LimitRules.hm(allowed)}$extra", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

/** Home's card for App limits: today's screen time, and the apps closest to their limit. */
@Composable
internal fun LimitsCard(vm: LimitsViewModel, onOpen: () -> Unit) {
    val list by vm.limits.collectAsStateWithLifecycle()
    val time by vm.time.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val on by vm.on.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AgentCard(title = "App limits", icon = Icons.Rounded.HourglassBottom, tint = limitsTint(), onClick = onOpen) {
        val t = time
        if (t != null && !t.allowed) {
            Text("Needs Usage access to count your time.", style = MaterialTheme.typography.bodyMedium, color = muted)
            Text("Set it up", style = MaterialTheme.typography.labelLarge, color = brand.action, modifier = Modifier.clickable(onClick = onOpen))
            return@AgentCard
        }
        if (t != null) Text("${LimitRules.hm(t.total)} on your phone today", style = MaterialTheme.typography.titleMedium)
        if (list.isEmpty()) {
            Text("Set a daily limit for the apps that eat your time.", style = MaterialTheme.typography.bodyMedium, color = muted)
            Text("Add an app", style = MaterialTheme.typography.labelLarge, color = brand.action, modifier = Modifier.clickable(onClick = onOpen))
            return@AgentCard
        }
        val closest = list.sortedByDescending { l ->
            (t?.perApp?.get(l.pkg) ?: 0L).toDouble() / LimitRules.allowedMs(l, day.extra[l.pkg] ?: 0)
        }.take(3)
        closest.forEach { l -> LimitLine(vm, l, t?.perApp?.get(l.pkg) ?: 0L, day.extra[l.pkg] ?: 0, iconSize = 32.dp) }
        if (list.size > 3) Text("and ${list.size - 3} more", style = MaterialTheme.typography.bodySmall, color = muted)
        if (!on) Text("App limits is off, so nothing is closed.", style = MaterialTheme.typography.bodySmall, color = brand.careful)
    }
}
