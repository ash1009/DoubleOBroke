package app.brokeometer.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.BiasAlignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.brokeometer.ui.screens.NoteEditorScreen
import app.brokeometer.ui.screens.ComposeScreen
import app.brokeometer.ui.screens.MessageScreen
import app.brokeometer.BuildConfig
import app.brokeometer.ui.components.Avatar
import app.brokeometer.ui.screens.AccountScreen
import app.brokeometer.ui.screens.EMAIL_SETUP_PAGE
import app.brokeometer.ui.screens.LimitsScreen
import app.brokeometer.ui.screens.RecapScreen
import app.brokeometer.ui.screens.LogScreen
import app.brokeometer.ui.screens.SettingsScreen
import app.brokeometer.ui.theme.LocalBrand
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val INSIGHTS = "insights"
    const val RECAP = "recap"
    const val PENDING = "pending"
    const val MONTHS = "months"
    const val CATEGORIES = "categories"
    const val SETTINGS = "settings"
    const val ACCOUNT = "account"
    const val LOG = "log"
    const val EMAIL = "email"
    const val PASSWORDS = "passwords"
    const val TRAVEL = "travel"
    const val MONEY = "money"
    const val EMAIL_SETUP = "email/setup"
    const val MORE = "more"
    const val LIMITS = "limits"
    const val MONEY_IN = "money/in"
    const val NEWS = "news"
    const val NOTES = "notes"

    val all = setOf(HOME, INSIGHTS, RECAP, PENDING, MONTHS, CATEGORIES, SETTINGS, ACCOUNT, LOG, EMAIL, PASSWORDS, TRAVEL, MONEY, EMAIL_SETUP, MORE, LIMITS, MONEY_IN, NEWS, NOTES)

    /** Pages of the main screen (Home and the agents), as opposed to pages of their own. */
    val main = setOf(HOME, INSIGHTS, PENDING, MONTHS, CATEGORIES, EMAIL, TRAVEL, MONEY, MORE, MONEY_IN)
}

private data class MenuEntry(val route: String, val label: String, val icon: ImageVector)

