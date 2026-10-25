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
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Lock
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
import app.brokeometer.data.Layout
import app.brokeometer.vault.VaultState
import app.brokeometer.mail.MailActions
import app.brokeometer.ui.screens.HoldScreen
import app.brokeometer.ui.screens.LimitsShellPage
import app.brokeometer.ui.screens.NewsBody
import app.brokeometer.ui.screens.NewsViewModel
import app.brokeometer.ui.screens.NotesBody
import app.brokeometer.ui.screens.PasswordsScreen
import app.brokeometer.travel.Motion
import app.brokeometer.ui.components.Avatar
import app.brokeometer.ui.components.BrokeTopBar
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
import app.brokeometer.ui.screens.EMAIL_SETUP_PAGE
import app.brokeometer.ui.screens.MoneySetupPage
import app.brokeometer.ui.screens.emailPageNames
import app.brokeometer.ui.screens.moneyPageNames
import app.brokeometer.ui.screens.newsPageNames
import app.brokeometer.ui.screens.notesPageNames
import app.brokeometer.ui.screens.passwordsPageNames
import app.brokeometer.ui.screens.taskPageNames
import app.brokeometer.ui.screens.TasksBody
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
    MONEY("money", "Money", moneyPageNames, Icons.Rounded.Speed),
    TRAVEL("travel", "Travel", listOf("Activity", "Setup"), Icons.Rounded.DirectionsSubway),
    NEWS("news", "News", newsPageNames, Icons.AutoMirrored.Rounded.Article),
    LIMITS("limits", "App limits", listOf("Today", "Limits", "Setup"), Icons.Rounded.HourglassBottom),
    NOTES("notes", "Notes", notesPageNames, Icons.Rounded.Lightbulb),
    PASSWORDS("passwords", "Passwords", passwordsPageNames, Icons.Rounded.Key),
    TASKS("tasks", "Tasks", taskPageNames, Icons.Rounded.TaskAlt),
    /** Every feature without a place in the bar. */
    MORE("more", "More", listOf("More"), Icons.Rounded.MoreHoriz),
}

/** One page in the swipe line: a part, and which of its pages. */
data class ShellPage(val section: Section, val inner: Int) {
    val id: String get() = "${section.key}/$inner"
}

/**
 * Home first, then each feature with a place in the bar, in the order chosen in Settings, each with
 * its pages one after another, and More last.
 */
fun shellPages(bar: List<String>): List<ShellPage> {
    val inBar = bar.mapNotNull { key -> Section.entries.firstOrNull { it.key == key && it != Section.HOME && it != Section.MORE } }
    val sections = listOf(Section.HOME) + inBar + Section.MORE
    return sections.flatMap { s -> s.pages.indices.map { ShellPage(s, it) } }
}

/** Which page a route from elsewhere (a notification, an old link) means. */
fun shellPageFor(route: String): Pair<Section, Int>? = when (route) {
    Routes.HOME -> Section.HOME to 0
    Routes.EMAIL -> Section.EMAIL to 0
    Routes.EMAIL_SETUP -> Section.EMAIL to EMAIL_SETUP_PAGE
    Routes.MONEY -> Section.MONEY to 0
    Routes.PENDING -> Section.MONEY to 1
    Routes.INSIGHTS -> Section.MONEY to 2
    Routes.MONTHS -> Section.MONEY to 3
    Routes.CATEGORIES -> Section.MONEY to 4
    Routes.MONEY_IN -> Section.MONEY to 5
    Routes.TRAVEL -> Section.TRAVEL to 0
    Routes.MORE -> Section.MORE to 0
    Routes.NEWS -> Section.NEWS to 0
    Routes.LIMITS -> Section.LIMITS to 0
    Routes.NOTES -> Section.NOTES to 0
    Routes.PASSWORDS -> Section.PASSWORDS to 0
    Routes.TASKS -> Section.TASKS to 0
    else -> null
}

/** Ways out of the main screen, to pages of their own. */
class ShellLinks(
    val openMenu: () -> Unit,
    val openEmail: (Long) -> Unit,
    val write: () -> Unit,
    val account: () -> Unit,
    val recap: (Int) -> Unit,
    val passwords: () -> Unit,
    val limits: (Int) -> Unit,
    val news: () -> Unit = {},
    val notes: () -> Unit = {},
    /** A note by its id, to read or change. */
    val openNote: (Long) -> Unit = {},
    /** A new note, or a new list. */
    val newNote: (checklist: Boolean) -> Unit = {},
    /** A part with no place in the bar, on a page of its own, at one of its pages. */
    val openSection: (String, Int) -> Unit = { _, _ -> },
)

