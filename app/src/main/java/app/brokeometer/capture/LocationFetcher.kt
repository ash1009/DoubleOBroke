package app.brokeometer.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** The phone's position when a card payment comes in, if the owner has allowed it. */
class LocationFetcher(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    fun hasForeground(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Alerts arrive while the app is closed, so Android needs "Allow all the time". */
    fun hasBackground(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun current(): Location? {
        if (!hasForeground()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val known = try {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
        } catch (e: SecurityException) {
            emptyList()
        }
        val fresh = known.filter { now - it.time < FRESH_MS }.minByOrNull { it.accuracy }
        if (fresh != null && fresh.accuracy <= GOOD_ENOUGH_M) return fresh

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                    LocationManager.FUSED_PROVIDER
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                val live = withTimeoutOrNull(WAIT_MS) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        try {
                            manager.getCurrentLocation(provider, signal, executor) { location -> cont.resume(location) }
                        } catch (e: SecurityException) {
                            cont.resume(null)
                        }
                    }
                }
                if (live != null) return live
            }
        }
        return fresh ?: known.filter { now - it.time < STALE_MS }.minByOrNull { it.accuracy }
    }

    private companion object {
        const val FRESH_MS = 2 * 60 * 1000L
        const val STALE_MS = 15 * 60 * 1000L
        const val WAIT_MS = 20_000L
        const val GOOD_ENOUGH_M = 100f
    }
}