/** The menu keeps what's opened now and then; the agents are in the bar at the bottom. */
private val menu = listOf(
    MenuEntry(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
    MenuEntry(Routes.ACCOUNT, "Account", Icons.Rounded.Person),
)

/**
 * The whole app: a menu that slides in from the left edge (or opens from the top-left button)
 * and the screens behind it.
 * [requestedRoute] is a screen to jump to, for example when a payment alert is tapped.
 */
@Composable
fun AppRoot(vm: AppViewModel, requestedRoute: String?, onRouteHandled: () -> Unit) {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Routes.HOME

    val username by vm.username.collectAsStateWithLifecycle()
    val avatarVersion by vm.avatarVersion.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()


    // A page of the main screen asked for from elsewhere; the main screen moves to it.
    var shellTarget by remember { mutableStateOf<String?>(null) }
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    val barTicked by vm.barTicked.collectAsStateWithLifecycle()
    val open: (String) -> Unit = { route ->
        // Pages of the main screen, and features with a place in its bottom bar, open there.
        if (route in Routes.main || route in barTicked || (route == Routes.EMAIL_SETUP && Routes.EMAIL in barTicked)) {
            nav.popBackStack(Routes.HOME, inclusive = false)
            shellTarget = route
        } else {
            nav.goTo(route)
        }
    }
    val go: (String) -> Unit = { route ->
        scope.launch { drawer.close() }
        open(route)
    }

    /** Ways out of the main screen and the parts' own pages. */
    fun links(): ShellLinks = ShellLinks(
        openMenu = openMenu,
        openEmail = { id -> nav.navigate("email/view/$id") },
        write = { nav.navigate("email/write") },
        account = { nav.goTo(Routes.ACCOUNT) },
        recap = { year ->
            vm.showRecap(year)
            nav.navigate(Routes.RECAP)
        },
        passwords = { nav.goTo(Routes.PASSWORDS) },
        limits = { page -> nav.navigate("${Routes.LIMITS}?page=$page") { launchSingleTop = true } },
        news = { nav.goTo(Routes.NEWS) },
        notes = { nav.goTo(Routes.NOTES) },
        openNote = { id -> nav.navigate("notes/edit/$id") },
        newNote = { list -> nav.navigate("notes/edit/-1?list=$list") },
        openSection = { key, page -> nav.navigate("section/$key?page=$page") },
    )

    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) {
            if (requestedRoute in Routes.all) open(requestedRoute)
            onRouteHandled()
        }
    }

    // Back closes the menu first. Added again each time the menu opens, so it comes before the Back
    // handling of the screen underneath (going back a page on Email or Travel, say).
    key(drawer.isOpen) {
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = true,
        modifier = Modifier.pointerInput(Unit) {
            // A touch that starts at the left edge and travels right opens the menu.
            // It is read on the first pass, before lists and buttons see it. Once the touch is
            // clearly heading right, the rest of it belongs to the menu, so nothing else can
            // grab it half way and drag the menu back shut.
            val edge = 28.dp.toPx()
            val travel = 40.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (BuildConfig.DEBUG) Log.d("EdgeSwipe", "down x=${down.position.x} edge=$edge open=${drawer.isOpen}")
                if (down.position.x > edge || drawer.isOpen) return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                var dx = 0f
                var dy = 0f
                var ours = false
                var opened = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (BuildConfig.DEBUG) Log.d("EdgeSwipe", "lifted at dx=$dx ours=$ours opened=$opened")
                        if (ours) change.consume()
                        break
                    }
                    val moved = change.positionChange()
                    dx += moved.x
                    dy += moved.y
                    if (!ours) {
                        if (abs(dy) > slop && abs(dy) > abs(dx)) break
                        if (dx > slop && dx > abs(dy)) ours = true
                    }
                    if (ours) {
                        change.consume()
                        if (!opened && dx > travel) {
                            opened = true
                            if (BuildConfig.DEBUG) Log.d("EdgeSwipe", "opening menu at dx=$dx")
                            scope.launch { drawer.open() }
                        }
                    }
                }
            }
        },
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.width(304.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { go(Routes.ACCOUNT) }
                            .padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(vm.avatarFile, avatarVersion, username, size = 56.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                username.ifBlank { "Add your name" },
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                            )
                            Text(
                                "Double O Broke",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    menu.forEach { entry ->
                        val brand = LocalBrand.current
                        NavigationDrawerItem(
                            label = { Text(entry.label, style = MaterialTheme.typography.labelLarge) },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            selected = current == entry.route,
                            onClick = { go(entry.route) },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = brand.action,
                                selectedIconColor = brand.onAction,
                                selectedTextColor = brand.onAction,
                                selectedBadgeColor = brand.onAction,
                                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        },
    ) {
      Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable(Routes.HOME) {
                MainShell(
                    app = vm,
                    target = shellTarget,
                    onTargetHandled = { shellTarget = null },
                    links = links(),
                )
            }
            composable(Routes.RECAP) { RecapScreen(vm, onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpenLog = { nav.navigate(Routes.LOG) },
                    onOpenEmail = { open(Routes.EMAIL) },
                )
            }
            composable(Routes.ACCOUNT) { AccountScreen(vm, onBack = { nav.popBackStack() }) }
            composable(Routes.LOG) { LogScreen(vm, onBack = { nav.popBackStack() }) }
            // Parts with no place in the bottom bar, each on a page of its own, laid out like the rest.
            composable(Routes.EMAIL_SETUP) { SectionScreen("email", EMAIL_SETUP_PAGE, vm, links(), onBack = { nav.popBackStack() }) }
            composable(Routes.PASSWORDS) { SectionScreen("passwords", 0, vm, links(), onBack = { nav.popBackStack() }) }
            composable(Routes.NEWS) { SectionScreen("news", 0, vm, links(), onBack = { nav.popBackStack() }) }
            composable(
                "section/{key}?page={page}",
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType },
                    navArgument("page") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                SectionScreen(
                    key = entry.arguments?.getString("key").orEmpty(),
                    startPage = entry.arguments?.getInt("page") ?: 0,
                    app = vm,
                    links = links(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.NOTES) { SectionScreen("notes", 0, vm, links(), onBack = { nav.popBackStack() }) }
            composable(
                "notes/edit/{id}?list={list}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("list") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                // The same notes as the Notes page (its own, or the one in the main screen), so a note deleted
                // here can be undone there.
                val parent = remember(entry) {
                    runCatching { nav.getBackStackEntry(Routes.NOTES) }.getOrNull() ?: runCatching { nav.getBackStackEntry(Routes.HOME) }.getOrNull()
                }
                val notesVm: NotesViewModel = if (parent != null) viewModel(parent) else viewModel()
                NoteEditorScreen(
                    id = entry.arguments?.getLong("id") ?: -1L,
                    newChecklist = entry.arguments?.getBoolean("list") ?: false,
                    onBack = { nav.popBackStack() },
                    vm = notesVm,
                )
            }
            composable(
                "${Routes.LIMITS}?page={page}",
                arguments = listOf(navArgument("page") { type = NavType.IntType; defaultValue = 0 }),
            ) { entry ->
                LimitsScreen(startPage = entry.arguments?.getInt("page") ?: 0, onBack = { nav.popBackStack() })
            }
            composable(
                "email/view/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                MessageScreen(onBack = { nav.popBackStack() }, onReply = { id -> nav.navigate("email/write?reply=$id") })
            }
            composable(
                "email/write?reply={reply}",
                arguments = listOf(navArgument("reply") { type = NavType.LongType; defaultValue = -1L }),
            ) {
                ComposeScreen(onBack = { nav.popBackStack() })
            }
        }
        // On phones with gesture navigation, a swipe from the left edge normally means Back.
        // In this strip, where a thumb rests, it reaches the app and slides the menu in instead.
        // Android allows 200dp of this per edge. Elsewhere, start the swipe a little in from the edge.
        Box(
            Modifier
                .align(BiasAlignment(-1f, 0.35f))
                .width(28.dp)
                .height(200.dp)
                .systemGestureExclusion()
        )
      }
    }
}

/** Back goes to the page you came from, like a browser. "This month" starts the trail again. */
private fun NavHostController.goTo(route: String) {
    when {
        route == Routes.HOME -> popBackStack(Routes.HOME, inclusive = false)
        currentDestination?.route == route -> Unit
        else -> navigate(route) { launchSingleTop = true }
    }
}