/** The view models the pages share. */
internal class ShellModels(
    val app: AppViewModel,
    val mail: MailViewModel,
    val travel: TravelViewModel,
    val vault: VaultViewModel,
    val limits: LimitsViewModel,
    val news: NewsViewModel,
    val notes: NotesViewModel,
    val tasks: TasksViewModel,
)

/**
 * One page of a part. [go] moves to another page (of this part or another). Home and More are only
 * ever shown by the main screen, which passes them in.
 */
@Composable
internal fun ShellPageContent(
    page: ShellPage,
    list: androidx.compose.foundation.lazy.LazyListState,
    m: ShellModels,
    links: ShellLinks,
    go: (Section, Int) -> Unit,
    home: @Composable () -> Unit = {},
    more: @Composable () -> Unit = {},
) {
    when (page.section) {
        Section.HOME -> home()
        Section.EMAIL -> EmailPage(page.inner, list, links.openEmail, onOpenSetup = { go(Section.EMAIL, EMAIL_SETUP_PAGE) }, vm = m.mail)
        Section.MONEY -> when (page.inner) {
            0 -> MoneyThisMonthPage(m.app, list, onOpenPending = { go(Section.MONEY, 1) }, onOpenInsights = { go(Section.MONEY, 2) })
            1 -> PaymentsScreen(m.app)
            2 -> InsightsScreen(m.app, onOpenRecap = links.recap)
            3 -> MonthScreen(m.app)
            4 -> CategoriesScreen(m.app)
            5 -> MoneyInScreen(m.app)
            else -> MoneySetupPage(m.app, list)
        }
        Section.TRAVEL -> TravelPage(page.inner, m.travel, list, onOpenPayments = { go(Section.MONEY, 1) }, onOpenSetup = { go(Section.TRAVEL, 1) })
        Section.NEWS -> NewsBody(m.news, page.inner, list, onSetup = { go(Section.NEWS, newsPageNames.lastIndex) })
        Section.LIMITS -> LimitsShellPage(m.limits, page.inner) { go(Section.LIMITS, it) }
        Section.NOTES -> NotesBody(m.notes, page.inner, links.openNote, links.newNote)
        Section.PASSWORDS -> PasswordsScreen(m.vault, page.inner)
        Section.TASKS -> TasksBody(m.tasks, page.inner, list, onOpenEmail = links.openEmail, onOpenNote = links.openNote)
        Section.MORE -> more()
    }
}

/**
 * The main screen: a top bar, the names of the pages in the current part, the pages themselves in one
 * line (swipe left or right through them all: Home, then each agent's pages in turn), and a bar at the
 * bottom to jump to each part. Back goes to the page you were on before; on Home it leaves the app.
 */
@Composable
fun MainShell(app: AppViewModel, target: String?, onTargetHandled: () -> Unit, links: ShellLinks) {
    val cards by app.homeCards.collectAsStateWithLifecycle()
    val barOrder by app.barOrder.collectAsStateWithLifecycle()
    val barTicked by app.barTicked.collectAsStateWithLifecycle()
    val bar = remember(barOrder, barTicked) { Layout.inBar(barOrder, barTicked) }
    val moreOrder = remember(barOrder, barTicked) { Layout.underMore(barOrder, barTicked) }
    val pages = remember(bar) { shellPages(bar) }
    val latestPages by rememberUpdatedState(pages)
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val mail: MailViewModel = viewModel()
    val travel: TravelViewModel = viewModel()
    val vault: VaultViewModel = viewModel()
    val limits: LimitsViewModel = viewModel()
    val news: NewsViewModel = viewModel()
    val notesVm: NotesViewModel = viewModel()
    val tasksVm: TasksViewModel = viewModel()
    val models = ShellModels(app, mail, travel, vault, limits, news, notesVm, tasksVm)

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

    fun go(section: Section, inner: Int = 0) {
        val i = pages.indexOfFirst { it.section == section && it.inner == inner }
        // A part with no place in the bar opens on its own instead.
        if (i < 0) openOutside(section, inner, links) else jump(i)
    }

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
            openEmailSetup = { go(Section.EMAIL, EMAIL_SETUP_PAGE) },
            openMoney = { go(Section.MONEY) },
            openPayments = { go(Section.MONEY, 1) },
            openTravel = { kind: Motion? ->
                travel.showToday(kind)
                go(Section.TRAVEL)
            },
            openTravelSetup = { go(Section.TRAVEL, 1) },
            openLimits = { go(Section.LIMITS) },
            openNews = { go(Section.NEWS) },
            openNotes = { go(Section.NOTES) },
            openNote = links.openNote,
            newNote = { links.newNote(false) },
            openPasswords = { go(Section.PASSWORDS) },
            openTasks = { go(Section.TASKS) },
        )
    }

    // Loosening App limits waits on a screen of its own, over everything, until it's done or dropped.
    val hold by limits.hold.collectAsStateWithLifecycle()
    hold?.let { h ->
        HoldScreen(limits, h)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { ShellTopBar(current, app, links) },
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
            if (current.section == Section.EMAIL && current.inner != EMAIL_SETUP_PAGE && accounts.isNotEmpty()) {
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
                ShellPageContent(
                    page, list, models, links,
                    go = { s, inner -> go(s, inner) },
                    home = { HomePage(cards, app, mail, travel, limits, news, notesVm, vault, tasksVm, list, homeActions) },
                    more = { MorePage(limits, vault, list, order = moreOrder, onOpen = { key -> openMore(key, links) }) },
                )
            }
        }
    }
}

