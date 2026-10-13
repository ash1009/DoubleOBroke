package app.brokeometer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.theme.LocalBrand

data class SetupStatus(val readsBankAlerts: Boolean, val canNotify: Boolean) {
    val complete: Boolean get() = readsBankAlerts && canNotify
}

fun readSetupStatus(context: Context): SetupStatus = SetupStatus(
    readsBankAlerts = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
    canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled(),
)

/** The two permissions the app needs, checked again every time the app comes to the front. */
@Composable
fun rememberSetupStatus(): SetupStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readSetupStatus(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = readSetupStatus(context) }
    return status
}

fun openNotificationAccess(context: Context) {
    context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

fun openAppInfo(context: Context) {
    context.startActivitySafely(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )
}

fun openAppNotificationSettings(context: Context) {
    context.startActivitySafely(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
}

private fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: Exception) {
        }
    }
}

/** Shown on the home screen until both permissions are on. */
@Composable
fun SetupPanel(status: SetupStatus, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var askedOnce by remember { mutableStateOf(false) }
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) openAppNotificationSettings(context)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Two switches to turn on", style = MaterialTheme.typography.headlineSmall)

            SetupStep(
                done = status.readsBankAlerts,
                title = "Let the app read HSBC payment alerts",
                body = "In the list that opens, turn on Double O Broke. Only HSBC alerts are read.",
            )
            if (!status.readsBankAlerts) {
                ActionButton(
                    text = "Open notification access",
                    onClick = { openNotificationAccess(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "If Android says the setting is restricted: open App info, tap the three dots " +
                        "at the top right, choose Allow restricted settings, then come back here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { openAppInfo(context) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                ) { Text("Open App info", style = MaterialTheme.typography.labelLarge) }
            }

            SetupStep(
                done = status.canNotify,
                title = "Let the app send you its own alerts",
                body = "This is the alert with Accept and Update on it.",
            )
            if (!status.canNotify) {
                ActionButton(
                    text = "Allow alerts",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !askedOnce) {
                            askedOnce = true
                            askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openAppNotificationSettings(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SetupStep(done: Boolean, title: String, body: String) {
    val brand = LocalBrand.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done yet",
            tint = if (done) brand.fine else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!done) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
