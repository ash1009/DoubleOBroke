package app.brokeometer.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brokeometer.BrokeApp
import app.brokeometer.data.AwayRangeEntity
import app.brokeometer.data.BackupCodec
import app.brokeometer.data.RestorePlan
import app.brokeometer.data.RestoreResult
import app.brokeometer.data.CaptureLogEntity
import app.brokeometer.data.DayTotal
import app.brokeometer.data.ImportPlan
import app.brokeometer.data.fxTableOf
import app.brokeometer.data.Catalog
import app.brokeometer.data.MonthTotal
import app.brokeometer.data.TransactionEntity
import app.brokeometer.data.TxSource
import app.brokeometer.data.TxStatus
import app.brokeometer.domain.AwayDays
import app.brokeometer.domain.ImportFormatException
import app.brokeometer.insights.FxTable
import app.brokeometer.insights.Goals
import app.brokeometer.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.YearMonth

sealed interface ImportState {
    data object Idle : ImportState
    data object Working : ImportState
    data class Ready(val plan: ImportPlan) : ImportState
    data class NothingNew(val plan: ImportPlan) : ImportState
    data class Done(val added: Int, val trips: Int) : ImportState
    /** A backup file was opened: what restoring it would add. */
    data class RestoreReady(val plan: RestorePlan) : ImportState
    data class RestoreNothingNew(val plan: RestorePlan) : ImportState
    data class Restored(val result: RestoreResult) : ImportState
    data class Failed(val message: String) : ImportState
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as BrokeApp).container
    private val repository = container.repository
    private val dao = repository.dao
    private val prefs = container.prefs
    private val notifier = container.notifier

    private fun <T> kotlinx.coroutines.flow.Flow<T>.held(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    private val _todayForInsights = MutableStateFlow(LocalDate.now())

    val catalog: StateFlow<Catalog> =
        combine(dao.observeCategories(), dao.observeSubcategories()) { c, s -> Catalog(c, s) }.held(Catalog())

    val pending: StateFlow<List<TransactionEntity>> = dao.observePending().held(emptyList())

    val history: StateFlow<List<TransactionEntity>> = dao.observeHistory().held(emptyList())

    /** Money in and transfers, newest first, for the Money in page. */
    val moneyMoves: StateFlow<List<app.brokeometer.data.MoneyMoveEntity>> = dao.observeMoves().held(emptyList())

    /** Payments that money in paid back, by id. */
    val paidBack: StateFlow<Map<Long, TransactionEntity>> =
        dao.observePaidBack().map { list -> list.associateBy { it.id } }.held(emptyMap())

    fun moneyMine(id: Long) = viewModelScope.launch { container.money.markMine(id) }

    // Bank and Oyster balances

    /** The balances as typed in, and when; null until they are. */
    val balanceStart: StateFlow<app.brokeometer.insights.BalanceStart?> = prefs.balances

    /** The bank and Oyster balances now, kept up to date from payments, money in, top-ups and trips. */
    val balances: StateFlow<app.brokeometer.insights.Balances?> = prefs.balances.flatMapLatest { start ->
        if (start == null) {
            kotlinx.coroutines.flow.flowOf(null)
        } else {
            combine(dao.observeSince(start.at), dao.observeMoves()) { payments, moves ->
                val ids = moves.map { it.id }.toSet()
                app.brokeometer.insights.BalanceMath.now(
                    start,
                    payments.map { app.brokeometer.insights.BalancePayment(it.source, it.currency, it.paidMinor ?: it.amountMinor, it.occurredAt) },
                    moves.map {
                        app.brokeometer.insights.BalanceMove(it.direction, it.kind, it.currency, it.amountMinor, it.occurredAt, paired = it.pairId != null && it.pairId in ids)
                    },
                )
            }
        }
    }.held(null)

    /** Types in the balances as they are now; from here on the app keeps them up to date. */
    fun setBalances(bankMinor: Long, oysterMinor: Long) {
        prefs.setBalances(app.brokeometer.insights.BalanceStart(bankMinor, oysterMinor, System.currentTimeMillis()))
    }

    /** A payment that was money put on the Oyster card: it moves to Money in as a top-up. */
    fun oysterTopUp(id: Long) = viewModelScope.launch { container.money.markTopUp(id) }

    fun moneyOther(id: Long) = viewModelScope.launch { container.money.markOther(id) }

    /** A transfer out that was really a payment: back to payments waiting. */
    fun notATransfer(id: Long) = viewModelScope.launch {
        val saved = container.money.notATransfer(id) ?: return@launch
        notifier.showPending(saved, repository.catalog().label(saved.subcategoryId))
    }

    val monthTotals: StateFlow<List<MonthTotal>> = dao.observeMonthTotals().held(emptyList())

    val dayTotals: StateFlow<List<DayTotal>> = dao.observeDayTotals().held(emptyList())

    val awayRanges: StateFlow<List<AwayRangeEntity>> = dao.observeAwayRanges().held(emptyList())

    /** Trip days, left out of averages. */
    val away: StateFlow<AwayDays> = dao.observeAwayRanges()
        .map { rows -> AwayDays(rows.map { Dates.parseDay(it.startDay) to Dates.parseDay(it.endDay) }) }
        .held(AwayDays.NONE)

    val goals: StateFlow<Goals> = prefs.goals

    val saveLocation: StateFlow<Boolean> = prefs.saveLocation

    val appLock: StateFlow<Boolean> = prefs.appLock

    fun setAppLock(value: Boolean) = prefs.setAppLock(value)
    val location get() = container.location

    fun setSaveLocation(value: Boolean) = prefs.setSaveLocation(value)

    private data class InsightInputs(
        val rows: List<TransactionEntity>,
        val pending: List<TransactionEntity>,
        val catalog: Catalog,
        val fx: FxTable,
        val away: AwayDays,
    )

    /** Everything on the Insights page. Null while it is first worked out. */
    val insights: StateFlow<InsightsData?> = combine(
        combine(
            dao.observeConfirmed(),
            dao.observePending(),
            combine(dao.observeCategories(), dao.observeSubcategories()) { c, s -> Catalog(c, s) },
            dao.observeFxRates().map { fxTableOf(it) },
            dao.observeAwayRanges().map { rows -> AwayDays(rows.map { Dates.parseDay(it.startDay) to Dates.parseDay(it.endDay) }) },
            ::InsightInputs,
        ),
        _todayForInsights,
        prefs.goals,
        prefs.hiddenRegulars,
    ) { inputs, today, goals, hidden ->
        buildInsights(inputs.rows, inputs.pending, inputs.catalog, inputs.fx, inputs.away, today, goals, hidden)
    }.flowOn(Dispatchers.Default).held(null)

    /** The year open on the recap screen. */
    private val _recapYear = MutableStateFlow(LocalDate.now().year)
    val recapYear: StateFlow<Int> = _recapYear

    fun showRecap(year: Int) {
        _recapYear.value = year
    }

    fun saveGoals(goals: Goals) = prefs.setGoals(goals)

    fun hideRegular(key: String) = prefs.hideRegular(key)

    // AI for new shops

    val geminiKey: StateFlow<String> = prefs.geminiKey

    private val _aiStatus = MutableStateFlow<String?>(null)
    val aiStatus: StateFlow<String?> = _aiStatus

    fun setGeminiKey(value: String) {
        prefs.setGeminiKey(value)
        _aiStatus.value = null
    }

    /** Asks Gemini to file a made-up Subway lunch, to check the key works. */
    fun testGemini() = viewModelScope.launch {
        _aiStatus.value = "Asking Gemini..."
        val outcome = container.ai.ask(
            app.brokeometer.domain.AiPayment("Subway", "SUBWAY 58204-0", "London", "£4.20", "Thursday 13:05"),
            useClaude = false,
        )
        _aiStatus.value = if (outcome.answer != null) {
            "Works. A £4.20 Subway at 1pm came back as ${outcome.note}."
        } else {
            outcome.note
        }
    }

    fun showAllRegulars() = prefs.showAllRegulars()

    fun refreshRates() = viewModelScope.launch { container.fx.refresh() }

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import

    /** The month the home screen is about. Refreshed when the app comes back to the front. */
    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today

    val thisMonth: StateFlow<List<TransactionEntity>> =
        _today.flatMapLatest { dao.observeMonth(YearMonth.from(it).toString()) }.held(emptyList())

    /** The month open on the Months screen. */
    private val _selectedMonth = MutableStateFlow(YearMonth.now())
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth

    val selectedMonthEntries: StateFlow<List<TransactionEntity>> =
        _selectedMonth.flatMapLatest { dao.observeMonth(it.toString()) }.held(emptyList())

    val captureLog: StateFlow<List<CaptureLogEntity>> = dao.observeLog().held(emptyList())

    val theme: StateFlow<String> = prefs.theme
    val username: StateFlow<String> = prefs.username
    val avatarVersion: StateFlow<Long> = prefs.avatarVersion
    val keepAlerts: StateFlow<Boolean> = prefs.keepAlerts

    val avatarFile: File get() = File(getApplication<Application>().filesDir, AVATAR_FILE)

    fun refreshToday() {
        val now = LocalDate.now()
        if (now != _today.value) _today.value = now
        if (now != _todayForInsights.value) _todayForInsights.value = now
    }

    fun showMonth(month: YearMonth) {
        _selectedMonth.value = month
    }

    fun stepMonth(by: Long) {
        _selectedMonth.value = _selectedMonth.value.plusMonths(by)
    }

    // Entries

    fun accept(id: Long) = viewModelScope.launch {
        if (repository.accept(id)) notifier.cancel(id)
    }

    /** Accepts every waiting entry that has a guess. */
    fun acceptAll() = viewModelScope.launch {
        dao.pending().filter { it.subcategoryId != null }.forEach {
            if (repository.accept(it.id)) notifier.cancel(it.id)
        }
    }

    fun setCategory(id: Long, subcategoryId: Long) = viewModelScope.launch {
        if (repository.setCategory(id, subcategoryId)) notifier.cancel(id)
    }

    /** Saves edits from the entry sheet. Saving always confirms the entry. */
    fun saveEntry(
        original: TransactionEntity,
        amountMinor: Long,
        label: String,
        date: LocalDate,
        subcategoryId: Long,
        note: String,
    ) = viewModelScope.launch {
        val sameDay = Dates.localDate(original.occurredAt) == date
        val occurredAt = if (sameDay) original.occurredAt else Dates.middayMillis(date)
        val categoryChanged = original.subcategoryId != subcategoryId
        // With money paid back, the amount shown is your share; what was paid moves with it.
        val paid = original.paidMinor?.let { it + (amountMinor - original.amountMinor) }
        repository.save(
            original.copy(
                amountMinor = amountMinor,
                paidMinor = paid,
                merchantName = label.trim().ifEmpty { original.merchantName },
                occurredAt = occurredAt,
                day = Dates.toDay(date),
                subcategoryId = subcategoryId,
                status = TxStatus.CONFIRMED,
                guessSource = if (categoryChanged) "USER" else original.guessSource,
                note = note.trim(),
            )
        )
        notifier.cancel(original.id)
    }

    fun addEntry(
        amountMinor: Long,
        label: String,
        date: LocalDate,
        subcategoryId: Long,
        note: String,
        currency: String = "GBP",
        place: String = "",
    ) = viewModelScope.launch {
        val occurredAt = if (date == LocalDate.now()) System.currentTimeMillis() else Dates.middayMillis(date)
        repository.addManual(amountMinor, label, occurredAt, subcategoryId, note, currency, place)
    }

    // Adding by voice

    sealed interface VoiceState {
        data object Idle : VoiceState
        data object Working : VoiceState
        data class Saved(val id: Long, val message: String) : VoiceState
        /** Not everything was understood: the form opens with what was. */
        data class Check(val draft: app.brokeometer.domain.VoiceDraft, val message: String) : VoiceState
    }

    private val _voice = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voice: StateFlow<VoiceState> = _voice

    /** True when the app was opened to add by voice (the app icon's shortcut), so the Payments page starts listening. */
    private val _voiceAsked = MutableStateFlow(false)
    val voiceAsked: StateFlow<Boolean> = _voiceAsked

    fun askForVoice() {
        _voiceAsked.value = true
    }

    fun voiceAskHandled() {
        _voiceAsked.value = false
    }

    fun addByVoice(spoken: String) {
        if (spoken.isBlank()) return
        _voice.value = VoiceState.Working
        viewModelScope.launch {
            _voice.value = when (val r = container.voice.add(spoken.trim())) {
                is app.brokeometer.capture.VoiceAdder.Result.Saved -> VoiceState.Saved(r.id, r.message)
                is app.brokeometer.capture.VoiceAdder.Result.Check -> VoiceState.Check(r.draft, r.message)
            }
        }
    }

    fun voiceShown() {
        _voice.value = VoiceState.Idle
    }

    fun deleteEntry(id: Long) = viewModelScope.launch {
        repository.delete(id)
        notifier.cancel(id)
    }

    // Categories

    fun addCategory(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.addCategory(name)
    }

    fun addSubcategory(categoryId: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.addSubcategory(categoryId, name)
    }

    // Settings and account

    fun setTheme(value: String) = prefs.setTheme(value)

    /** The agents in the order you chose: the bottom bar, the swipe order and the cards on Home. */
    val agentOrder: StateFlow<List<String>> = prefs.agentOrder

    /** Moves an agent up (-1) or down (+1) the order. */
    fun moveAgent(key: String, by: Int) {
        val order = prefs.agentOrder.value.toMutableList()
        val from = order.indexOf(key)
        val to = from + by
        if (from < 0 || to !in order.indices) return
        order.removeAt(from)
        order.add(to, key)
        prefs.setAgentOrder(order)
    }

    /** The Goals sheet, opened from the top bar on Insights or from a card. */
    private val _editingGoals = MutableStateFlow(false)
    val editingGoals: StateFlow<Boolean> = _editingGoals

    fun editGoals(open: Boolean) {
        _editingGoals.value = open
    }

    fun setKeepAlerts(value: Boolean) = prefs.setKeepAlerts(value)

    fun setUsername(value: String) = prefs.setUsername(value.trim())

    /** Copies the chosen picture into the app, scaled down, so it stays even if the original is deleted. */
    fun setAvatar(uri: Uri) = viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@withContext false
                val side = minOf(bitmap.width, bitmap.height)
                val square = Bitmap.createBitmap(
                    bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side
                )
                val scaled = if (side > 512) Bitmap.createScaledBitmap(square, 512, 512, true) else square
                FileOutputStream(avatarFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                true
            } catch (e: Exception) {
                false
            }
        }
        if (saved) prefs.bumpAvatar()
    }

    // Import

    /** Reads the chosen file and shows what it would add. Nothing is written yet. */
    fun readImport(uri: Uri) = viewModelScope.launch {
        _import.value = ImportState.Working
        _import.value = try {
            val text = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > MAX_IMPORT_BYTES) throw ImportFormatException("That file is too big to be an import file.")
                    bytes.toString(Charsets.UTF_8)
                }
            } ?: throw ImportFormatException("Could not open that file.")
            if (BackupCodec.looksLikeBackup(text)) {
                val plan = container.backups.planRestore(text)
                if (plan.isEmpty) ImportState.RestoreNothingNew(plan) else ImportState.RestoreReady(plan)
            } else {
                val plan = repository.planImport(text)
                if (plan.isEmpty) ImportState.NothingNew(plan) else ImportState.Ready(plan)
            }
        } catch (e: ImportFormatException) {
            ImportState.Failed(e.message ?: "Could not read that file.")
        } catch (e: Exception) {
            ImportState.Failed("Could not read that file.")
        }
    }

    fun confirmImport() = viewModelScope.launch {
        when (val state = _import.value) {
            is ImportState.Ready -> {
                _import.value = ImportState.Working
                _import.value = try {
                    ImportState.Done(added = repository.commitImport(state.plan), trips = state.plan.away.size)
                } catch (e: Exception) {
                    ImportState.Failed("The import stopped part way. Nothing was saved.")
                }
            }
            is ImportState.RestoreReady -> {
                _import.value = ImportState.Working
                _import.value = try {
                    ImportState.Restored(container.backups.commitRestore(state.plan))
                } catch (e: Exception) {
                    ImportState.Failed("The restore stopped part way. Nothing was saved.")
                }
            }
            else -> return@launch
        }
        // Rupee and other amounts need the day's rate before insights can show them in pounds.
        container.fx.refresh()
    }

    fun dismissImport() {
        _import.value = ImportState.Idle
    }

    // Backup

    val backupFolder: StateFlow<String?> = prefs.backupFolder
    val lastBackupAt: StateFlow<Long> = prefs.lastBackupAt
    val backupFailed: StateFlow<Boolean> = prefs.backupFailed

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage

    private val _backupWorking = MutableStateFlow(false)
    val backupWorking: StateFlow<Boolean> = _backupWorking

    fun backupFolderName(): String? = container.backups.folderName()

    /** Saves a backup file where the owner chose, such as Google Drive. */
    fun backupTo(uri: Uri) = viewModelScope.launch {
        _backupWorking.value = true
        val ok = container.backups.writeTo(uri)
        _backupWorking.value = false
        _backupMessage.value = if (ok) "Backup saved." else "Could not save the backup there. Try another place."
    }

    fun startWeeklyBackup(folder: Uri) = viewModelScope.launch {
        _backupWorking.value = true
        val ok = container.backups.startWeekly(folder)
        _backupWorking.value = false
        if (!ok) container.backups.stopWeekly()
        _backupMessage.value = if (ok) {
            "Weekly backup is on. The first file is already saved there."
        } else {
            "Could not save to that folder. Try another one, such as Documents or Download."
        }
    }

    fun stopWeeklyBackup() = container.backups.stopWeekly()

    fun dismissBackupMessage() {
        _backupMessage.value = null
    }

    /** Runs a made-up payment through the whole flow, so the alert and its buttons can be tried. */
    fun sendTestPayment() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        container.capture.onBankAlert(
            packageName = "test",
            alertKey = "test-$now",
            postedAt = now,
            title = "Money Out",
            text = "£4.20 transaction | SUBWAY 58204-0    LONDON          GB | BANK A/C",
            source = TxSource.TEST,
        )
    }

    fun removeTestPayments() = viewModelScope.launch {
        dao.pending().filter { it.source == TxSource.TEST }.forEach { notifier.cancel(it.id) }
        dao.deleteTestTransactions()
    }

    companion object {
        const val AVATAR_FILE = "avatar.jpg"
        private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }
}
