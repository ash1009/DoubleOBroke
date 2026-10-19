package app.brokeometer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.mail.MailActions
import app.brokeometer.travel.Motion
import app.brokeometer.ui.components.Avatar
import app.brokeometer.ui.screens.CategoriesScreen
import app.brokeometer.ui.screens.EmailPage
import app.brokeometer.ui.screens.HomeActions
import app.brokeometer.ui.screens.HomePage
import app.brokeometer.ui.screens.InsightsScreen
import app.brokeometer.ui.screens.MoneyInScreen
import app.brokeometer.ui.screens.MoneyThisMonthPage
import app.brokeometer.ui.screens.MonthScreen
import app.brokeometer.ui.screens.MorePage
import app.brokeometer.ui.screens.PaymentsScreen
import app.brokeometer.ui.screens.Tabs
import app.brokeometer.ui.screens.TravelPage
import app.brokeometer.ui.screens.emailPageNames
import app.brokeometer.ui.screens.partOfDay
import app.brokeometer.ui.screens.rememberMenuPull
import app.brokeometer.ui.theme.LocalBrand
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** The app's main parts: Home and each agent, with the pages inside each. */
enum class Section(val key: String, val label: String, val pages: List<String>, val icon: ImageVector) {
    HOME("home", "Home", listOf("Home"), Icons.Rounded.Home),
    EMAIL("email", "Email", emailPageNames, Icons.Rounded.Mail),
    MONEY("money", "Money", listOf("This month", "Payments", "Insights", "Months", "Categories", "Money in"), Icons.Rounded.Speed),
    TRAVEL("travel", "Travel", listOf("Activity", "Setup"), Icons.Rounded.DirectionsSubway),
    /** Features that don't need a place of their own in the bar: App limits, Passwords, and ones to come. */
    MORE("more", "More", listOf("More"), Icons.Rounded.MoreHoriz),
}

/** One page in the swipe line: a part, and which of its pages. */
data class ShellPage(val section: Section, val inner: Int) {
    val id: String get() = "${section.key}/$inner"
}

/**
 * Home first, then each agent with a place in the bar in the order chosen in Settings, each with its
 * pages one after another, and More last.
 */
fun shellPages(order: List<String>): List<ShellPage> {
    val agents = order.mapNotNull { key -> Section.entries.firstOrNull { it.key == key && it != Section.HOME && it != Section.MORE } }
    val sections = listOf(Section.HOME) + agents + Section.MORE
    return sections.flatMap { s -> s.pages.indices.map { ShellPage(s, it) } }
}

/** Which page a route from elsewhere (a notification, an old link) means. */
fun shellPageFor(route: String): Pair<Section, Int>? = when (route) {
    Routes.HOME -> Section.HOME to 0
    Routes.EMAIL -> Section.EMAIL to 0
    Routes.MONEY -> Section.MONEY to 0
    Routes.PENDING -> Section.MONEY to 1
    Routes.INSIGHTS -> Section.MONEY to 2
    Routes.MONTHS -> Section.MONEY to 3
    Routes.CATEGORIES -> Section.MONEY to 4
    Routes.MONEY_IN -> Section.MONEY to 5
    Routes.TRAVEL -> Section.TRAVEL to 0
    Routes.MORE -> Section.MORE to 0
    else -> null
}

/** Ways out of the main screen, to pages of their own. */
class ShellLinks(
    val openMenu: () -> Unit,
    val openEmail: (Long) -> Unit,
    val write: () -> Unit,
    val emailSetup: () -> Unit,
    val account: () -> Unit,
    val recap: (Int) -> Unit,
    val passwords: () -> Unit,
    val limits: (Int) -> Unit,
    val news: () -> Unit = {},
)

/**
 * The main screen: a top bar, the names of the pages in the current part, the pages themselves in one
 * line (swipe left or right through them all: Home, then each agent's pages in turn), and a bar at the
 * bottom to jump to each part. Back goes to the page you were on before; on Home it leaves the app.
 */
