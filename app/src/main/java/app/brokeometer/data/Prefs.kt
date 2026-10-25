package app.brokeometer.data

import android.content.Context
import app.brokeometer.insights.FixedCosts
import app.brokeometer.insights.Goals
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small settings kept on the phone. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

    /** Keys the owner pasted in. Kept out of every backup, so they never leave the phone. */
    private val secrets = context.applicationContext.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)
        .also { moveOldSecrets(it) }

    /** Things that only make sense on this phone, such as the backup folder. Also kept out of backups. */
    private val device = context.applicationContext.getSharedPreferences(DEVICE_FILE, Context.MODE_PRIVATE)

    /** Versions before backups kept the Gemini key with the other settings. */
    private fun moveOldSecrets(target: android.content.SharedPreferences) {
        if (!sp.contains(KEY_GEMINI) && !sp.contains(KEY_GEMINI_MODEL)) return
        target.edit()
            .putString(KEY_GEMINI, sp.getString(KEY_GEMINI, "") ?: "")
            .putString(KEY_GEMINI_MODEL, sp.getString(KEY_GEMINI_MODEL, "") ?: "")
            .commit()
        sp.edit().remove(KEY_GEMINI).remove(KEY_GEMINI_MODEL).commit()
    }

    private val _theme = MutableStateFlow(sp.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK)
    val theme: StateFlow<String> = _theme

    private val _username = MutableStateFlow(sp.getString(KEY_USERNAME, "") ?: "")
    val username: StateFlow<String> = _username

    /** Goes up each time the profile picture changes, so screens reload it. */
    private val _avatarVersion = MutableStateFlow(sp.getLong(KEY_AVATAR_VERSION, 0L))
    val avatarVersion: StateFlow<Long> = _avatarVersion

    private val _keepAlerts = MutableStateFlow(sp.getBoolean(KEY_KEEP_ALERTS, true))
    val keepAlerts: StateFlow<Boolean> = _keepAlerts

    /** Regular payments the owner said are not regular. */
    private val _hiddenRegulars = MutableStateFlow(sp.getStringSet(KEY_HIDDEN_REGULARS, emptySet())?.toSet() ?: emptySet())
    val hiddenRegulars: StateFlow<Set<String>> = _hiddenRegulars

    /**
     * Names your banks use for you, learned from transfers between your own accounts ("MORGAN A",
     * "ALEX MORGAN"), on top of the name in your profile.
     */
    private val _ownNames = MutableStateFlow(sp.getStringSet(KEY_OWN_NAMES, emptySet())?.toSet() ?: emptySet())
    val ownNames: StateFlow<Set<String>> = _ownNames

    fun learnOwnName(name: String) {
        val clean = name.trim().uppercase()
        if (clean.split(' ').count { it.isNotBlank() } < 2 || clean in _ownNames.value) return
        val value = _ownNames.value + clean
        sp.edit().putStringSet(KEY_OWN_NAMES, value).apply()
        _ownNames.value = value
    }

    /** Categories and sub-categories you can't cut back on, like rent. */
    private val _fixedCosts = MutableStateFlow(readFixedCosts())
    val fixedCosts: StateFlow<FixedCosts> = _fixedCosts

    private fun readFixedCosts(): FixedCosts {
        fun ids(key: String) = sp.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        return FixedCosts(ids(KEY_FIXED_CATEGORIES), ids(KEY_FIXED_SUBCATEGORIES))
    }

    fun setFixedCosts(value: FixedCosts) {
        sp.edit()
            .putStringSet(KEY_FIXED_CATEGORIES, value.categories.map { it.toString() }.toSet())
            .putStringSet(KEY_FIXED_SUBCATEGORIES, value.subcategories.map { it.toString() }.toSet())
            .apply()
        _fixedCosts.value = value
    }

    fun hideRegular(key: String) = setHiddenRegulars(_hiddenRegulars.value + key)

    fun showAllRegulars() = setHiddenRegulars(emptySet())

    private fun setHiddenRegulars(value: Set<String>) {
        sp.edit().putStringSet(KEY_HIDDEN_REGULARS, value).apply()
        _hiddenRegulars.value = value
    }

    /** Ask for a fingerprint (or the phone's PIN) when the app opens. */
    private val _appLock = MutableStateFlow(sp.getBoolean(KEY_APP_LOCK, true))
    val appLock: StateFlow<Boolean> = _appLock

    fun setAppLock(value: Boolean) {
        sp.edit().putBoolean(KEY_APP_LOCK, value).apply()
        _appLock.value = value
    }

    /** The owner's Gemini API key. Kept in the app's private storage only. */
    private val _geminiKey = MutableStateFlow(secrets.getString(KEY_GEMINI, "") ?: "")
    val geminiKey: StateFlow<String> = _geminiKey

    fun setGeminiKey(value: String) {
        secrets.edit().putString(KEY_GEMINI, value.trim()).remove(KEY_GEMINI_MODEL).apply()
        _geminiKey.value = value.trim()
    }

    /** The Gemini model that last worked with the key. */
    var geminiModel: String
        get() = secrets.getString(KEY_GEMINI_MODEL, "") ?: ""
        set(value) = secrets.edit().putString(KEY_GEMINI_MODEL, value).apply()

    /** The email agent: on means it handles new email and checks spam. Off until the owner turns it on. */
    private val _mailAgentOn = MutableStateFlow(sp.getBoolean(KEY_MAIL_AGENT, false))
    val mailAgentOn: StateFlow<Boolean> = _mailAgentOn

    fun setMailAgentOn(value: Boolean) {
        sp.edit().putBoolean(KEY_MAIL_AGENT, value).apply()
        _mailAgentOn.value = value
    }

    /** Senders you marked as spam, whose spam goes straight to the bin. */
    private val _spamSenders = MutableStateFlow(sp.getStringSet(KEY_SPAM_SENDERS, emptySet())?.toSet() ?: emptySet())
    val spamSenders: StateFlow<Set<String>> = _spamSenders

    /** Senders you said are not spam; anything of theirs in spam is moved to the inbox. */
    private val _notSpamSenders = MutableStateFlow(sp.getStringSet(KEY_NOT_SPAM_SENDERS, emptySet())?.toSet() ?: emptySet())
    val notSpamSenders: StateFlow<Set<String>> = _notSpamSenders

    fun addSpamSender(address: String) {
        val a = address.lowercase()
        val spam = _spamSenders.value + a
        val notSpam = _notSpamSenders.value - a
        sp.edit().putStringSet(KEY_SPAM_SENDERS, spam).putStringSet(KEY_NOT_SPAM_SENDERS, notSpam).apply()
        _spamSenders.value = spam
        _notSpamSenders.value = notSpam
    }

    fun addNotSpamSender(address: String) {
        val a = address.lowercase()
        val notSpam = _notSpamSenders.value + a
        val spam = _spamSenders.value - a
        sp.edit().putStringSet(KEY_SPAM_SENDERS, spam).putStringSet(KEY_NOT_SPAM_SENDERS, notSpam).apply()
        _spamSenders.value = spam
        _notSpamSenders.value = notSpam
    }

    /** Senders you said aren't important: their emails are marked read as soon as they arrive. */
    private val _quietSenders = MutableStateFlow(sp.getStringSet(KEY_QUIET_SENDERS, emptySet())?.toSet() ?: emptySet())
    val quietSenders: StateFlow<Set<String>> = _quietSenders

    fun setQuietSender(address: String, quiet: Boolean) {
        val a = address.lowercase()
        val next = if (quiet) _quietSenders.value + a else _quietSenders.value - a
        sp.edit().putStringSet(KEY_QUIET_SENDERS, next).apply()
        _quietSenders.value = next
        if (quiet) setImportantSender(a, false)
    }

    /** Senders you said are important: every email from them notifies you, whatever its kind. */
    private val _importantSenders = MutableStateFlow(sp.getStringSet(KEY_IMPORTANT_SENDERS, emptySet())?.toSet() ?: emptySet())
    val importantSenders: StateFlow<Set<String>> = _importantSenders

    fun setImportantSender(address: String, important: Boolean) {
        val a = address.lowercase()
        val next = if (important) _importantSenders.value + a else _importantSenders.value - a
        if (next == _importantSenders.value) return
        sp.edit().putStringSet(KEY_IMPORTANT_SENDERS, next).apply()
        _importantSenders.value = next
    }

    /** The owner's Claude API key, from the Claude Console. Secret, like the Gemini key. */
    private val _claudeKey = MutableStateFlow(secrets.getString(KEY_CLAUDE, "") ?: "")
    val claudeKey: StateFlow<String> = _claudeKey

    fun setClaudeKey(value: String) {
        secrets.edit().putString(KEY_CLAUDE, value.trim()).remove(KEY_CLAUDE_MODELS).apply()
        _claudeKey.value = value.trim()
    }

    /** Claude models the key can use, newest first, comma separated. Empty means ask again. */
    var claudeModels: String
        get() = secrets.getString(KEY_CLAUDE_MODELS, "") ?: ""
        set(value) = secrets.edit().putString(KEY_CLAUDE_MODELS, value).apply()

    /** The app password for an email account. */
    fun mailPassword(address: String): String = secrets.getString(KEY_MAIL_PASSWORD + address.lowercase(), "") ?: ""

    fun setMailPassword(address: String, password: String?) {
        val key = KEY_MAIL_PASSWORD + address.lowercase()
        secrets.edit().apply { if (password.isNullOrBlank()) remove(key) else putString(key, password) }.apply()
    }

    /** The folder the weekly backup goes to, as a document tree link, or null when weekly backup is off. */
    private val _backupFolder = MutableStateFlow(device.getString(KEY_BACKUP_FOLDER, null))
    val backupFolder: StateFlow<String?> = _backupFolder

    fun setBackupFolder(value: String?) {
        device.edit().apply { if (value != null) putString(KEY_BACKUP_FOLDER, value) else remove(KEY_BACKUP_FOLDER) }.apply()
        _backupFolder.value = value
    }

    /** When the last backup was saved, epoch milliseconds, or 0. */
    private val _lastBackupAt = MutableStateFlow(device.getLong(KEY_LAST_BACKUP_AT, 0L))
    val lastBackupAt: StateFlow<Long> = _lastBackupAt

    /** True when the last weekly backup could not be saved. */
    private val _backupFailed = MutableStateFlow(device.getBoolean(KEY_BACKUP_FAILED, false))
    val backupFailed: StateFlow<Boolean> = _backupFailed

    fun markBackup(at: Long?, failed: Boolean) {
        device.edit().apply {
            if (at != null) putLong(KEY_LAST_BACKUP_AT, at)
            putBoolean(KEY_BACKUP_FAILED, failed)
        }.apply()
        if (at != null) _lastBackupAt.value = at
        _backupFailed.value = failed
    }

    /** Save the phone's location with each card payment. */
    private val _saveLocation = MutableStateFlow(sp.getBoolean(KEY_SAVE_LOCATION, false))
    val saveLocation: StateFlow<Boolean> = _saveLocation

    fun setSaveLocation(value: Boolean) {
        sp.edit().putBoolean(KEY_SAVE_LOCATION, value).apply()
        _saveLocation.value = value
    }

    /** Noting where you go through the day, for the nightly tube and bus check. */
    private val _trackTrips = MutableStateFlow(sp.getBoolean(KEY_TRACK_TRIPS, false))
    val trackTrips: StateFlow<Boolean> = _trackTrips

    fun setTrackTrips(value: Boolean) {
        sp.edit().putBoolean(KEY_TRACK_TRIPS, value).apply()
        _trackTrips.value = value
    }

    /** "" for none, or "16-25" / "26-30": a third off off-peak train fares on Oyster. */
    private val _railcard = MutableStateFlow(sp.getString(KEY_RAILCARD, "") ?: "")
    val railcard: StateFlow<String> = _railcard

    fun setRailcard(value: String) {
        sp.edit().putString(KEY_RAILCARD, value).apply()
        _railcard.value = value
    }

    /**
     * The agents in the order that matters to you: it sets the bottom bar, the swipe order and the
     * cards on Home. Keys: "email", "money", "travel", "limits" (App limits has a Home card but sits under More).
     */
    private val _agentOrder = MutableStateFlow(readAgentOrder())
    val agentOrder: StateFlow<List<String>> = _agentOrder

    fun setAgentOrder(order: List<String>) {
        val clean = (order.filter { it in AGENTS } + AGENTS).distinct()
        sp.edit().putString(KEY_AGENT_ORDER, clean.joinToString(",")).apply()
        _agentOrder.value = clean
    }

    private fun readAgentOrder(): List<String> {
        val saved = sp.getString(KEY_AGENT_ORDER, null)?.split(',')?.map { it.trim() }.orEmpty()
        return (saved.filter { it in AGENTS } + AGENTS).distinct()
    }

    private val _moreOrder = MutableStateFlow(readMoreOrder())
    /** The features on More, in the order you chose. Keys: [MORE]. */
    val moreOrder: StateFlow<List<String>> = _moreOrder

    fun setMoreOrder(order: List<String>) {
        val clean = (order.filter { it in MORE } + MORE).distinct()
        sp.edit().putString(KEY_MORE_ORDER, clean.joinToString(",")).apply()
        _moreOrder.value = clean
    }

    private fun readMoreOrder(): List<String> {
        val saved = sp.getString(KEY_MORE_ORDER, null)?.split(',')?.map { it.trim() }.orEmpty()
        return (saved.filter { it in MORE } + MORE).distinct()
    }

    // Home cards and the bottom bar (see [Layout]). The first time, they come from the agent order and More's order.

    private val firstLayout = Layout.fromOld(readAgentOrder(), readMoreOrder())

    private val _homeCards = MutableStateFlow(Layout.decodeCards(sp.getString(KEY_HOME_CARDS, null)) ?: firstLayout.first)
    /** The cards on Home, in order, each on or off. */
    val homeCards: StateFlow<List<HomeCard>> = _homeCards

    fun setHomeCards(cards: List<HomeCard>) {
        val clean = Layout.cleanCards(cards)
        sp.edit().putString(KEY_HOME_CARDS, Layout.encodeCards(clean)).apply()
        _homeCards.value = clean
    }

    private val _barOrder = MutableStateFlow(
        sp.getString(KEY_BAR_ORDER, null)?.split(',')?.map { it.trim() }?.let { Layout.cleanOrder(it) } ?: firstLayout.second,
    )
    /** Every feature in order: the ticked ones are the bottom bar (and the swipe order), the rest are More. */
    val barOrder: StateFlow<List<String>> = _barOrder

    private val _barTicked = MutableStateFlow(
        sp.getString(KEY_BAR_TICKED, null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?.let { Layout.cleanTicked(_barOrder.value, it) } ?: firstLayout.third,
    )
    val barTicked: StateFlow<Set<String>> = _barTicked

    fun setBar(order: List<String>, ticked: Set<String>) {
        val o = Layout.cleanOrder(order)
        val t = Layout.cleanTicked(o, ticked)
        sp.edit().putString(KEY_BAR_ORDER, o.joinToString(",")).putString(KEY_BAR_TICKED, t.joinToString(",")).apply()
        _barOrder.value = o
        _barTicked.value = t
    }

    /** The last day the nightly trip check finished, as yyyy-MM-dd. */
    var lastTravelDay: String
        get() = sp.getString(KEY_LAST_TRAVEL_DAY, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_TRAVEL_DAY, value).apply()

    private val _balances = MutableStateFlow(readBalances())
    /** The bank and Oyster balances as typed in, and when; null until they are. */
    val balances: StateFlow<app.brokeometer.insights.BalanceStart?> = _balances

    private fun readBalances(): app.brokeometer.insights.BalanceStart? {
        val at = sp.getLong(KEY_BALANCE_AT, -1L).takeIf { it >= 0 } ?: return null
        return app.brokeometer.insights.BalanceStart(sp.getLong(KEY_BANK_BALANCE, 0L), sp.getLong(KEY_OYSTER_BALANCE, 0L), at)
    }

    fun setBalances(value: app.brokeometer.insights.BalanceStart?) {
        sp.edit().apply {
            if (value == null) {
                remove(KEY_BANK_BALANCE); remove(KEY_OYSTER_BALANCE); remove(KEY_BALANCE_AT)
            } else {
                putLong(KEY_BANK_BALANCE, value.bankMinor); putLong(KEY_OYSTER_BALANCE, value.oysterMinor); putLong(KEY_BALANCE_AT, value.at)
            }
        }.apply()
        _balances.value = value
    }

    private val _goals = MutableStateFlow(readGoals())
    val goals: StateFlow<Goals> = _goals

    private fun readGoals(): Goals = Goals(
        savingsMinor = sp.getLong(KEY_SAVINGS, -1L).takeIf { it >= 0 },
        savingsAt = sp.getLong(KEY_SAVINGS_AT, -1L).takeIf { it >= 0 },
        lastUntil = sp.getString(KEY_LAST_UNTIL, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        monthlyLimitMinor = sp.getLong(KEY_MONTHLY_LIMIT, -1L).takeIf { it > 0 },
        noSpendTarget = sp.getInt(KEY_NO_SPEND_TARGET, -1).takeIf { it > 0 },
        categoryCaps = (sp.getString(KEY_CATEGORY_CAPS, "") ?: "").split(';').mapNotNull { pair ->
            val (id, cap) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val key = id.toLongOrNull() ?: return@mapNotNull null
            val value = cap.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            key to value
        }.toMap(),
    )

    fun setGoals(goals: Goals) {
        sp.edit().apply {
            if (goals.savingsMinor != null) putLong(KEY_SAVINGS, goals.savingsMinor) else remove(KEY_SAVINGS)
            if (goals.savingsAt != null) putLong(KEY_SAVINGS_AT, goals.savingsAt) else remove(KEY_SAVINGS_AT)
            if (goals.lastUntil != null) putString(KEY_LAST_UNTIL, goals.lastUntil.toString()) else remove(KEY_LAST_UNTIL)
            if (goals.monthlyLimitMinor != null) putLong(KEY_MONTHLY_LIMIT, goals.monthlyLimitMinor) else remove(KEY_MONTHLY_LIMIT)
            if (goals.noSpendTarget != null) putInt(KEY_NO_SPEND_TARGET, goals.noSpendTarget) else remove(KEY_NO_SPEND_TARGET)
            putString(KEY_CATEGORY_CAPS, goals.categoryCaps.entries.joinToString(";") { "${it.key}=${it.value}" })
        }.apply()
        _goals.value = goals
    }

    /** Nudges already sent, as "yyyy-MM|categoryId|80", so each is sent once a month. */
    fun nudgeSent(key: String): Boolean = key in (sp.getStringSet(KEY_NUDGES, emptySet()) ?: emptySet())

    fun markNudgeSent(key: String, month: String) {
        val kept = (sp.getStringSet(KEY_NUDGES, emptySet()) ?: emptySet()).filter { it.startsWith(month) }.toSet()
        sp.edit().putStringSet(KEY_NUDGES, kept + key).apply()
    }

    /** Lets test alerts sent from a computer through. Off on a normal phone. */
    var testMode: Boolean
        get() = sp.getBoolean(KEY_TEST_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_TEST_MODE, value).apply()

    fun setTheme(value: String) {
        sp.edit().putString(KEY_THEME, value).apply()
        _theme.value = value
    }

    fun setUsername(value: String) {
        sp.edit().putString(KEY_USERNAME, value).apply()
        _username.value = value
    }

    fun bumpAvatar() {
        val next = _avatarVersion.value + 1
        sp.edit().putLong(KEY_AVATAR_VERSION, next).apply()
        _avatarVersion.value = next
    }

    fun setKeepAlerts(value: Boolean) {
        sp.edit().putBoolean(KEY_KEEP_ALERTS, value).apply()
        _keepAlerts.value = value
    }

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        /** File names, also used by the backup rules in res/xml. */
        const val SETTINGS_FILE = "settings"
        const val SECRETS_FILE = "secrets"
        const val DEVICE_FILE = "device"

        private const val KEY_THEME = "theme"
        private const val KEY_USERNAME = "username"
        private const val KEY_OWN_NAMES = "own_names"
        private const val KEY_AVATAR_VERSION = "avatar_version"
        private const val KEY_KEEP_ALERTS = "keep_alerts"
        private const val KEY_TEST_MODE = "test_mode"
        private const val KEY_SAVINGS = "goal_savings"
        private const val KEY_SAVINGS_AT = "goal_savings_at"
        private const val KEY_LAST_UNTIL = "goal_last_until"
        private const val KEY_MONTHLY_LIMIT = "goal_monthly_limit"
        private const val KEY_NO_SPEND_TARGET = "goal_no_spend_target"
        private const val KEY_CATEGORY_CAPS = "goal_category_caps"
        private const val KEY_NUDGES = "nudges_sent"
        private const val KEY_SAVE_LOCATION = "save_location"
        private const val KEY_TRACK_TRIPS = "track_trips"
        private const val KEY_RAILCARD = "railcard"
        private const val KEY_BANK_BALANCE = "bank_balance"
        private const val KEY_OYSTER_BALANCE = "oyster_balance"
        private const val KEY_BALANCE_AT = "balance_at"
        private const val KEY_AGENT_ORDER = "agent_order"
        private const val KEY_MORE_ORDER = "more_order"
        private const val KEY_HOME_CARDS = "home_cards"
        private const val KEY_BAR_ORDER = "bar_order"
        private const val KEY_BAR_TICKED = "bar_ticked"
        private const val KEY_IMPORTANT_SENDERS = "mail_important_senders"

        /** Every agent, in the order you chose to start with: email, money, travel, passwords. */
        val AGENTS = listOf("email", "money", "travel", "limits")

        /** What sits under More; new ones go at the end until you move them. */
        val MORE = listOf("limits", "passwords", "notes", "news")
        private const val KEY_LAST_TRAVEL_DAY = "last_travel_day"
        private const val KEY_HIDDEN_REGULARS = "hidden_regulars"
        private const val KEY_FIXED_CATEGORIES = "fixed_categories"
        private const val KEY_FIXED_SUBCATEGORIES = "fixed_subcategories"
        private const val KEY_GEMINI = "gemini_key"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_MAIL_AGENT = "mail_agent_on"
        private const val KEY_SPAM_SENDERS = "mail_spam_senders"
        private const val KEY_NOT_SPAM_SENDERS = "mail_not_spam_senders"
        private const val KEY_QUIET_SENDERS = "mail_quiet_senders"
        private const val KEY_CLAUDE = "claude_key"
        private const val KEY_CLAUDE_MODELS = "claude_models"
        private const val KEY_MAIL_PASSWORD = "mail_password_"
        private const val KEY_BACKUP_FOLDER = "backup_folder"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_BACKUP_FAILED = "backup_failed"
    }
}
