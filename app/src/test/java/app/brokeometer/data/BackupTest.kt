package app.brokeometer.data

import app.brokeometer.domain.ImportFormatException
import app.brokeometer.insights.Goals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class BackupTest {

    private fun tx(
        id: Long,
        sub: Long?,
        amount: Long,
        createdAt: Long,
        sourceKey: String? = null,
        source: String = TxSource.MANUAL,
        lat: Double? = null,
    ) = TransactionEntity(
        id = id,
        amountMinor = amount,
        currency = "GBP",
        merchantName = "Shop $id",
        merchantKey = "SHOP$id",
        descriptor = "",
        place = "London",
        account = "",
        occurredAt = 1_760_000_000_000 + id,
        day = "2026-10-0${id % 9 + 1}",
        subcategoryId = sub,
        status = TxStatus.CONFIRMED,
        source = source,
        sourceKey = sourceKey,
        guessSource = "USER",
        guessConfidence = 0.75f,
        note = if (id == 1L) "with \"quotes\", commas\nand a new line" else "",
        createdAt = createdAt,
        latitude = lat,
        longitude = lat?.let { -0.25 },
        locationAccuracy = lat?.let { 8.5f },
    )

    private val backup = BackupData(
        exportedAt = 1_760_100_000_000,
        appVersion = "0.1.23",
        categories = listOf(CategoryEntity(5, "Food", 0, 0), CategoryEntity(6, "Pets", 7, 7)),
        subcategories = listOf(
            SubcategoryEntity(50, 5, "Lunch", 0, code = "food.lunch"),
            SubcategoryEntity(51, 5, "Brunch", 1, code = null),
            SubcategoryEntity(60, 6, "Others", 0, code = null),
        ),
        transactions = listOf(
            tx(1, 50, 1555, 100, sourceKey = "hsbc|a1", source = TxSource.BANK, lat = 51.556),
            tx(2, 51, 899, 200),
            tx(3, 60, 1200, 300),
            tx(4, null, 500, 400, source = TxSource.TEST),
        ),
        away = listOf(AwayRangeEntity(startDay = "2026-04-01", endDay = "2026-04-12", label = "Japan")),
        settings = BackupSettings(
            username = "Alex",
            hiddenRegulars = setOf("51|SHOP2|9"),
            goals = Goals(
                savingsMinor = 800_000,
                savingsAt = 1_760_000_000_000,
                lastUntil = LocalDate.of(2027, 6, 30),
                monthlyLimitMinor = 70_000,
                noSpendTarget = 6,
                categoryCaps = mapOf(5L to 30_000L, 6L to 2_000L),
            ),
        ),
        avatar = "aGVsbG8=",
    )

    @Test
    fun writesAndReadsBackEverything() {
        val text = BackupCodec.write(backup)
        assertTrue(BackupCodec.looksLikeBackup(text))
        assertEquals(backup, BackupCodec.read(text))
    }

    @Test
    fun theEncryptedVaultTravelsWithTheBackup() {
        val withVault = backup.copy(vault = "{\"format\":\"double-o-broke-vault\",\"salt\":\"abc\"}")
        assertEquals(withVault, BackupCodec.read(BackupCodec.write(withVault)))
    }

    @Test
    fun notesTravelWithTheBackup() {
        val notes = listOf(
            app.brokeometer.notes.NoteEntity(title = "Shop", items = "[x] milk\n[ ] bread", checklist = true, color = "GREEN", pinned = true, createdAt = 10, updatedAt = 20),
            app.brokeometer.notes.NoteEntity(title = "Trip", body = "# Pack\n**passport**", background = "DOTS", createdAt = 30, updatedAt = 40, keepKey = "keep|1|Trip"),
        )
        val withNotes = backup.copy(notes = app.brokeometer.notes.NotesBackup.write(notes))
        val back = BackupCodec.read(BackupCodec.write(withNotes))
        assertEquals(notes, app.brokeometer.notes.NotesBackup.read(back.notes!!))
    }

    @Test
    fun emptyGoalsAndNoPictureSurvive() {
        val plain = backup.copy(settings = BackupSettings(), avatar = null)
        assertEquals(plain, BackupCodec.read(BackupCodec.write(plain)))
    }

    @Test
    fun tellsBackupsFromSheets() {
        assertFalse(BackupCodec.looksLikeBackup("kind,date,end_date,amount\nspend,2026-01-05,,1.60"))
        assertTrue(BackupCodec.looksLikeBackup("﻿  {\"format\":\"broke-o-meter-backup\"}"))
    }

    @Test
    fun refusesOtherJsonAndNewerBackups() {
        expectFormatError("{\"hello\": 1}")
        expectFormatError("{ not json")
        expectFormatError("{\"format\":\"broke-o-meter-backup\",\"version\":99}")
    }

    private fun expectFormatError(text: String) {
        try {
            BackupCodec.read(text)
            fail("should not read: $text")
        } catch (e: ImportFormatException) {
            // expected
        }
    }

    private val phone = Catalog(
        categories = listOf(CategoryEntity(1, "Food", 0, 0), CategoryEntity(2, "Travel", 1, 1)),
        subcategories = listOf(
            SubcategoryEntity(10, 1, "Lunch", 0, code = "food.lunch"),
            SubcategoryEntity(11, 1, "Others", 1, code = "food.others"),
            SubcategoryEntity(20, 2, "Bus", 0, code = "travel.bus"),
        ),
    )

    @Test
    fun matchesCategoriesAndSkipsWhatThePhoneHas() {
        val already = setOf(RestorePlanner.identity(backup.transactions[0]))
        val plan = RestorePlanner.plan(backup, phone, already, emptyList(), Goals())

        assertEquals(mapOf(50L to 10L), plan.subMap)
        assertEquals(listOf(51L, 60L), plan.missingSubs.map { it.id })
        assertEquals(listOf("Pets"), plan.newCategories)
        // The bank entry is already there and the test entry is never restored.
        assertEquals(listOf(2L, 3L), plan.rows.map { it.id })
        assertEquals(1, plan.alreadyThere)
        assertEquals(1, plan.away.size)
        assertTrue(plan.restoresGoals)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun restoringTwiceAddsNothing() {
        val everything = backup.transactions.map { RestorePlanner.identity(it) }.toSet()
        val phoneWithAll = phone.copy(
            categories = phone.categories + CategoryEntity(3, "Pets", 2, 7),
            subcategories = phone.subcategories +
                SubcategoryEntity(12, 1, "Brunch", 2) + SubcategoryEntity(30, 3, "Others", 0),
        )
        val plan = RestorePlanner.plan(
            backup, phoneWithAll, everything, backup.away, Goals(monthlyLimitMinor = 50_000),
        )
        assertTrue(plan.isEmpty)
        assertEquals(mapOf(50L to 10L, 51L to 12L, 60L to 30L), plan.subMap)
        assertEquals(3, plan.alreadyThere)
        assertFalse(plan.restoresGoals)
    }

    @Test
    fun identityUsesTheAlertKeyWhenThereIsOne() {
        assertEquals("hsbc|a1", RestorePlanner.identity(backup.transactions[0]))
        assertEquals("row|200|1760000000002|899|GBP", RestorePlanner.identity(backup.transactions[1]))
    }

    @Test
    fun capsAndHiddenRegularsFollowTheNewIds() {
        val phoneCategories = phone.categories + CategoryEntity(9, "Pets", 2, 7)
        assertEquals(
            mapOf(1L to 30_000L, 9L to 2_000L),
            RestorePlanner.mapCaps(backup.settings.goals.categoryCaps, backup.categories, phoneCategories),
        )
        assertEquals(setOf("12|SHOP2|9"), RestorePlanner.mapHidden(setOf("51|SHOP2|9", "77|X|1"), mapOf(51L to 12L)))
    }
}
