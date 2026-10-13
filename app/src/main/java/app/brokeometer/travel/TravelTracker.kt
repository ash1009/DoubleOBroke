package app.brokeometer.travel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.brokeometer.BrokeApp
import app.brokeometer.R
import app.brokeometer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Records where the phone is through the day, for the nightly trip check. Runs as a foreground
 * service with a small notification, which is what keeps Android from stopping it.
 *
 * To save battery it asks for a precise position every 15 seconds only while you are moving; once
 * you have stayed within about 60 metres for 5 minutes it drops to one rough position every 2
 * minutes, and speeds up again as soon as you move off.
 */
class TravelTracker : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()
    private var manager: LocationManager? = null
    private var fast: Boolean? = null

    /** Where you were when you last moved, and when; staying near it for a while means you've stopped. */
    private var anchor: Location? = null
    private var anchorAt = 0L

    private val listener = LocationListener { location -> onFix(location) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle("Noting your trips")
            .setContentText("For tonight's tube and bus check. Where you go stays on this phone.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 41,
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "travel"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // Location was taken away, or Android won't start it from here: nothing to track.
            stopSelf()
            return
        }
        manager = getSystemService(LocationManager::class.java)
        request(fastMode = true)
        startSteps()
    }

    // Started again when the switch is touched or step counting is allowed, so it picks that up.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSteps()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { sensors?.unregisterListener(steps) }
        // The last reading is kept on the app's own scope, as this service's is about to stop.
        latestSteps?.let { total ->
            val app = application as BrokeApp
            app.scope.launch { runCatching { app.container.travelDatabase.dao().insertStepSample(StepSampleEntity(at = System.currentTimeMillis(), total = total)) } }
        }
        runCatching { manager?.removeUpdates(listener) }
        scope.cancel()
        executor.shutdown()
        super.onDestroy()
    }

    // Steps: the phone's own step counter, read in batches so it costs almost no battery.
    private var sensors: SensorManager? = null
    private var counting = false
    private var savedAt = 0L
    private var latestSteps: Long? = null

    private val steps = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val total = event.values.firstOrNull()?.toLong() ?: return
            latestSteps = total
            val now = System.currentTimeMillis()
            // One reading a minute is plenty.
            if (now - savedAt >= STEP_SAVE_MS) saveSteps(total, now)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun saveSteps(total: Long, at: Long) {
        savedAt = at
        val dao = (application as BrokeApp).container.travelDatabase.dao()
        scope.launch { runCatching { dao.insertStepSample(StepSampleEntity(at = at, total = total)) } }
    }

    private fun startSteps() {
        if (counting || !stepsAllowed(this)) return
        val m = getSystemService(SensorManager::class.java) ?: return
        val sensor = m.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        sensors = m
        counting = m.registerListener(steps, sensor, SensorManager.SENSOR_DELAY_NORMAL, STEP_BATCH_US)
    }

    private fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun request(fastMode: Boolean) {
        if (fast == fastMode) return
        val m = manager ?: return
        if (!allowed()) {
            stopSelf()
            return
        }
        fast = fastMode
        runCatching { m.removeUpdates(listener) }
        val interval = if (fastMode) FAST_MS else SLOW_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && m.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                val req = LocationRequest.Builder(interval)
                    .setQuality(if (fastMode) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateIntervalMillis(interval / 2)
                    .build()
                m.requestLocationUpdates(LocationManager.FUSED_PROVIDER, req, executor, listener)
            } else {
                if (fastMode && m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    m.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f, listener, Looper.getMainLooper())
                }
                if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, interval, 0f, listener, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun onFix(location: Location) {
        val now = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val point = TrackPointEntity(
            at = now,
            lat = location.latitude,
            lon = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else 999f,
            speed = if (location.hasSpeed()) location.speed else null,
        )
        val dao = (application as BrokeApp).container.travelDatabase.dao()
        scope.launch { runCatching { dao.insertPoint(point) } }

        // Moving or staying put decides how often to ask.
        val a = anchor
        if (a == null || (location.accuracy < 100f && a.distanceTo(location) > MOVED_M)) {
            anchor = location
            anchorAt = now
            request(fastMode = true)
        } else if (now - anchorAt > STILL_MS) {
            request(fastMode = false)
        }
    }

    companion object {
        private const val CHANNEL = "travel_tracking"
        const val CHANNEL_TRIPS = "travel_trips"
        private const val NOTIFICATION_ID = 4101
        private val FAST_MS = TimeUnit.SECONDS.toMillis(15)
        private val SLOW_MS = TimeUnit.MINUTES.toMillis(2)
        private val STILL_MS = TimeUnit.MINUTES.toMillis(5)
        private const val MOVED_M = 60f

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Noting trips", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Shows while the app notes where you go, for the nightly tube and bus check." }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_TRIPS, "Tube and bus trips", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Each night: the trips found that day, to check." }
            )
        }

        private val STEP_SAVE_MS = TimeUnit.MINUTES.toMillis(1)
        private val STEP_BATCH_US = TimeUnit.MINUTES.toMicros(1).toInt()

        /** Step counting needs "Physical activity" allowed (from Android 10). */
        fun stepsAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        /** Whether this phone has a step counter at all. */
        fun hasStepCounter(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

        /** Starts or stops noting trips, as the switch on the Travel page says. */
        fun sync(context: Context) {
            val on = (context.applicationContext as BrokeApp).container.prefs.trackTrips.value
            val intent = Intent(context, TravelTracker::class.java)
            if (on) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}

/** After the phone restarts, or the app is updated, noting trips carries on if it was on. */
class TravelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            TravelTracker.sync(context)
            TravelCheckWorker.schedule(context)
        }
    }
}
