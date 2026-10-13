package app.brokeometer.ui

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brokeometer.BrokeApp
import app.brokeometer.limits.AppLimit
import app.brokeometer.limits.LimitRules
import app.brokeometer.limits.LimitAdmin
import app.brokeometer.limits.LimitLive
import app.brokeometer.limits.LimitStore
import app.brokeometer.limits.LimitWatcher
import app.brokeometer.limits.PhoneApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Today's screen time: per app, in total, and each app's usual day. */
data class ScreenTime(
    val allowed: Boolean,
    val perApp: Map<String, Long>,
    val total: Long,
    val typical: Map<String, Long>,
    val labels: Map<String, String>,
    /** Apps you can open from the home screen (not this one): the only ones worth a limit. */
    val openable: Set<String> = emptySet(),
)

/** What App limits has been given, and what it still needs. */
data class LimitPerms(
    val usage: Boolean = false,
    /** "Display over other apps", so the block screen can come up over an app. */
    val overlay: Boolean = false,
    val running: Boolean = false,
    val admin: Boolean = false,
    val battery: Boolean = false,
    val autoTime: Boolean = true,
)

/** Something that loosens App limits, held back by a wait (and, to loosen a limit, typing a promise). */
sealed interface Hold {
    val waitMinutes: Int
    val promise: Boolean
    val what: String

    data class Limit(val pkg: String, val label: String, val minutes: Int, val wayOut: Boolean, val remove: Boolean) : Hold {
        override val waitMinutes = LimitRules.LOOSEN_WAIT
        override val promise = true
        override val what = if (remove) "Taking $label off your limits" else "Loosening $label's limit"
    }

    data object TurnOff : Hold {
        override val waitMinutes = LimitRules.TURN_OFF_WAIT
        override val promise = false
        override val what = "Turning App limits off"
    }
}

class LimitsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BrokeApp
    private val store: LimitStore = app.container.limits
    private val usage = app.container.usage

    val limits = store.limits
    val on = store.on
    val day = store.day

    private val _time = MutableStateFlow<ScreenTime?>(null)
    val time: StateFlow<ScreenTime?> = _time

    private val _perms = MutableStateFlow(LimitPerms())
    val perms: StateFlow<LimitPerms> = _perms

    private val _apps = MutableStateFlow<List<PhoneApp>?>(null)
    /** The apps you can pick from, most used first; null while loading. */
    val apps: StateFlow<List<PhoneApp>?> = _apps

    private val _hold = MutableStateFlow<Hold?>(null)
    /** A loosening change waiting on its wait; the screen for it shows while this is set. */
    val hold: StateFlow<Hold?> = _hold

    private val _holdUntil = MutableStateFlow(0L)
    /** When the wait ends; 0 while it isn't running. */
    val holdUntil: StateFlow<Long> = _holdUntil

    private val _done = MutableStateFlow<String?>(null)
    /** A line to show once a change is saved. */
    val done: StateFlow<String?> = _done

    private var ticking: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        store.refresh()
        val ctx = getApplication<Application>()
        // Starts (or stops) watching, now that permissions may have been given.
        LimitWatcher.sync(ctx)
        _perms.value = LimitPerms(
            usage = usage.allowed(),
            overlay = LimitWatcher.overlayAllowed(ctx),
            running = LimitLive.running,
            admin = LimitWatcher.adminActive(ctx),
            battery = LimitWatcher.batteryFree(ctx),
            autoTime = LimitWatcher.autoTime(ctx),
        )
        viewModelScope.launch {
            _time.value = withContext(Dispatchers.IO) { screenTime() }
        }
    }

    /** Keeps today's numbers fresh while a page of App limits is open. */
    fun keepFresh(on: Boolean) {
        ticking?.cancel()
        if (!on) return
        ticking = viewModelScope.launch {
            while (true) {
                delay(30_000)
                _time.value = withContext(Dispatchers.IO) { screenTime() }
            }
        }
    }

    private fun screenTime(): ScreenTime {
        if (!usage.allowed()) return ScreenTime(false, emptyMap(), 0, emptyMap(), emptyMap())
        val openable = usage.phoneApps().map { it.pkg }.toSet()
        val skip = usage.notScreenTime()
        val today = usage.today().filterKeys { it !in skip }
        val typical = usage.typical().filterKeys { it !in skip }
        val labels = (today.keys + typical.keys + store.limits.value.map { it.pkg }).associateWith { pkg ->
            store.limits.value.firstOrNull { it.pkg == pkg }?.label ?: usage.label(pkg)
        }
        return ScreenTime(true, today, today.values.sum(), typical, labels, openable)
    }

    fun loadApps() {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) {
                val typical = runCatching { usage.typical() }.getOrDefault(emptyMap())
                usage.phoneApps().sortedWith(compareByDescending<PhoneApp> { typical[it.pkg] ?: 0L }.thenBy { it.label.lowercase() })
            }
            if (_time.value == null) refresh()
        }
    }

    fun icon(pkg: String) = usage.icon(pkg)

    fun typical(pkg: String): Long? = _time.value?.typical?.get(pkg)

    /** Adds an app, or saves a change to one. Stricter (or with App limits off) happens now; looser waits. */
    fun save(pkg: String, label: String, minutes: Int, warn: Int, wayOut: Boolean) {
        // Never this app: App limits is turned off from inside it.
        if (pkg == getApplication<Application>().packageName) return
        val old = store.limitFor(pkg)
        if (old == null) {
            store.setNow(AppLimit(pkg, label, minutes, warn, wayOut))
            _done.value = "$label: ${LimitRules.hmMinutes(minutes)} a day, from now."
            refresh()
            return
        }
        if (old.warnMinutes != warn) store.setWarning(pkg, warn)
        if (old.minutes == minutes && old.wayOut == wayOut) {
            _done.value = "Saved."
            return
        }
        if (LimitRules.loosens(old, minutes, wayOut) && on.value) {
            _hold.value = Hold.Limit(pkg, label, minutes, wayOut, remove = false)
            return
        }
        store.setNow(old.copy(minutes = minutes, wayOut = wayOut, warnMinutes = warn))
        _done.value = "$label: ${LimitRules.hmMinutes(minutes)} a day, from now."
        LimitWatcher.sync(getApplication())
    }

    fun remove(limit: AppLimit) {
        if (on.value) {
            _hold.value = Hold.Limit(limit.pkg, limit.label, limit.minutes, limit.wayOut, remove = true)
        } else {
            store.removeNow(limit.pkg)
            _done.value = "${limit.label} is off your limits."
            LimitWatcher.sync(getApplication())
        }
    }

    fun cancelChange(pkg: String) {
        store.cancelChange(pkg)
        _done.value = "Change cancelled."
    }

    fun turnOff() {
        _hold.value = Hold.TurnOff
    }

    fun turnOn() {
        store.setOn(true)
        _done.value = "App limits is on."
        refresh()
    }

    fun waitMsFor(h: Hold): Long = store.waitMs(h.waitMinutes)

    /** Starts (or starts again) the wait for what's held. */
    fun startWait() {
        val h = _hold.value ?: return
        _holdUntil.value = System.currentTimeMillis() + store.waitMs(h.waitMinutes)
    }

    /** Leaving the screen: the wait starts again from the beginning when you're back. */
    fun stopWait() {
        _holdUntil.value = 0L
    }

    fun dropHold() {
        _hold.value = null
        _holdUntil.value = 0L
    }

    /** The wait is over (and the promise typed, where it's needed): the change is made. */
    fun finishHold() {
        val h = _hold.value ?: return
        if (_holdUntil.value == 0L || System.currentTimeMillis() < _holdUntil.value) return
        when (h) {
            is Hold.Limit -> {
                store.setFromTomorrow(h.pkg, h.minutes, h.wayOut, h.remove)
                _done.value = if (h.remove) "${h.label} comes off your limits tomorrow." else "From tomorrow, ${h.label} gets ${LimitRules.hmMinutes(h.minutes)} a day."
            }
            Hold.TurnOff -> {
                store.setOn(false)
                // Off means off: the device admin goes too, so the app can be uninstalled.
                runCatching {
                    val ctx = getApplication<Application>()
                    ctx.getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(ComponentName(ctx, LimitAdmin::class.java))
                }
                _done.value = "App limits is off. Its permissions can be removed now."
            }
        }
        dropHold()
        refresh()
    }

    fun doneShown() {
        _done.value = null
    }
}
