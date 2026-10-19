package app.brokeometer.limits

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * What App limits keeps: the apps and their limits, blocked hours, whether it's on, and today's way
 * outs and warnings. Changes that loosen a limit wait for the next day ([Change]); [refresh] lets them take
 * over once their day comes.
 */
class LimitStore(context: Context) {

    private val sp = context.getSharedPreferences("limits", Context.MODE_PRIVATE)

    private val _limits = MutableStateFlow(readLimits())
    /** Every app on the list, as saved (with any change still waiting). */
    val limits: StateFlow<List<AppLimit>> = _limits

    private val _hours = MutableStateFlow(readHours())
    /** Blocked hours, as saved (with any change still waiting). */
    val hours: StateFlow<BlockedHours> = _hours

    private val _on = MutableStateFlow(sp.getBoolean(KEY_ON, true))
    /** Whether App limits is on. Turning it off takes [LimitRules.TURN_OFF_WAIT] minutes. */
    val on: StateFlow<Boolean> = _on

    private val _day = MutableStateFlow(readDay())
    /** Today's way outs and warnings. */
    val day: StateFlow<DayState> = _day

    /** Waits in seconds instead of minutes: only for the test build on an emulator. */
    @Volatile var fast: Boolean = false

    /** Milliseconds for a wait of [minutes]. */
    fun waitMs(minutes: Int): Long = minutes * if (fast) 1_000L else 60_000L

    @Synchronized
    fun refresh(today: LocalDate = LocalDate.now()) {
        val now = _limits.value.mapNotNull { LimitRules.onDay(it, today) }
        if (now != _limits.value) saveLimits(now)
        val h = HoursRules.at(_hours.value, System.currentTimeMillis())
        if (h != _hours.value) saveHours(h)
        if (_day.value.day != today.toString()) saveDay(DayState(today.toString()))
    }

    /** An app's limit as it stands today, or null when it has none. */
    fun limitFor(pkg: String): AppLimit? = _limits.value.firstOrNull { it.pkg == pkg }

    /** Whether [pkg] is closed right now by blocked hours. */
    fun closedNow(pkg: String): Boolean =
        HoursRules.closed(HoursRules.at(_hours.value, System.currentTimeMillis()), pkg, java.time.LocalTime.now())

    /** Saves blocked hours as they are given: the rules of what waits are in [HoursRules]. */
    @Synchronized
    fun setHours(h: BlockedHours) {
        saveHours(h)
    }

    /** Adds an app or makes its limit stricter: straight away. Any loosening change waiting for it is dropped. */
    @Synchronized
    fun setNow(limit: AppLimit) {
        val list = _limits.value.filterNot { it.pkg == limit.pkg } + limit.copy(next = null)
        saveLimits(list.sortedBy { it.label.lowercase() })
    }

    /** Takes an app off the list straight away: only while App limits is off. */
    @Synchronized
    fun removeNow(pkg: String) {
        saveLimits(_limits.value.filterNot { it.pkg == pkg })
    }

    /** Loosens an app's limit, from tomorrow. */
    @Synchronized
    fun setFromTomorrow(pkg: String, minutes: Int, wayOut: Boolean, remove: Boolean = false) {
        val from = LocalDate.now().plusDays(1).toString()
        saveLimits(_limits.value.map { if (it.pkg == pkg) it.copy(next = Change(from, minutes, wayOut, remove)) else it })
    }

    /** Drops a waiting change: making things stricter again, so straight away. */
    @Synchronized
    fun cancelChange(pkg: String) {
        saveLimits(_limits.value.map { if (it.pkg == pkg) it.copy(next = null) else it })
    }

    @Synchronized
    fun setWarning(pkg: String, minutes: Int) {
        saveLimits(_limits.value.map { if (it.pkg == pkg) it.copy(warnMinutes = minutes) else it })
    }

    fun setOn(value: Boolean) {
        sp.edit().putBoolean(KEY_ON, value).apply()
        _on.value = value
    }

    /** Minutes added today by the way out for [pkg]. */
    fun extraMinutes(pkg: String): Int = _day.value.extra[pkg] ?: 0

    /** Way outs used today for [pkg]. */
    fun wayOutsUsed(pkg: String): Int = _day.value.wayOuts[pkg] ?: 0

    @Synchronized
    fun addWayOut(pkg: String) {
        refresh()
        val d = _day.value
        saveDay(
            d.copy(
                extra = d.extra + (pkg to (d.extra[pkg] ?: 0) + LimitRules.EXTRA_MINUTES),
                wayOuts = d.wayOuts + (pkg to (d.wayOuts[pkg] ?: 0) + 1),
            ),
        )
    }