@Composable
fun MainShell(app: AppViewModel, target: String?, onTargetHandled: () -> Unit, links: ShellLinks) {
    val order by app.agentOrder.collectAsStateWithLifecycle()
    val pages = remember(order) { shellPages(order) }
    val latestPages by rememberUpdatedState(pages)
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val mail: MailViewModel = viewModel()
    val travel: TravelViewModel = viewModel()
    val vault: VaultViewModel = viewModel()
    val limits: LimitsViewModel = viewModel()

    // The page the swipe started from. Swiping back into the part before it shows that part's first
    // page (Email's Activity, say), not its last; once the swipe settles, the real first page takes over.
    var anchor by rememberSaveable { mutableIntStateOf(pager.currentPage) }
    // A move made by the app (a tap, Back) goes to exactly the page asked for.
    var steering by remember { mutableStateOf(false) }

    fun entersBackwards(index: Int, from: Int, list: List<ShellPage>): Boolean {
        val real = list.getOrNull(index) ?: return false
        val start = list.getOrNull(from) ?: return false
        return index == from - 1 && real.section != start.section && real.inner != 0
    }

    /** What a slot of the swipe line shows right now. */
    fun shown(index: Int): ShellPage {
        val real = pages[index]
        return if (!steering && entersBackwards(index, anchor, pages)) ShellPage(real.section, 0) else real
    }

    val current = shown(pager.currentPage.coerceIn(0, pages.size - 1))

    fun jump(index: Int) {
        if (index < 0) return
        // Already there: nothing will settle, so nothing to steer.
        if (index != pager.currentPage) steering = true
        scope.launch {
            // Next door slides across; further away jumps straight there.
            if (abs(index - pager.currentPage) <= 1) pager.animateScrollToPage(index) else pager.scrollToPage(index)
        }
    }

    fun go(section: Section, inner: Int = 0) = jump(pages.indexOfFirst { it.section == section && it.inner == inner })

    // The last page seen in each part, for its icon in the bottom bar.
    val lastInner = remember { mutableStateMapOf<Section, Int>() }

    // The pages visited before this one, so Back goes back through them.
    var history by rememberSaveable { mutableStateOf(listOf<String>()) }
    var goingBack by remember { mutableStateOf(false) }
    var settledId by rememberSaveable { mutableStateOf(pages.first().id) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p ->
            val list = latestPages
            var landed = p
            if (!steering && entersBackwards(p, anchor, list)) {
                landed = list.indexOfFirst { it.section == list[p].section && it.inner == 0 }
                pager.scrollToPage(landed)
            }
            steering = false
            anchor = landed
            val page = list.getOrNull(landed) ?: return@collect
            lastInner[page.section] = page.inner
            if (page.id != settledId) {
                if (!goingBack) history = (history + settledId).takeLast(30)
                goingBack = false
                settledId = page.id
            }
        }
    }
    // When the order changes in Settings, stay on the same page.
    LaunchedEffect(pages) {
        val i = pages.indexOfFirst { it.id == settledId }
        if (i >= 0 && i != pager.currentPage) {
            steering = true
            pager.scrollToPage(i)
        }
    }
    BackHandler(enabled = history.isNotEmpty() || current.section != Section.HOME) {
        goingBack = true
        if (history.isNotEmpty()) {
            val back = history.last()
            history = history.dropLast(1)
            jump(pages.indexOfFirst { it.id == back })
        } else {
            go(Section.HOME)
        }
    }

    // A page asked for from outside (a notification, Settings).
    LaunchedEffect(target, pages) {
        val t = target ?: return@LaunchedEffect
        shellPageFor(t)?.let { (section, inner) -> go(section, inner) }
        onTargetHandled()
    }

    // Email's done messages, with Undo, at the bottom of the screen.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(mail) {
        mail.done.collect { d ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val r = snackbar.showSnackbar(d.message, actionLabel = if (d.undo != null) "Undo" else null, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) d.undo?.invoke()
            }
        }
    }

    val accounts by mail.accounts.collectAsStateWithLifecycle()
    val log by mail.log.collectAsStateWithLifecycle()
    val pending by app.pending.collectAsStateWithLifecycle()
    val needsYou = remember(log) { log.count { MailActions.attention(it) != null } }
    val menuPull = rememberMenuPull(pager, links.openMenu)

    val homeActions = remember(pages) {
        HomeActions(
            openEmail = { go(Section.EMAIL) },
            openEmailItem = { e, ids ->
                EmailBrowse.ids = ids
                links.openEmail(e.id)
            },
            openUnread = {
                mail.showUnread()
                go(Section.EMAIL)
            },
            openEmailSetup = links.emailSetup,
            openMoney = { go(Section.MONEY) },
            openPayments = { go(Section.MONEY, 1) },
            openTravel = { kind: Motion? ->
                travel.showToday(kind)
                go(Section.TRAVEL)
            },
            openTravelSetup = { go(Section.TRAVEL, 1) },
            openLimits = { links.limits(0) },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { ShellTopBar(current, app, links, onGoals = { app.editGoals(true) }) },
        bottomBar = {
            BottomBar(
                sections = pages.map { it.section }.distinct(),
                selected = current.section,
                badges = mapOf(Section.EMAIL to needsYou, Section.MONEY to pending.size),
                // The page you were last on in that part; tapping the part you're in goes to its first page.
                onSelect = { s -> if (s == current.section) go(s) else go(s, lastInner[s] ?: 0) },
            )
        },
        floatingActionButton = {
            if (current.section == Section.EMAIL && accounts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text("Write", style = MaterialTheme.typography.labelLarge) },
                    icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = links.write,
                    containerColor = LocalBrand.current.action,
                    contentColor = LocalBrand.current.onAction,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            // The names of the pages in this part; they stay put while the pages slide.
            if (current.section.pages.size > 1) {
                SectionTabs(current.section.pages, current.inner) { go(current.section, it) }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(menuPull),
                beyondViewportPageCount = 1,
                key = { pages[it].id },
            ) { index ->
                val page = shown(index)
                // A page you leave starts at the top when you come back to it.
                val list = rememberLazyListState()
                val here = pager.settledPage == index
                LaunchedEffect(here) { if (!here) list.scrollToItem(0) }
                when (page.section) {
                    Section.HOME -> HomePage(order, app, mail, travel, limits, list, homeActions)
                    Section.EMAIL -> EmailPage(page.inner, list, links.openEmail, links.emailSetup, mail)
                    Section.MONEY -> when (page.inner) {
                        0 -> MoneyThisMonthPage(app, list, onOpenPending = { go(Section.MONEY, 1) }, onOpenInsights = { go(Section.MONEY, 2) })
                        1 -> PaymentsScreen(app)
                        2 -> InsightsScreen(app, onOpenRecap = links.recap)
                        3 -> MonthScreen(app)
                        4 -> CategoriesScreen(app)
                        else -> MoneyInScreen(app)
                    }
                    Section.TRAVEL -> TravelPage(page.inner, travel, list, onOpenPayments = { go(Section.MONEY, 1) }, onOpenSetup = { go(Section.TRAVEL, 1) })
                    Section.MORE -> MorePage(limits, vault, list, onLimits = { links.limits(0) }, onPasswords = links.passwords, onNews = links.news)
                }
            }
        }
    }
}

