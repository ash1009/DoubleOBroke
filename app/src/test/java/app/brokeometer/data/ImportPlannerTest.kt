package app.brokeometer.data

import app.brokeometer.domain.ImportCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {

    private val catalog = Catalog(
        categories = listOf(
            CategoryEntity(1, "Food", 0, 0),
            CategoryEntity(2, "Grocery", 1, 1),
            CategoryEntity(3, "Entertainment", 2, 2),
        ),
        subcategories = listOf(
            SubcategoryEntity(10, 1, "Lunch", 0, code = "food.lunch"),
            SubcategoryEntity(20, 2, "Others", 0, code = "grocery.others"),
            SubcategoryEntity(30, 3, "Subscriptions", 0, code = "entertainment.subscriptions"),
            SubcategoryEntity(31, 3, "Concerts", 1, code = null),
        ),
    )

    private val file = """
        kind,date,end_date,amount,currency,category,subcategory,note
        spend,2026-01-05,,1.60,GBP,Food,Lunch,
        spend,2026-01-05,,1.60,GBP,Food,Lunch,
        spend,2026-01-06,,4.99,GBP,Entertainment,Subscriptions,
        spend,2026-01-07,,30.00,GBP,Entertainment,Concerts,Gig
        spend,2026-01-07,,3.00,GBP,Snacks,Crisps,
        spend,2026-10-07,,12.00,GBP,Grocery,Others,
        away,2026-04-01,2026-04-12,,,,,Japan
        away,2026-04-01,2026-04-12,,,,,Japan again
    """.trimIndent()

    @Test
    fun mapsCategoriesAndKeepsRepeatsApart() {
        val plan = ImportPlanner.plan(ImportCsv.parse(file), catalog, emptySet(), null, emptyList(), now = 0)
        assertEquals(5, plan.rows.size)
        assertEquals(listOf(10L, 10L, 30L, 31L, 20L), plan.rows.map { it.subcategoryId })
        assertEquals(5, plan.rows.mapNotNull { it.sourceKey }.toSet().size)
        assertTrue(plan.rows.all { it.status == TxStatus.CONFIRMED && it.source == TxSource.IMPORT })
        assertTrue(plan.rows.all { it.merchantKey.isEmpty() })
        assertEquals("Gig", plan.rows[3].note)
        assertEquals(mapOf("Snacks / Crisps" to 1), plan.unmatched)
        assertEquals(1, plan.away.size)
        assertEquals("2026-01-05", plan.firstDay)
        assertEquals("2026-10-07", plan.lastDay)
        assertEquals(mapOf("GBP" to 160L + 160 + 499 + 3000 + 1200), plan.totalsByCurrency)
    }

    @Test
    fun skipsWhatIsAlreadyInAndDaysTheBankAlertsCover() {
        val first = ImportPlanner.plan(ImportCsv.parse(file), catalog, emptySet(), null, emptyList(), now = 0)
        val keys = first.rows.take(2).mapNotNull { it.sourceKey }.toSet()
        val again = ImportPlanner.plan(
            ImportCsv.parse(file), catalog, keys, firstBankDay = "2026-10-01",
            existingAway = first.away,
        )
        assertEquals(2, again.alreadyThere)
        assertEquals(1, again.trackedByBank)
        assertEquals(2, again.rows.size)
        assertTrue(again.away.isEmpty())
    }
}
