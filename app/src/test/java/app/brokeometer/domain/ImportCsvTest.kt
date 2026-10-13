package app.brokeometer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ImportCsvTest {

    private val header = "kind,date,end_date,amount,currency,category,subcategory,note"

    @Test
    fun readsSpendsTripsAndQuotedNotes() {
        val text = "﻿$header\r\n" +
            "spend,2025-03-14,,264.00,INR,Travel,Visa,UK VIsa scheduling payment\r\n" +
            "spend,2026-01-02,,\"1,234.50\",gbp,Food,Lunch,\"Said \"\"hi\"\", then left\"\r\n" +
            "away,2026-04-01,2026-04-12,,,,,Japan\r\n" +
            "\r\n"
        val bundle = ImportCsv.parse(text)
        assertEquals(2, bundle.spends.size)
        assertEquals(26400L, bundle.spends[0].amountMinor)
        assertEquals("INR", bundle.spends[0].currency)
        assertEquals("UK VIsa scheduling payment", bundle.spends[0].note)
        assertEquals(123450L, bundle.spends[1].amountMinor)
        assertEquals("GBP", bundle.spends[1].currency)
        assertEquals("Said \"hi\", then left", bundle.spends[1].note)
        assertEquals(listOf(ImportedAway(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 12), "Japan")), bundle.away)
        assertTrue(bundle.problems.isEmpty())
    }

    @Test
    fun badLinesAreReportedNotGuessed() {
        val bundle = ImportCsv.parse(
            "$header\nspend,2026-13-01,,5.00,GBP,Food,Lunch,\nspend,2026-01-01,,0,GBP,Food,Lunch,\n" +
                "spend,2026-01-01,,5,GBP,,Lunch,\nfly,2026-01-01,,5,GBP,Food,Lunch,\n"
        )
        assertTrue(bundle.spends.isEmpty())
        assertEquals(listOf("line 2: date", "line 3: amount", "line 4: category", "line 5: kind"), bundle.problems)
    }

    @Test(expected = ImportFormatException::class)
    fun otherFilesAreRejected() {
        ImportCsv.parse("Date,Description,Amount\n2026-01-01,Tesco,4.00\n")
    }

    @Test
    fun awayDaysAreCountedInsideRanges() {
        val away = AwayDays(
            listOf(
                LocalDate.of(2026, 4, 1) to LocalDate.of(2026, 4, 12),
                LocalDate.of(2026, 10, 5) to LocalDate.of(2026, 9, 14),
            )
        )
        assertEquals(12, away.countIn(YearMonth.of(2026, 4)))
        assertEquals(17, away.countIn(YearMonth.of(2026, 9)))
        assertEquals(5, away.countIn(YearMonth.of(2026, 10)))
        assertTrue(LocalDate.of(2026, 4, 4) in away)
        assertFalse(LocalDate.of(2026, 4, 13) in away)
    }
}