@Composable
private fun ShellTopBar(page: ShellPage, app: AppViewModel, links: ShellLinks, onGoals: () -> Unit) {
    val username by app.username.collectAsStateWithLifecycle()
    val avatarVersion by app.avatarVersion.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .height(if (page.section == Section.HOME) 72.dp else 60.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = links.openMenu) { Icon(Icons.Rounded.Menu, contentDescription = "Open menu") }
        if (page.section == Section.HOME) {
            val now = LocalDateTime.now()
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                val name = username.trim().substringBefore(' ')
                Text(
                    if (name.isBlank()) "Good ${partOfDay(now.hour).lowercase()}" else "${partOfDay(now.hour)}, $name",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.padding(end = 12.dp).clip(CircleShape).clickable(onClick = links.account)) {
                Avatar(app.avatarFile, avatarVersion, username, size = 40.dp)
            }
        } else {
            Text(
                page.section.label,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            when {
                page.section == Section.EMAIL -> TextButton(onClick = links.emailSetup) { Text("Setup", color = brand.action) }
                page.section == Section.MONEY && page.inner == 2 -> TextButton(onClick = onGoals, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Rounded.Flag, contentDescription = null, tint = brand.action)
                    Spacer(Modifier.width(6.dp))
                    Text("Goals", style = MaterialTheme.typography.labelLarge, color = brand.action)
                }
            }
        }
    }
}

/** The page names of a part: side by side when they fit, sliding sideways when there are more. */
@Composable
private fun SectionTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (labels.size <= 3) {
        Tabs(labels, selected, onSelect)
        return
    }
    val brand = LocalBrand.current
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) brand.onAction else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) brand.action else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
    // Keep the current page's name in view.
    LaunchedEffect(selected, labels.size) {
        val fraction = selected.toFloat() / (labels.size - 1).coerceAtLeast(1)
        scroll.animateScrollTo((scroll.maxValue * fraction).toInt())
    }
}

@Composable
private fun BottomBar(sections: List<Section>, selected: Section, badges: Map<Section, Int>, onSelect: (Section) -> Unit) {
    val brand = LocalBrand.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        sections.forEach { s ->
            val count = badges[s] ?: 0
            NavigationBarItem(
                selected = s == selected,
                onClick = { onSelect(s) },
                icon = {
                    BadgedBox(badge = {
                        if (count > 0) Badge(containerColor = if (s == Section.MONEY) brand.action else brand.fine, contentColor = MaterialTheme.colorScheme.background) {
                            Text(count.toString())
                        }
                    }) { Icon(s.icon, contentDescription = null) }
                },
                label = { Text(s.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = brand.onAction,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = brand.action,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
