package app.brokeometer.limits

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokeometer.BrokeApp
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.theme.BrokeTheme
import app.brokeometer.ui.theme.LocalBrand
import kotlinx.coroutines.delay

/**
 * The block screen, over an app whose time is used up for today, or that is in blocked hours. For a
 * used-up limit it can offer a way out (wait, and the wait doubles each time, for 10 more minutes).
 * Leaving the screen during a wait starts it again. Blocked hours have no way out.
 */
class BlockActivity : ComponentActivity() {

    /** Whether a way-out wait is running; leaving the screen ends it. */
    private var waitingUntil = mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BrokeApp).container
        setContent {
            val theme by container.prefs.theme.collectAsStateWithLifecycle()
            BrokeTheme(theme) {
                BlockScreen(intent?.getStringExtra(EXTRA_PKG).orEmpty(), intent?.getStringExtra(EXTRA_REASON) ?: REASON_LIMIT)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        waitingUntil.longValue = 0L
        recreate()
    }

    override fun onResume() {
        super.onResume()
        showing = true
    }

    override fun onPause() {
        super.onPause()
        showing = false
    }

    override fun onStop() {
        super.onStop()
        // Leaving starts the wait again.
        waitingUntil.longValue = 0L
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun home() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun openApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    @Composable
    private fun BlockScreen(pkg: String, reason: String) {
        val container = (application as BrokeApp).container
        val store = container.limits
        val limits by store.limits.collectAsStateWithLifecycle()
        val day by store.day.collectAsStateWithLifecycle()
        val limit = limits.firstOrNull { it.pkg == pkg }
        val hours by store.hours.collectAsStateWithLifecycle()
        val label = limit?.label ?: hours.apps.firstOrNull { it.pkg == pkg }?.label ?: container.usage.label(pkg)
        val icon = remember(pkg) { container.usage.icon(pkg)?.asImageBitmap() }
        val brand = LocalBrand.current
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val used = day.wayOuts[pkg] ?: 0
        val waitMinutes = LimitRules.wayOutWait(used)
        var until by waitingUntil
        var left by remember { mutableLongStateOf(0L) }

        LaunchedEffect(until) {
            if (until == 0L) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                return@LaunchedEffect
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            while (true) {
                left = until - System.currentTimeMillis()
                if (left <= 0) {
                    until = 0L
                    store.addWayOut(pkg)
                    openApp(pkg)
                    break
                }
                delay(250)
            }
        }
        BackHandler { if (until != 0L) until = 0L else home() }

        // A Surface, so text takes the theme's colour on the dark background.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
                } else {
                    Icon(Icons.Rounded.HourglassBottom, contentDescription = null, tint = brand.action, modifier = Modifier.size(64.dp))
                }
                Spacer(Modifier.height(24.dp))
                if (until != 0L) {
                    val secs = ((left + 999) / 1000).coerceAtLeast(0)
                    Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.displayMedium, color = brand.action)
                    Spacer(Modifier.height(12.dp))
                    Text("Then 10 more minutes of $label", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Stay on this screen. Leaving it starts the wait again.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = muted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(32.dp))
                    QuietButton("Never mind", onClick = { until = 0L }, modifier = Modifier.fillMaxWidth())
                    return@Column
                }
                val (title, body) = when (reason) {
                    REASON_CLOCK -> "The phone's clock was changed" to
                        "Turn Automatic date and time back on to use $label. App limits goes by the real time."
                    REASON_HOURS -> "$label is closed right now" to
                        "Blocked hours: ${HoursRules.clock(hours.start)} to ${HoursRules.clock(hours.end)}. It opens again at ${HoursRules.clock(hours.end)}."
                    else -> "$label is done for today" to
                        "You've had your ${LimitRules.hmMinutes((limit?.minutes ?: 0) + (day.extra[pkg] ?: 0))}. It opens again at midnight."
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(body, style = MaterialTheme.typography.bodyLarge, color = muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                if (reason == REASON_CLOCK) {
                    ActionButton("Open date and time", onClick = {
                        runCatching { startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        finish()
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                } else if (reason == REASON_LIMIT) {
                    // Blocked hours have no way out.
                    if (limit?.wayOut == true) {
                        QuietButton(
                            "Wait $waitMinutes min for 10 more minutes",
                            onClick = { until = System.currentTimeMillis() + store.waitMs(waitMinutes) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                QuietButton("Close", onClick = { home() }, modifier = Modifier.fillMaxWidth(), color = muted)
            }
        }
    }

    companion object {
        /** Whether the block screen is up, so the watcher knows it came up. */
        @Volatile var showing = false

        const val EXTRA_PKG = "pkg"
        const val EXTRA_REASON = "reason"
        const val REASON_LIMIT = "limit"
        const val REASON_CLOCK = "clock"
        const val REASON_HOURS = "hours"
    }
}
