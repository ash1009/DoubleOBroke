package app.brokeometer.ui

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.theme.LocalBrand

/** The fingerprint lock: a fingerprint, face or the phone's own PIN opens the app. */
object AppLock {

    private val authenticators: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    /** False when the phone has no fingerprint and no screen lock set up. */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, onUnlocked: () -> Unit, onFailed: () -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailed()
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Double O Broke")
            .setSubtitle("Use your fingerprint")
            .setAllowedAuthenticators(authenticators)
            .apply { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) setNegativeButtonText("Cancel") }
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}

/** Shown instead of the app until it is unlocked, so nothing behind it can be read. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val brand = LocalBrand.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = brand.action, modifier = Modifier.size(72.dp))
            Text("Double O Broke is locked", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Use your fingerprint to open it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            ActionButton("Unlock", onClick = onUnlock)
        }
    }
}