    fun warned(pkg: String): Boolean = pkg in _day.value.warned

    @Synchronized
    fun markWarned(pkg: String) {
        saveDay(_day.value.copy(warned = _day.value.warned + pkg))
    }

    private fun saveLimits(list: List<AppLimit>) {
        val arr = JSONArray()
        list.forEach { l ->
            arr.put(
                JSONObject()
                    .put("pkg", l.pkg)
                    .put("label", l.label)
                    .put("minutes", l.minutes)
                    .put("warn", l.warnMinutes)
                    .put("wayOut", l.wayOut)
                    .apply {
                        l.next?.let { c ->
                            put("next", JSONObject().put("from", c.from).put("minutes", c.minutes).put("wayOut", c.wayOut).put("remove", c.remove))
                        }
                    },
            )
        }
        sp.edit().putString(KEY_LIMITS, arr.toString()).apply()
        _limits.value = list
    }

    private fun readLimits(): List<AppLimit> = runCatching {
        val arr = JSONArray(sp.getString(KEY_LIMITS, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AppLimit(
                pkg = o.getString("pkg"),
                label = o.getString("label"),
                minutes = o.getInt("minutes"),
                warnMinutes = o.optInt("warn", 10),
                wayOut = o.optBoolean("wayOut", true),
                next = o.optJSONObject("next")?.let { n ->
                    Change(n.getString("from"), n.getInt("minutes"), n.getBoolean("wayOut"), n.optBoolean("remove"))
                },
            )
        }
    }.getOrDefault(emptyList())

    private fun saveHours(h: BlockedHours) {
        val o = JSONObject()
            .put("start", h.start)
            .put("end", h.end)
            .put("apps", JSONArray().apply { h.apps.forEach { a -> put(JSONObject().put("pkg", a.pkg).put("label", a.label)) } })
        h.next?.let { c ->
            o.put(
                "next",
                JSONObject().put("from", c.from).put("remove", JSONArray(c.remove.toList())).apply {
                    c.start?.let { put("start", it) }
                    c.end?.let { put("end", it) }
                },
            )
        }
        sp.edit().putString(KEY_HOURS, o.toString()).apply()
        _hours.value = h
    }

    private fun readHours(): BlockedHours = runCatching {
        val o = JSONObject(sp.getString(KEY_HOURS, null)!!)
        val arr = o.optJSONArray("apps") ?: JSONArray()
        BlockedHours(
            start = o.getInt("start"),
            end = o.getInt("end"),
            apps = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { HoursApp(it.getString("pkg"), it.getString("label")) } },
            next = o.optJSONObject("next")?.let { n ->
                val rm = n.optJSONArray("remove") ?: JSONArray()
                HoursChange(
                    from = n.getLong("from"),
                    start = if (n.has("start")) n.getInt("start") else null,
                    end = if (n.has("end")) n.getInt("end") else null,
                    remove = (0 until rm.length()).map { rm.getString(it) }.toSet(),
                )
            },
        )
    }.getOrDefault(BlockedHours())

    private fun saveDay(d: DayState) {
        val o = JSONObject()
            .put("day", d.day)
            .put("extra", JSONObject(d.extra as Map<*, *>))
            .put("wayOuts", JSONObject(d.wayOuts as Map<*, *>))
            .put("warned", JSONArray(d.warned.toList()))
        sp.edit().putString(KEY_DAY, o.toString()).apply()
        _day.value = d
    }

    private fun readDay(): DayState = runCatching {
        val o = JSONObject(sp.getString(KEY_DAY, null)!!)
        fun ints(name: String): Map<String, Int> {
            val m = o.optJSONObject(name) ?: return emptyMap()
            return m.keys().asSequence().associateWith { m.getInt(it) }
        }
        val warned = o.optJSONArray("warned") ?: JSONArray()
        DayState(o.getString("day"), ints("extra"), ints("wayOuts"), (0 until warned.length()).map { warned.getString(it) }.toSet())
    }.getOrDefault(DayState(LocalDate.now().toString()))

    companion object {
        private const val KEY_LIMITS = "limits"
        private const val KEY_ON = "on"
        private const val KEY_DAY = "day"
        private const val KEY_HOURS = "hours"
    }
}

/** One day's way outs (minutes added and how many times) and warnings sent, per app. */
data class DayState(
    val day: String,
    val extra: Map<String, Int> = emptyMap(),
    val wayOuts: Map<String, Int> = emptyMap(),
    val warned: Set<String> = emptySet(),
)