/**
 * Email, Money or Travel on a page of their own, when they have no place in the bottom bar (opened
 * from More, Home or a notification): the part's pages side by side, with Back to where you were.
 */
@Composable
fun SectionScreen(key: String, startPage: Int, app: AppViewModel, links: ShellLinks, onBack: () -> Unit) {
    val section = Section.entries.firstOrNull { it.key == key && it != Section.HOME && it != Section.MORE }
    if (section == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val mail: MailViewModel = viewModel()
    val models = ShellModels(app, mail, viewModel(), viewModel(), viewModel(), viewModel(), viewModel(), viewModel())
    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, section.pages.size - 1)) { section.pages.size }
    val scope = rememberCoroutineScope()
    val brand = LocalBrand.current
    // Loosening App limits waits on a screen of its own, over everything.
    val hold by models.limits.hold.collectAsStateWithLifecycle()
    val accounts by mail.accounts.collectAsStateWithLifecycle()
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
    hold?.let { h ->
        HoldScreen(models.limits, h)
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { BrokeTopBar(section.label, onBack = onBack) },
        floatingActionButton = {
            if (section == Section.EMAIL && pager.currentPage != EMAIL_SETUP_PAGE && accounts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text("Write", style = MaterialTheme.typography.labelLarge) },
                    icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = links.write,
                    containerColor = brand.action,
                    contentColor = brand.onAction,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (section.pages.size > 1) {
                SectionTabs(section.pages, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f), beyondViewportPageCount = 1) { i ->
                val list = rememberLazyListState()
                ShellPageContent(
                    ShellPage(section, i), list, models, links,
                    go = { s, inner ->
                        if (s == section) scope.launch { pager.animateScrollToPage(inner) } else openOutside(s, inner, links)
                    },
                )
            }
        }
    }
}

/** A part that has no place in the bar opens on a page of its own, laid out the same way. */
private fun openOutside(section: Section, inner: Int, links: ShellLinks) = when (section) {
    Section.EMAIL, Section.MONEY, Section.TRAVEL, Section.TASKS -> links.openSection(section.key, inner)
    Section.LIMITS -> links.limits(inner)
    Section.NEWS -> if (inner == 0) links.news() else links.openSection(section.key, inner)
    // Notes opens on its own route, so a note's page shares its notes (for Undo).
    Section.NOTES -> links.notes()
    Section.PASSWORDS -> if (inner == 0) links.passwords() else links.openSection(section.key, inner)
    Section.HOME, Section.MORE -> Unit
}

/** A feature tapped on More (which only lists ones with no place in the bar). */
private fun openMore(key: String, links: ShellLinks) {
    val section = Section.entries.firstOrNull { it.key == key } ?: return
    openOutside(section, 0, links)
}

@Composable
private fun ShellTopBar(page: ShellPage, app: AppViewModel, links: ShellLinks) {
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
            // Only the part's name: everything else is on its pages, Setup last.
            Text(
                page.section.label,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
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
