package app.brokeometer.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.brokeometer.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.brokeometer.vault.VaultImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.brokeometer.BrokeApp
import app.brokeometer.BuildConfig
import app.brokeometer.ui.theme.BrokeTheme
import app.brokeometer.ui.theme.isDarkTheme

class MainActivity : FragmentActivity() {

    private val vm: AppViewModel by viewModels()

    /** A screen asked for from outside, for example by tapping a payment alert. */
    private var requestedRoute by mutableStateOf<String?>(null)

    /** True while the fingerprint lock covers the app. */
    private var locked by mutableStateOf(false)
    private var prompting = false
    private var askedThisVisit = false
    private var leftAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        addVoiceShortcut()
        locked = lockWanted()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && lockWanted()) {
            // The app switcher shows a blank card instead of your spending.
            setRecentsScreenshotEnabled(false)
        }

        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val dark = isDarkTheme(theme)

            // Status and navigation bar icons follow the theme the app is showing.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            BrokeTheme(theme) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    if (locked) {
                        LockScreen(onUnlock = { askToUnlock() })
                    } else {
                        AppRoot(vm = vm, requestedRoute = requestedRoute, onRouteHandled = { requestedRoute = null })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as BrokeApp).container.vault.appInForeground()
        // The emails you're likely to open next are fetched in the background, so they open straight away.
        (application as BrokeApp).container.mailReader.prefetchRecent()
        // A quick email check, so the list shows what Gmail and Yahoo Mail show (read or unread there).
        (application as BrokeApp).container.inboxAgent.onAppOpened()
        if (!locked && leftAt > 0 && System.currentTimeMillis() - leftAt > RELOCK_AFTER_MS && lockWanted()) locked = true
        leftAt = 0
        // Ask once each time the app comes to the front; after a cancel, the Unlock button asks again.
        if (locked && !askedThisVisit) {
            askedThisVisit = true
            askToUnlock()
        }
    }

    override fun onStop() {
        super.onStop()
        (application as BrokeApp).container.vault.appInBackground()
        // Typing the phone's PIN opens another screen; that is not leaving the app.
        if (!isChangingConfigurations && !prompting) {
            leftAt = System.currentTimeMillis()
            askedThisVisit = false
        }
    }

    private fun lockWanted(): Boolean {
        val app = application as BrokeApp
        return app.container.prefs.appLock.value && AppLock.available(this)
    }

    private fun askToUnlock() {
        if (prompting) return
        if (!lockWanted()) {
            locked = false
            return
        }
        prompting = true
        AppLock.prompt(
            this,
            onUnlocked = {
                prompting = false
                locked = false
            },
            onFailed = { prompting = false },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_ROUTE)?.let { requestedRoute = it }
        if (intent.action == ACTION_VOICE) {
            requestedRoute = Routes.PENDING
            vm.askForVoice()
        }
        // A file opened or shared from another app, such as the Claude app: show what it would import.
        importUri(intent)?.let { uri ->
            // A password export (shared from WhatsApp or Drive, say) goes to Passwords; anything else is a
            // spending file or a backup.
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                val passwords = bytes != null && bytes.size < 20_000_000 &&
                    runCatching { VaultImport.fromFile(bytes).columns.any { it.equals("password", ignoreCase = true) } }.getOrDefault(false)
                if (passwords) {
                    (application as BrokeApp).container.vault.offerFile(bytes!!)
                    requestedRoute = Routes.PASSWORDS
                } else {
                    vm.readImport(uri)
                    requestedRoute = Routes.SETTINGS
                }
            }
        }
        if (BuildConfig.DEBUG) DebugHooks.apply(intent, application as BrokeApp, vm)
    }

    /** Long-pressing the app icon offers "Add by voice". */
    private fun addVoiceShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, "voice")
            .setShortLabel("Add by voice")
            .setLongLabel("Add a payment by voice")
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_shortcut_voice))
            .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_VOICE))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(this, shortcut) }
    }

    private fun importUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ACTION_VOICE = "app.brokeometer.action.ADD_BY_VOICE"
        const val ROUTE_PENDING = Routes.PENDING
        const val ROUTE_INSIGHTS = Routes.INSIGHTS
        const val ROUTE_EMAIL = Routes.EMAIL
        /** Coming back within half a minute (a file picker, a quick switch) does not ask again. */
        private const val RELOCK_AFTER_MS = 30_000L
    }
}
