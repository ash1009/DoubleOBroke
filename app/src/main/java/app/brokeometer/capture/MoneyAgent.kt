package app.brokeometer.capture

import androidx.room.withTransaction
import app.brokeometer.data.AppDatabase
import app.brokeometer.data.MoneyMoveEntity
import app.brokeometer.data.MoveKind
import app.brokeometer.data.Prefs
import app.brokeometer.data.Repository
import app.brokeometer.data.TransactionEntity
import app.brokeometer.domain.HsbcParser
import app.brokeometer.domain.InSide
import app.brokeometer.domain.MoneyText
import app.brokeometer.domain.OutSide
import app.brokeometer.domain.ParsedMoneyIn
import app.brokeometer.domain.ParsedPayment
import app.brokeometer.domain.TransferMatch
import app.brokeometer.util.Dates
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Money that isn't spending: money coming in, transfers between your own accounts, and friends
 * paying you back. A transfer is never a payment; money paid back makes the payment it was for
 * count only your share.
 */
class MoneyAgent(
    private val db: AppDatabase,
    private val repository: Repository,
    private val prefs: Prefs,
    private val notifier: Notifier,
) {
    private val dao get() = repository.dao

    /** Both banks' alerts for one transfer arrive within seconds; one at a time keeps the pairing right. */
    private val lock = Mutex()

    private val owner: String get() = prefs.username.value
    private val learned: Set<String> get() = prefs.ownNames.value

    /**
     * A Money Out alert, before it is saved as a payment. Returns true when it is a transfer to one of
     * your own accounts: then it is noted here and must not become a payment.
     */
    suspend fun onMoneyOut(payment: ParsedPayment, bank: String, at: Long, sourceKey: String): Boolean = lock.withLock {
        val text = payment.descriptor
        val toSelf = MoneyText.namesOwner(text, owner, learned)
        val out = OutSide(payment.amountMinor, payment.currency, at, bank, text)
        val partner = nearby(at).firstOrNull { m ->
            m.direction == MoneyMoveEntity.IN && m.pairId == null &&
                (m.kind == MoveKind.UNSORTED || m.kind == MoveKind.TRANSFER) &&
                // Named as you: the money in only has to be yours or share the reference.
                // Not named: only a shared reference proves it, never an equal amount alone.
                (if (toSelf) TransferMatch.isPair(out, inSide(m), owner, learned)
                else TransferMatch.sameMoney(out, inSide(m)) && MoneyText.sharesReference(text, m.descriptor))
        }
        if (!toSelf && partner == null) return@withLock false
        db.withTransaction {
            val id = dao.insertMove(
                MoneyMoveEntity(
                    direction = MoneyMoveEntity.OUT,
                    kind = MoveKind.TRANSFER,
                    amountMinor = payment.amountMinor,
                    currency = payment.currency,
                    counterparty = MoneyText.nameOf(text).ifBlank { payment.merchantName },
                    reference = MoneyText.referencesOf(text).firstOrNull().orEmpty(),
                    descriptor = text,
                    bank = bank,
                    account = payment.account,
                    occurredAt = at,
                    day = Dates.dayOf(at),
                    pairId = partner?.id,
                    sourceKey = sourceKey,
                    createdAt = System.currentTimeMillis(),
                )
            )
            if (id > 0 && partner != null) {
                dao.updateMove(partner.copy(kind = MoveKind.TRANSFER, pairId = id))
                notifier.cancelMoneyIn(partner.id)
                learnFrom(partner.counterparty, text)
            }
        }
        true
    }

    /**
     * Money in at either bank. Saved as money in, paired with the other side of a transfer when there is
     * one, and otherwise left for you to say what it was. Returns the saved row, or null if it was saved before.
     */
    suspend fun onMoneyIn(money: ParsedMoneyIn, at: Long, sourceKey: String): MoneyMoveEntity? = lock.withLock {
        val inn = InSide(money.amountMinor, money.currency, at, money.bank, money.descriptor)
        val fromSelf = MoneyText.namesOwner(money.descriptor, owner, learned)
        val moves = nearby(at)
        val outMove = moves.firstOrNull { m ->
            m.direction == MoneyMoveEntity.OUT && m.kind == MoveKind.TRANSFER && m.pairId == null && TransferMatch.isPair(outSide(m), inn, owner, learned)
        }
        // A payment that was really a transfer: only when the reference matches or it names you.
        val outPayment = if (outMove != null) null else dao.bankPaymentsBetween(at - TransferMatch.WINDOW_MS, at + TransferMatch.WINDOW_MS)
            .firstOrNull { tx ->
                tx.paidMinor == null && TransferMatch.paymentWasTransfer(paymentSide(tx), inn, owner, learned)
            }
        val kind = if (fromSelf || outMove != null || outPayment != null) MoveKind.TRANSFER else MoveKind.UNSORTED

        db.withTransaction {
            val id = dao.insertMove(
                MoneyMoveEntity(
                    direction = MoneyMoveEntity.IN,
                    kind = kind,
                    amountMinor = money.amountMinor,
                    currency = money.currency,
                    counterparty = money.from,
                    reference = money.reference,
                    descriptor = money.descriptor,
                    bank = money.bank,
                    account = money.account,
                    occurredAt = at,
                    day = Dates.dayOf(at),
                    pairId = outMove?.id,
                    sourceKey = sourceKey,
                    createdAt = System.currentTimeMillis(),
                )
            )
            if (id <= 0) return@withTransaction null
            if (outMove != null) {
                dao.updateMove(outMove.copy(pairId = id))
                learnFrom(money.from, outMove.descriptor)
            }
            if (outPayment != null) {
                val outId = dao.insertMove(transferFrom(outPayment, pairId = id))
                if (outId > 0) dao.updateMove(dao.move(id)!!.copy(pairId = outId))
                repository.delete(outPayment.id)
                notifier.cancel(outPayment.id)
                learnFrom(money.from, outPayment.descriptor)
            }
            dao.move(id)
        }
    }

    /** An Oyster top-up in a bank alert: money moved from the bank to Oyster, noted on Money in, not spending. */
    suspend fun onTopUp(payment: ParsedPayment, bank: String, at: Long, sourceKey: String) = lock.withLock {
        dao.insertMove(
            MoneyMoveEntity(
                direction = MoneyMoveEntity.OUT,
                kind = MoveKind.TOPUP,
                amountMinor = payment.amountMinor,
                currency = payment.currency,
                counterparty = "Oyster",
                reference = "",
                descriptor = payment.descriptor,
                bank = bank,
                account = payment.account,
                occurredAt = at,
                day = Dates.dayOf(at),
                sourceKey = sourceKey,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /** You said this payment was an Oyster top-up: it moves to Money in as one, and isn't spending. */
    suspend fun markTopUp(txId: Long): Boolean = lock.withLock {
        val tx = dao.transaction(txId) ?: return@withLock false
        db.withTransaction {
            dao.insertMove(
                MoneyMoveEntity(
                    direction = MoneyMoveEntity.OUT,
                    kind = MoveKind.TOPUP,
                    amountMinor = tx.paidMinor ?: tx.amountMinor,
                    currency = tx.currency,
                    counterparty = "Oyster",
                    reference = "",
                    descriptor = tx.descriptor,
                    bank = bankOf(tx),
                    account = tx.account,
                    occurredAt = tx.occurredAt,
                    day = tx.day,
                    sourceKey = "topup|${tx.sourceKey ?: tx.id}",
                    createdAt = System.currentTimeMillis(),
                )
            )
            repository.delete(tx.id)
        }
        notifier.cancel(tx.id)
        true
    }

    /**
     * You said this payment was a deposit you get back (a rent deposit, say): it moves to Money in as one.
     * It still comes off the bank balance, but isn't spending, so it's in no total or average.
     */
    suspend fun markDeposit(txId: Long): Boolean = lock.withLock {
        val tx = dao.transaction(txId) ?: return@withLock false
        db.withTransaction {
            dao.insertMove(
                MoneyMoveEntity(
                    direction = MoneyMoveEntity.OUT,
                    kind = MoveKind.DEPOSIT,
                    amountMinor = tx.paidMinor ?: tx.amountMinor,
                    currency = tx.currency,
                    counterparty = tx.merchantName,
                    reference = "",
                    descriptor = tx.descriptor,
                    bank = bankOf(tx),
                    account = tx.account,
                    occurredAt = tx.occurredAt,
                    day = tx.day,
                    sourceKey = "deposit|${tx.sourceKey ?: tx.id}",
                    createdAt = System.currentTimeMillis(),
                )
            )
            repository.delete(tx.id)
        }
        notifier.cancel(tx.id)
        true
    }

    /** You said this money in came from one of your own accounts. */
    suspend fun markMine(id: Long) = lock.withLock {
        val move = dao.move(id) ?: return@withLock
        prefs.learnOwnName(move.counterparty)
        unlinkPayback(move)
        var updated = move.copy(kind = MoveKind.TRANSFER, paysBackId = null)
        if (move.direction == MoneyMoveEntity.IN && move.pairId == null) {
            val partner = nearby(move.occurredAt).firstOrNull { m ->
                m.direction == MoneyMoveEntity.OUT && m.kind == MoveKind.TRANSFER && m.pairId == null && TransferMatch.sameMoney(outSide(m), inSide(move))
            }
            if (partner != null) {
                dao.updateMove(partner.copy(pairId = move.id))
                updated = updated.copy(pairId = partner.id)
            }
        }
        dao.updateMove(updated)
        notifier.cancelMoneyIn(id)
    }

    /** Money in that is neither a transfer nor paying you back, such as a gift. */
    suspend fun markOther(id: Long) = lock.withLock {
        val move = dao.move(id) ?: return@withLock
        unlinkPayback(move)
        unpair(move)
        dao.updateMove(move.copy(kind = MoveKind.OTHER, paysBackId = null, pairId = null))
        notifier.cancelMoneyIn(id)
    }

    /** Money in paying you back for payment [txId]: that payment then counts only your share. */
    suspend fun linkPayback(id: Long, txId: Long) = lock.withLock {
        val move = dao.move(id) ?: return@withLock
        val tx = dao.transaction(txId) ?: return@withLock
        if (tx.currency != move.currency) return@withLock
        db.withTransaction {
            unlinkPayback(move)
            unpair(move)
            dao.updateMove(move.copy(kind = MoveKind.PAYBACK, paysBackId = txId, pairId = null))
            recomputeShare(txId)
        }
        notifier.cancelMoneyIn(id)
    }

    /** A transfer out (or an Oyster top-up) that was really a payment: it becomes a payment waiting for you again. */
    suspend fun notATransfer(id: Long): TransactionEntity? = lock.withLock {
        val move = dao.move(id) ?: return@withLock null
        if (move.direction != MoneyMoveEntity.OUT) return@withLock null
        val merchant = app.brokeometer.domain.Merchants.fromDescriptor(move.descriptor)
        val parsed = ParsedPayment(
            amountMinor = move.amountMinor,
            currency = move.currency,
            descriptor = move.descriptor,
            merchantName = merchant.name,
            merchantKey = merchant.key,
            place = merchant.place,
            account = move.account,
        )
        val saved = db.withTransaction {
            move.pairId?.let { pid -> dao.move(pid)?.let { dao.updateMove(it.copy(kind = MoveKind.UNSORTED, pairId = null)) } }
            dao.deleteMove(id)
            repository.capture(parsed, move.occurredAt, "again|${move.sourceKey ?: move.id}", app.brokeometer.data.TxSource.BANK)
        }
        saved
    }

    /** The money in rows waiting for you, for their notifications. */
    suspend fun move(id: Long): MoneyMoveEntity? = dao.move(id)

    /** Payments the money in [id] could be paying back, newest first, the most likely ones on top. */
    suspend fun paybackChoices(id: Long): List<TransactionEntity> {
        val move = dao.move(id) ?: return emptyList()
        val since = move.occurredAt - CHOICE_DAYS * 24 * 60 * 60 * 1000L
        return dao.paymentsSince(move.currency, since)
            .filter { it.occurredAt <= move.occurredAt + 60 * 60 * 1000L && (it.paidMinor ?: it.amountMinor) > 0 }
            .sortedWith(compareByDescending<TransactionEntity> { (it.paidMinor ?: it.amountMinor) >= move.amountMinor }.thenByDescending { it.occurredAt })
    }

    private suspend fun unlinkPayback(move: MoneyMoveEntity) {
        val old = move.paysBackId ?: return
        dao.updateMove(move.copy(paysBackId = null, kind = MoveKind.OTHER))
        recomputeShare(old)
    }

    /** No longer one side of a transfer: the other side stands on its own again. */
    private suspend fun unpair(move: MoneyMoveEntity) {
        val other = move.pairId?.let { dao.move(it) } ?: return
        if (other.pairId == move.id) dao.updateMove(other.copy(pairId = null))
    }

    /** Your share of a payment: what was paid, less everything friends paid back for it. */
    private suspend fun recomputeShare(txId: Long) {
        val tx = dao.transaction(txId) ?: return
        val paid = tx.paidMinor ?: tx.amountMinor
        val back = dao.paybacksFor(txId).filter { it.kind == MoveKind.PAYBACK && it.currency == tx.currency }.sumOf { it.amountMinor }
        val updated = if (back <= 0) tx.copy(amountMinor = paid, paidMinor = null)
        else tx.copy(amountMinor = (paid - back).coerceAtLeast(0), paidMinor = paid)
        if (updated != tx) dao.updateTransaction(updated)
    }

    private suspend fun nearby(at: Long) = dao.movesBetween(at - TransferMatch.WINDOW_MS, at + TransferMatch.WINDOW_MS)

    /** Both names were on one transfer, so both are yours. Single words are never learned. */
    private fun learnFrom(inName: String, outText: String) {
        prefs.learnOwnName(MoneyText.nameOf(inName))
        prefs.learnOwnName(MoneyText.nameOf(outText))
    }

    private fun transferFrom(tx: TransactionEntity, pairId: Long) = MoneyMoveEntity(
        direction = MoneyMoveEntity.OUT,
        kind = MoveKind.TRANSFER,
        amountMinor = tx.amountMinor,
        currency = tx.currency,
        counterparty = MoneyText.nameOf(tx.descriptor).ifBlank { tx.merchantName },
        reference = MoneyText.referencesOf(tx.descriptor).firstOrNull().orEmpty(),
        descriptor = tx.descriptor,
        bank = bankOf(tx),
        account = tx.account,
        occurredAt = tx.occurredAt,
        day = tx.day,
        pairId = pairId,
        sourceKey = "transfer|${tx.sourceKey ?: tx.id}",
        createdAt = System.currentTimeMillis(),
    )

    companion object {
        /** How far back a payment can be for money in to pay it back. */
        const val CHOICE_DAYS = 45L

        fun bankOf(tx: TransactionEntity): String = if (tx.sourceKey?.startsWith("icici|") == true) "ICICI" else HsbcParser.BANK

        fun inSide(m: MoneyMoveEntity) = InSide(m.amountMinor, m.currency, m.occurredAt, m.bank, m.descriptor)
        fun outSide(m: MoneyMoveEntity) = OutSide(m.amountMinor, m.currency, m.occurredAt, m.bank, m.descriptor)
        fun paymentSide(tx: TransactionEntity) = OutSide(tx.amountMinor, tx.currency, tx.occurredAt, bankOf(tx), tx.descriptor)
    }
}
