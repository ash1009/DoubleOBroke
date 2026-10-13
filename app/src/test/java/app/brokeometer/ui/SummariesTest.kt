package app.brokeometer.ui

import app.brokeometer.data.Catalog
import app.brokeometer.data.CategoryEntity
import app.brokeometer.data.DayTotal
import app.brokeometer.data.SubcategoryEntity
import app.brokeometer.data.TransactionEntity
import app.brokeometer.domain.AwayDays
import app.brokeometer.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SummariesTest {

    private val catalog = Catalog(
        categories = listOf(CategoryEntity(1, "Food", 0, 0), CategoryEntity(2, "Travel", 1, 1)),
        subcategories = listOf(
            SubcategoryEntity(10, 1, "Lunch", 0), SubcategoryEntity(11, 1, "Dinner", 1),
            SubcategoryEntity(20, 2, "Bus", 0),
        ),
    )

    private fun entry(id: Long, day: String, pence: Long, sub: Long, currency: String = "GBP") = TransactionEntity(
        id = id, amountMinor = pence, currency = currency, merchantName = "M", merchantKey = "M", descriptor = "",
        place = "", account = "", occurredAt = 0, day = day, subcategoryId = sub, status = "CONFIRMED",
        source = "BANK", sourceKey = null, guessSource = "USER", guessConfidence = 1f, note = "", createdAt = 0,
    )

    @Test
    fun totalsAverageAndProjectionForTheCurrentMonth() {
        val entries = listOf(
            entry(1, "2026-10-06", 1300, 11), entry(2, "2026-10-06", 600, 10),
            entry(3, "2026-10-07", 1550, 10), entry(4, "2026-10-07", 175, 20),
        )
        val s = summarise(entries, catalog, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 8))
        assertEquals(3625L, s.totalMinor)
        assertEquals(8, s.daysCounted)
        assertEquals(3625L / 8, s.dailyAverageMinor)
        assertEquals(3625L + 3625L / 8 * 23, s.projectedMinor)
        assertEquals(31, s.perDay.size)
        assertEquals(1900L, s.perDay[5])
        assertEquals(1725L, s.perDay[6])
        assertEquals("Food", s.byCategory.first().category.name)
        assertEquals(3450L, s.byCategory.first().totalMinor)
        assertEquals(2, s.byCategory.first().subs.size)
    }

    @Test
    fun aFinishedMonthUsesAllItsDaysAndHasNoProjection() {
        val s = summarise(
            listOf(entry(1, "2026-09-10", 3000, 10)), catalog, YearMonth.of(2026, 9), LocalDate.of(2026, 10, 8)
        )
        assertEquals(30, s.daysCounted)
        assertEquals(100L, s.dailyAverageMinor)
        assertNull(s.projectedMinor)
    }

    @Test
    fun otherCurrenciesAreKeptApartFromPounds() {
        val s = summarise(
            listOf(entry(1, "2026-10-01", 1000, 10), entry(2, "2026-10-01", 50000, 10, currency = "INR")),
            catalog, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 8),
        )
        assertEquals(1000L, s.totalMinor)
        assertEquals(50000L, s.otherCurrencies["INR"])
    }

    private fun days(month: String, perDayPence: Long, count: Int, currency: String = "GBP") =
        (1..count).map { DayTotal("$month-%02d".format(it), currency, perDayPence) }

    @Test
    fun usualMonthIsTheMiddleDailyAverageTimesTheDaysAtHome() {
        val totals = days("2026-04", 300, 30) + days("2026-05", 400, 31) + days("2026-06", 500, 30) +
            days("2026-07", 1000, 6) + // only six days at home, so July is not used
            days("2026-10", 9999, 3) + // the current month is never used
            days("2025-06", 99999, 30, currency = "INR")
        val away = AwayDays(listOf(LocalDate.of(2026, 7, 7) to LocalDate.of(2026, 7, 31)))
        // Middle of 300, 400 and 500 pence a day, times 31 October days.
        assertEquals(400L * 31, usualMonthMinor(totals, YearMonth.of(2026, 10), away))
        // Five October days away leaves 26 days to spend at home.
        val withOctoberTrip = AwayDays(
            listOf(LocalDate.of(2026, 7, 7) to LocalDate.of(2026, 7, 31), LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 5))
        )
        assertEquals(400L * 26, usualMonthMinor(totals, YearMonth.of(2026, 10), withOctoberTrip))
        assertNull(usualMonthMinor(days("2026-10", 500, 3), YearMonth.of(2026, 10)))
    }

    @Test
    fun tripDaysAreLeftOutOfTheAverageButNotTheTotal() {
        val away = AwayDays(listOf(LocalDate.of(2026, 4, 1) to LocalDate.of(2026, 4, 12)))
        val s = summarise(
            listOf(entry(1, "2026-04-04", 499, 10), entry(2, "2026-04-20", 1800, 10)),
            catalog, YearMonth.of(2026, 4), LocalDate.of(2026, 10, 8), away,
        )
        assertEquals(2299L, s.totalMinor)
        assertEquals(12, s.awayDays)
        assertEquals(18, s.daysCounted)
        assertEquals(100L, s.dailyAverageMinor)
        assertEquals((0..11).toSet(), s.awayIndexes)
    }

    @Test
    fun theCurrentMonthProjectsOnlyOverDaysAtHome() {
        val away = AwayDays(listOf(LocalDate.of(2026, 9, 14) to LocalDate.of(2026, 10, 5)))
        val s = summarise(
            listOf(entry(1, "2026-10-06", 900, 10), entry(2, "2026-10-08", 300, 10)),
            catalog, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 8), away,
        )
        assertEquals(3, s.daysCounted)
        assertEquals(400L, s.dailyAverageMinor)
        assertEquals(1200L + 400L * 23, s.projectedMinor)
    }

    @Test
    fun aRupeeMonthIsShownInRupees() {
        val s = summarise(
            listOf(entry(1, "2025-05-01", 24000, 10, "INR"), entry(2, "2025-05-02", 31500, 11, "INR")),
            catalog, YearMonth.of(2025, 5), LocalDate.of(2026, 10, 8),
        )
        assertEquals("INR", s.currency)
        assertEquals(55500L, s.totalMinor)
        assertTrue(s.otherCurrencies.isEmpty())
    }

    @Test
    fun zones() {
        assertEquals(MeterZone.LOADED, zoneFor(0.1f))
        assertEquals(MeterZone.COMFORTABLE, zoneFor(0.5f))
        assertEquals(MeterZone.CAREFUL, zoneFor(0.9f))
        assertEquals(MeterZone.BROKE, zoneFor(1.2f))
    }

    @Test
    fun moneyFormatting() {
        assertEquals("£1,234.50", Money.format(123450))
        assertEquals("£0.05", Money.format(5))
        assertEquals("₹50,800.60", Money.format(5080060, "INR"))
        assertEquals("£1,235", Money.formatWhole(123450))
        assertEquals(1250L, Money.parseToMinor("12.5"))
        assertEquals(1555L, Money.parseToMinor("£15.55"))
        assertNull(Money.parseToMinor("abc"))
        assertNull(Money.parseToMinor("0"))
        assertEquals("12.50", Money.plain(1250))
    }
}
