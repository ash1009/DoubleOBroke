package app.brokeometer.insights

import app.brokeometer.domain.AwayDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class InsightsTest {

    private val today = LocalDate.of(2026, 10, 8)

    private fun spend(day: LocalDate, pence: Long, category: Long = 1, sub: Long = 10, key: String = "", hour: Int? = null) =
        Spend(day, pence, category, sub, key, key.lowercase().replaceFirstChar { it.uppercase() }, hour,
            day.atTime(hour ?: 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli())

    /** £10 every day from April to September, £5 a day so far in October. */
    private fun steady(): List<Spend> {
        val out = ArrayList<Spend>()
        var d = LocalDate.of(2026, 4, 1)
        while (d < today) {
            out += spend(d, if (d.monthValue == 10) 500 else 1000)
            d = d.plusDays(1)
        }
        return out
    }

    @Test
    fun runwayCountsSpendingAfterTheSavingsWereSet() {
        val history = History(steady(), AwayDays.NONE, today)
        val setAt = LocalDate.of(2026, 10, 5).atTime(23, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val r = runway(history, Goals(savingsMinor = 100_000, savingsAt = setAt, lastUntil = today.plusDays(100)))!!
        assertEquals(1000L, r.spentSinceMinor) // 6 and 7 October
        assertEquals(99_000L, r.leftMinor)
        assertEquals(99_000 / (1000 * DAYS_PER_MONTH), r.monthsLeft!!, 0.001)
        // October so far: £35 over 8 days, today included.
        assertEquals(today.plusDays(Math.ceil(99_000 / (3500 / 8.0)).toLong()), r.runsOutOn)
        assertEquals(990L, r.dailyToLastMinor)
    }

    @Test
    fun allowanceSharesWhatIsLeftOverTheDaysAtHome() {
        val away = AwayDays(listOf(LocalDate.of(2026, 10, 20) to LocalDate.of(2026, 10, 29)))
        val history = History(steady(), away, today)
        val a = allowance(history, Goals(monthlyLimitMinor = 30_000), null)!!
        assertEquals(3500L, a.spentMinor)
        // 8th to 31st is 24 days, less 10 away.
        assertEquals(14, a.daysLeft)
        assertEquals((30_000L - 3500) / 14, a.perDayMinor)
        assertNull(allowance(history, Goals(), null))
    }

    @Test
    fun categoriesAreComparedWithAUsualMonthByNow() {
        val history = History(steady(), AwayDays.NONE, today)
        val food = categoryChanges(history, Goals(categoryCaps = mapOf(1L to 20_000L))).single()
        assertEquals(3500L, food.thisMonthMinor)
        assertEquals(8000L, food.usualByNowMinor) // 8 days so far, today included
        assertEquals(31_000L, food.usualMonthMinor)
        assertEquals(20_000L, food.capMinor)
    }

    @Test
    fun noSpendDaysSkipTripsAndStopAtTheFirstSpend() {
        val days = listOf(1, 2, 6).map { spend(LocalDate.of(2026, 10, it), 100) }
        val away = AwayDays(listOf(LocalDate.of(2026, 10, 3) to LocalDate.of(2026, 10, 4)))
        val n = noSpend(History(days, away, today), Goals(noSpendTarget = 8))
        // 5th and 7th were at home with nothing spent.
        assertEquals(2, n.thisMonth)
        assertEquals(5, n.daysSoFar)
        assertEquals(1, n.currentStreak)
        assertEquals(1, n.bestStreak)
        assertEquals(8, n.target)
        val empty = noSpend(History(emptyList(), AwayDays.NONE, today), Goals())
        assertEquals(0, empty.currentStreak)
        assertEquals(0, empty.bestStreak)
    }

    @Test
    fun monthlyChargesAreFoundEvenWithAMissedMonth() {
        val phone = listOf("2026-01-15" to 1000L, "2026-02-15" to 1000L, "2026-03-15" to 1000L, "2026-04-16" to 1000L, "2026-08-15" to 1000L, "2026-10-06" to 900L)
            .map { (d, p) -> spend(LocalDate.parse(d), p, category = 4, sub = 40) }
        val games = listOf("2026-04-07", "2026-06-07", "2026-07-06", "2026-08-07", "2026-09-06")
            .map { spend(LocalDate.parse(it), 499, category = 7, sub = 70) } +
            spend(LocalDate.of(2026, 9, 6), 99, category = 7, sub = 70)
        val lunches = (1..20).map { spend(LocalDate.of(2026, 9, it), 1200, category = 1, sub = 10) }
        val ee = listOf(spend(LocalDate.of(2026, 9, 3), 1000, sub = 41, key = "EE TOPUP"), spend(LocalDate.of(2026, 10, 3), 1000, sub = 41, key = "EE TOPUP"))
        val r = regulars(History(phone + games + lunches + ee, AwayDays.NONE, today))
        assertEquals(listOf(70L, 41L, 40L), r.found.map { it.subcategoryId })
        val game = r.found.first()
        assertEquals(499L, game.typicalMinor)
        assertEquals(LocalDate.of(2026, 10, 6), game.nextDay)
        assertTrue(game.isDue(today))
        val phoneBill = r.found.last()
        assertEquals(LocalDate.of(2026, 11, 6), phoneBill.nextDay)
        assertEquals(1000L, phoneBill.typicalMinor)
        assertEquals("Ee topup", r.found[1].name)
        assertEquals(499L + 1000 + 1000, r.monthlyMinor)
    }

    @Test
    fun aLunchThatHappensToRepeatIsNotRegular() {
        // Lunch most days, and a £13.50 one about once a month, the last at Pasta Remoli.
        val lunches = (0 until 120).map { i ->
            val day = LocalDate.of(2026, 6, 1).plusDays(i.toLong())
            spend(day, 900L + (i % 7) * 37, category = 1, sub = 10)
        }
        val similar = listOf("2026-07-02", "2026-08-01", "2026-09-01").map { spend(LocalDate.parse(it), 1350, category = 1, sub = 10) } +
            spend(LocalDate.of(2026, 10, 1), 1350, category = 1, sub = 10, key = "PASTA REMOLI")
        val r = regulars(History(lunches + similar, AwayDays.NONE, today))
        assertTrue(r.found.isEmpty())
    }

    @Test
    fun hiddenOnesStayHidden() {
        val games = listOf("2026-06-07", "2026-07-06", "2026-08-07", "2026-09-06")
            .map { spend(LocalDate.parse(it), 499, category = 7, sub = 70) }
        val history = History(games, AwayDays.NONE, today)
        val key = regulars(history).found.single().key
        val r = regulars(history, setOf(key))
        assertTrue(r.found.isEmpty())
        assertEquals(1, r.hidden)
    }

    @Test
    fun categoriesOverTheirAverageComeFirst() {
        val base = (0 until 150).map { i -> LocalDate.of(2026, 4, 1).plusDays(i.toLong()) }
            .filter { it < LocalDate.of(2026, 10, 1) }
            .flatMap { listOf(spend(it, 1000, category = 1, sub = 10), spend(it, 300, category = 2, sub = 20), spend(it, 100, category = 3, sub = 30)) }
        val october = (1..7).flatMap { d ->
            val day = LocalDate.of(2026, 10, d)
            listOf(spend(day, 200, category = 1, sub = 10), spend(day, 600, category = 2, sub = 20), spend(day, 150, category = 3, sub = 30))
        }
        val order = categoryChanges(History(base + october, AwayDays.NONE, today), Goals()).map { it.categoryId }
        // Category 2 is well over, 3 a little over, 1 far under.
        assertEquals(listOf(2L, 3L, 1L), order)
    }

    @Test
    fun averagesLeaveTripDaysOut() {
        val history = History(steady(), AwayDays(listOf(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 10))), today)
        val a = averages(history)
        assertEquals(6, a.months)
        // £10 a day at home in every base month.
        assertEquals(1000 * DAYS_PER_MONTH, a.byCategory.getValue(1L).toDouble(), 1.0)
        assertEquals(a.byCategory.getValue(1L), a.bySubcategory.getValue(10L))
    }

    @Test
    fun timesWeekdaysAndPlaces() {
        val list = listOf(
            spend(LocalDate.of(2026, 10, 3), 2000, key = "UBER EATS", hour = 22), // Saturday
            spend(LocalDate.of(2026, 10, 6), 1500, key = "SUBWAY", hour = 13),
            spend(LocalDate.of(2026, 10, 7), 1500, key = "SUBWAY", hour = 13),
        )
        val history = History(list, AwayDays.NONE, today)
        val w = whenYouSpend(history)
        assertEquals(3, w.timedPayments)
        assertEquals(2000L, w.slots.single { it.slot == TimeSlot.LATE }.totalMinor)
        assertEquals(DayOfWeek.SATURDAY, w.busiestDay)
        val places = topPlaces(lastDays(history, 90))
        assertEquals("SUBWAY", places.first().key)
        assertEquals(2, places.first().visits)
        assertEquals(1500L, places.first().averageMinor)
    }

    @Test
    fun yearRecapAndRates() {
        val fx = FxTable(mapOf("INR" to mapOf(LocalDate.of(2025, 3, 7) to 110.0)))
        // Saturday uses Friday's rate.
        assertEquals(1000L, fx.toGbpMinor(110_000, "INR", LocalDate.of(2025, 3, 8)))
        assertNull(fx.toGbpMinor(110_000, "INR", LocalDate.of(2025, 4, 30)))
        assertEquals(500L, fx.toGbpMinor(500, "GBP", LocalDate.of(2025, 4, 30)))

        val history = History(steady(), AwayDays.NONE, today)
        val recap = recap(history, 2026)!!
        assertEquals(listOf(2026), recapYears(history))
        assertEquals(183 * 1000L + 7 * 500L, recap.totalMinor)
        assertTrue(recap.noSpendDays == 0)
        assertEquals(1L, recap.topCategories.first().first)
    }

    @Test
    fun savingsDateStaysPutAtAUsualPace() {
        val history = History(steady(), AwayDays.NONE, today)
        val setAt = LocalDate.of(2026, 10, 5).atTime(23, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val now = setAt + 3 * 86_400_000L
        val r = runway(history, Goals(savingsMinor = 100_000, savingsAt = setAt), now)!!
        // Three days gone, but only £10 spent (one usual day), so two days ahead.
        assertEquals(2.0, r.aheadDays!!, 0.001)
        assertEquals(2, r.aheadWholeDays())
        // £990 left at £10 a day is 99 days from now.
        val expected = java.time.Instant.ofEpochMilli(now + 99 * 86_400_000L).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertEquals(expected, r.lastsUntil)
        // Spending exactly a usual day keeps the date where it was.
        val later = History(steady() + spend(today, 1000), AwayDays.NONE, today)
        assertEquals(expected, runway(later, Goals(savingsMinor = 100_000, savingsAt = setAt), now + 86_400_000L)!!.lastsUntil)
        assertEquals(0, runway(history, Goals(savingsMinor = 100_000, savingsAt = setAt), setAt + 86_400_000L + 3_600_000L)!!.aheadWholeDays())
    }

    @Test
    fun homeWordsPickOutTheNumbers() {
        val history = History(steady(), AwayDays.NONE, today)
        val words = homeWords(
            history = history,
            categories = listOf(CategoryChange(1, 3500, 8000, 30_000, null)),
            noSpend = NoSpend(3, 7, null, 2, 5),
            whenYouSpend = WhenYouSpend(emptyList(), 0, 1400, 3100, null, null),
            money = { "£%.2f".format(java.util.Locale.UK, it / 100.0) },
            category = { if (it == 1L) "Food" else null },
        )
        val text = words.map { s -> s.joinToString("") { it.text } }
        assertEquals(
            listOf(
                "You're spending £4.38 a day, £5.62 less than usual.",
                "Food is £45.00 under your usual so far.",
                "3 no-spend days this month, and you're on a 2-day run.",
                "Weekends cost you £31.00 a day, weekdays £14.00.",
            ),
            text,
        )
        assertTrue(words[0].any { it.strong && it.text == "£4.38 a day" })
    }
}
