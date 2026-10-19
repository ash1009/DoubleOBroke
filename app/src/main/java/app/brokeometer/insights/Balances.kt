package app.brokeometer.insights

/** The bank and Oyster balances typed in once, and when. */
data class BalanceStart(val bankMinor: Long, val oysterMinor: Long, val at: Long)

/** What the bank accounts (HSBC and Starling together) and the Oyster card hold now. */
data class Balances(val bankMinor: Long, val oysterMinor: Long) {
    /** Bank and Oyster together: what there really is to spend. */
    val totalMinor: Long get() = bankMinor + oysterMinor
}

/** A payment, as the balances see it: where it came from ("BANK", "TRAVEL", ...) and what really left. */
data class BalancePayment(val source: String, val currency: String, val paidMinor: Long, val at: Long)

/**
 * Money that isn't a payment, as the balances see it. [paired] is a transfer seen at both banks,
 * which only moves money between them.
 */
data class BalanceMove(val direction: String, val kind: String, val currency: String, val amountMinor: Long, val at: Long, val paired: Boolean)

/**
 * Keeps the bank and Oyster balances up to date from what happened after they were typed in:
 * - card and bank payments come off the bank (all of what was paid, even if friends paid some back);
 * - trips found from the phone's travel come off Oyster (a trip paid by card is the card's charge instead);
 * - money in goes on the bank, except a transfer between the two banks, which changes neither;
 * - an Oyster top-up moves money from the bank to Oyster;
 * - money sent to, or coming from, an account the app doesn't see (a transfer seen at one bank only)
 *   leaves or reaches the bank.
 * Payments added by hand don't change either, and only pounds count.
 */
object BalanceMath {

    const val BANK = "BANK"
    const val TRAVEL = "TRAVEL"
    const val IN = "IN"
    const val OUT = "OUT"
    const val TRANSFER = "TRANSFER"
    const val TOPUP = "TOPUP"

    fun now(start: BalanceStart, payments: List<BalancePayment>, moves: List<BalanceMove>): Balances {
        var bank = start.bankMinor
        var oyster = start.oysterMinor
        for (p in payments) {
            if (p.at <= start.at || p.currency != "GBP") continue
            when (p.source) {
                BANK -> bank -= p.paidMinor
                TRAVEL -> oyster -= p.paidMinor
            }
        }
        for (m in moves) {
            if (m.at <= start.at || m.currency != "GBP") continue
            when {
                m.kind == TOPUP && m.direction == OUT -> {
                    bank -= m.amountMinor
                    oyster += m.amountMinor
                }
                m.kind == TRANSFER && m.paired -> Unit
                m.direction == IN -> bank += m.amountMinor
                m.direction == OUT -> bank -= m.amountMinor
            }
        }
        return Balances(bank, oyster)
    }
}
