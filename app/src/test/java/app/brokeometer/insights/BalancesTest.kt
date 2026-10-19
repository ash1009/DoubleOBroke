package app.brokeometer.insights

import org.junit.Assert.assertEquals
import org.junit.Test

class BalancesTest {

    private val start = BalanceStart(bankMinor = 100_000, oysterMinor = 2_000, at = 1_000L)

    private fun pay(source: String, pence: Long, at: Long = 2_000L, currency: String = "GBP") = BalancePayment(source, currency, pence, at)
    private fun move(direction: String, kind: String, pence: Long, paired: Boolean = false, at: Long = 2_000L) =
        BalanceMove(direction, kind, "GBP", pence, at, paired)

    @Test
    fun cardPaymentsComeOffTheBankAndTripsOffOyster() {
        val b = BalanceMath.now(start, listOf(pay("BANK", 1_250), pay("TRAVEL", 360), pay("TRAVEL", 175)), emptyList())
        assertEquals(98_750, b.bankMinor)
        assertEquals(1_465, b.oysterMinor)
        assertEquals(100_215, b.totalMinor)
    }

    @Test
    fun aTopUpMovesMoneyFromTheBankToOyster() {
        val b = BalanceMath.now(start, emptyList(), listOf(move("OUT", "TOPUP", 2_000)))
        assertEquals(98_000, b.bankMinor)
        assertEquals(4_000, b.oysterMinor)
        assertEquals(start.bankMinor + start.oysterMinor, b.totalMinor)
    }

    @Test
    fun moneyInGoesOnTheBankButTransfersBetweenTheBanksChangeNothing() {
        val moves = listOf(
            move("IN", "PAYBACK", 2_000),
            move("IN", "UNSORTED", 500),
            move("IN", "TRANSFER", 10_000, paired = true),
            move("OUT", "TRANSFER", 10_000, paired = true),
        )
        assertEquals(102_500, BalanceMath.now(start, emptyList(), moves).bankMinor)
    }

    @Test
    fun aTransferSeenAtOneBankOnlyLeavesOrReachesTheBank() {
        assertEquals(95_000, BalanceMath.now(start, emptyList(), listOf(move("OUT", "TRANSFER", 5_000))).bankMinor)
        assertEquals(105_000, BalanceMath.now(start, emptyList(), listOf(move("IN", "TRANSFER", 5_000))).bankMinor)
    }

    @Test
    fun onlyWhatHappenedAfterTheBalancesWereTypedCounts() {
        val b = BalanceMath.now(
            start,
            listOf(pay("BANK", 1_000, at = 500L), pay("MANUAL", 700), pay("BANK", 900, currency = "EUR")),
            listOf(move("IN", "OTHER", 3_000, at = 1_000L)),
        )
        assertEquals(start.bankMinor, b.bankMinor)
        assertEquals(start.oysterMinor, b.oysterMinor)
    }
}
