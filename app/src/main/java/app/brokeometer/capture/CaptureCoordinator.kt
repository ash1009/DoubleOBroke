package app.brokeometer.capture

import app.brokeometer.data.Prefs
import app.brokeometer.data.Repository
import app.brokeometer.data.TransactionEntity
import app.brokeometer.data.TxSource
import app.brokeometer.domain.GuessSource
import app.brokeometer.domain.HsbcParser
import app.brokeometer.domain.IciciParser
import app.brokeometer.domain.ParseResult
import app.brokeometer.domain.ParsedMoneyIn
import app.brokeometer.domain.StarlingParser
import app.brokeometer.domain.StarlingResult
import app.brokeometer.util.Dates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Takes a bank alert from the listener to a pending entry with its own Accept/Update alert.
 * Returns true when the alert was a payment that is now safely stored and our own alert is
 * showing, so the bank's alert can be cleared.
 */
class CaptureCoordinator(
    private val repository: Repository,
    private val notifier: Notifier,
    private val location: LocationFetcher,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val ai: AiGuesser,
    private val money: MoneyAgent,
) {
    /** The trip check, set once the app is put together: it keeps card charges and Oyster top-ups from counting twice. */
    var travel: app.brokeometer.travel.TravelAgent? = null

    /** For a shop the phone has never seen, ask the AI, then update the waiting alert. */
    private fun askAiIfNew(saved: TransactionEntity, packageName: String, title: String?, text: String?) {
        val unknown = saved.guessSource == GuessSource.NONE.name || saved.guessSource == GuessSource.KEYWORD.name
        if (!unknown || !ai.ready) return
        scope.launch {
            val outcome = ai.ask(saved)
            val answer = outcome.answer
            val updated = answer?.let { repository.applyAiGuess(saved.id, it.subcategoryId, it.confidence) }
            if (updated != null) {
                val label = repository.catalog().label(updated.subcategoryId)
                notifier.showPending(updated, label)
                repository.log(packageName, title, text, "AI guess: ${label ?: "?"}")
            } else if (answer == null) {
                repository.log(packageName, title, text, "AI could not help: ${outcome.note}")
            }
        }
    }

    suspend fun onBankAlert(
        packageName: String,
        alertKey: String,
        postedAt: Long,
        title: String?,
        text: String?,
        source: String = TxSource.BANK,
        /** True when re-reading alerts that were already in the shade, where repeats are expected. */
        replay: Boolean = false,
    ): Boolean {
        val starling = packageName.contains("starling", ignoreCase = true) ||
            (source == TxSource.TEST && title?.trim()?.startsWith(StarlingParser.BANK, ignoreCase = true) == true)
        if (starling) {
            when (val result = StarlingParser.parse(title, text)) {
                is StarlingResult.Ignored -> if (!replay) repository.log(packageName, title, text, "Skipped: ${result.reason}")
                is StarlingResult.MoneyIn -> onMoneyIn(result.money, packageName, alertKey, postedAt, title, text, replay)
            }
            return false
        }
        HsbcParser.parseMoneyIn(title, text)?.let { moneyIn ->
            onMoneyIn(moneyIn, packageName, alertKey, postedAt, title, text, replay)
            return false
        }
        when (val result = HsbcParser.parse(title, text)) {
            is ParseResult.Ignored -> {
                if (!replay) repository.log(packageName, title, text, "Skipped: ${result.reason}")
                return false
            }
            is ParseResult.Payment -> {
                val t = travel
                if (t != null && t.isOysterTopUp(result.payment.merchantKey, result.payment.merchantName)) {
                    // Trips are counted one by one, so the top-up itself isn't spending: it moves money to Oyster.
                    money.onTopUp(result.payment, HsbcParser.BANK, postedAt, "$alertKey|$postedAt")
                    if (!replay) repository.log(packageName, title, text, "Oyster top-up: noted on Money in, not counted, your trips count instead")
                    return false
                }
                if (money.onMoneyOut(result.payment, HsbcParser.BANK, postedAt, "$alertKey|$postedAt")) {
                    // Money to one of your own accounts moves money, it doesn't spend it.
                    if (!replay) repository.log(packageName, title, text, "Transfer to your own account: noted on Money in, not a payment")
                    return false
                }
                val saved = repository.capture(result.payment, postedAt, "$alertKey|$postedAt", source)
                if (saved == null) {
                    if (!replay) repository.log(packageName, title, text, "Already saved")
                } else {
                    val label = repository.catalog().label(saved.subcategoryId)
                    repository.log(packageName, title, text, "Saved, waiting for you" + (label?.let { " ($it)" } ?: ""))
                    notifier.showPending(saved, label)
                    if (t != null && !replay && t.isTravelCharge(saved.merchantKey, saved.merchantName)) {
                        scope.launch {
                            val note = runCatching { t.onTravelCharge(saved) }.getOrNull()
                            if (note != null) {
                                repository.dao.transaction(saved.id)?.let { repository.dao.updateTransaction(it.copy(note = note)) }
                                repository.log(packageName, title, text, note)
                            }
                        }
                    }
                    if (!replay) askAiIfNew(saved, packageName, title, text)
                    // Card payments happen where the phone is. Look it up after the alert is out.
                    if (prefs.saveLocation.value && !replay) {
                        scope.launch {
                            location.current()?.let { fix ->
                                repository.dao.setLocation(saved.id, fix.latitude, fix.longitude, fix.accuracy)
                            }
                        }
                    }
                }
                // Only clear the bank's alert when ours can be seen, so a payment never goes unnoticed.
                return notifier.canPost()
            }
        }
    }

    /** Money in at HSBC or Starling: a transfer from yourself is just noted; from anyone else, you're asked. */
    private suspend fun onMoneyIn(
        moneyIn: ParsedMoneyIn,
        packageName: String,
        alertKey: String,
        postedAt: Long,
        title: String?,
        text: String?,
        replay: Boolean,
    ) {
        val saved = money.onMoneyIn(moneyIn, postedAt, "${moneyIn.bank.lowercase()}|$alertKey|$postedAt")
        if (saved == null) {
            if (!replay) repository.log(packageName, title, text, "Money in: already noted")
            return
        }
        if (saved.kind == app.brokeometer.data.MoveKind.TRANSFER) {
            repository.log(packageName, title, text, "Money in: a transfer from your own account" + (if (saved.pairId != null) ", matched" else ""))
        } else {
            repository.log(packageName, title, text, "Money in: asking if it pays you back")
            notifier.showMoneyIn(saved)
        }
    }

    /**
     * An SMS from ICICI Bank (India), seen through the messaging app's notification. Only money going
     * out is saved. The SMS notification itself is left alone.
     */
    suspend fun onIciciMessage(
        packageName: String,
        postedAt: Long,
        sender: String?,
        text: String?,
        source: String = TxSource.BANK,
        replay: Boolean = false,
    ) {
        when (val result = IciciParser.parse(text)) {
            is ParseResult.Ignored ->
                if (!replay) repository.log(packageName, sender, text, "Skipped: ${result.reason}")
            is ParseResult.Payment -> {
                val p = result.payment
                val day = p.day
                val occurredAt = if (day == null || day == Dates.localDate(postedAt)) postedAt else Dates.middayMillis(day)
                val key = p.reference?.let { "icici|$it" }
                    ?: "icici|${p.account}|${day ?: Dates.localDate(postedAt)}|${p.amountMinor}|${p.merchantKey}"
                val saved = repository.capture(p, occurredAt, key, source)
                if (saved == null) {
                    if (!replay) repository.log(packageName, sender, text, "Already saved")
                } else {
                    val label = repository.catalog().label(saved.subcategoryId)
                    repository.log(packageName, sender, text, "Saved, waiting for you" + (label?.let { " ($it)" } ?: ""))
                    notifier.showPending(saved, label)
                    if (!replay) askAiIfNew(saved, packageName, sender, text)
                }
            }
        }
    }
}
