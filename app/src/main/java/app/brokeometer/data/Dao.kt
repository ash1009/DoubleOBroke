package app.brokeometer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class MonthTotal(
    /** yyyy-MM */
    val month: String,
    val currency: String,
    val totalMinor: Long,
    val entries: Int,
)

data class DayTotal(
    /** yyyy-MM-dd */
    val day: String,
    val currency: String,
    val totalMinor: Long,
)

data class CategorySum(val categoryId: Long, val totalMinor: Long)

data class ForeignDay(val currency: String, val day: String)

data class LearnedRow(
    val merchantKey: String,
    val subcategoryId: Long,
    val occurredAt: Long,
    val amountMinor: Long,
    val source: String,
)

@Dao
interface BrokeDao {

    // Categories
    @Query("SELECT * FROM categories ORDER BY position")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM subcategories ORDER BY categoryId, position")
    fun observeSubcategories(): Flow<List<SubcategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY position")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM subcategories ORDER BY categoryId, position")
    suspend fun subcategories(): List<SubcategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

    @Insert
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert
    suspend fun insertSubcategory(subcategory: SubcategoryEntity): Long

    @Query("SELECT COALESCE(MAX(position), -1) FROM categories")
    suspend fun lastCategoryPosition(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM subcategories WHERE categoryId = :categoryId")
    suspend fun lastSubcategoryPosition(categoryId: Long): Int

    // Transactions
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>): List<Long>

    /** Every real entry, for backups. Test entries are left out. */
    @Query("SELECT * FROM transactions WHERE source != 'TEST' ORDER BY id")
    suspend fun allTransactions(): List<TransactionEntity>

    @Query("SELECT sourceKey FROM transactions WHERE sourceKey LIKE 'import|%'")
    suspend fun importedKeys(): List<String>

    /** The first day the app saw a payment from a bank alert, or null if it has not yet. */
    @Query("SELECT MIN(day) FROM transactions WHERE source = 'BANK'")
    suspend fun firstBankDay(): String?

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Long)

    @Query("UPDATE transactions SET latitude = :latitude, longitude = :longitude, locationAccuracy = :accuracy WHERE id = :id")
    suspend fun setLocation(id: Long, latitude: Double, longitude: Double, accuracy: Float)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE day IN (:days) AND source != 'TEST' ORDER BY occurredAt")
    suspend fun onDays(days: List<String>): List<TransactionEntity>

    /** Trips found from where you went, for the Travel page. */
    @Query("SELECT * FROM transactions WHERE source = 'TRAVEL' AND day = :day ORDER BY occurredAt")
    fun observeTravel(day: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE status = 'PENDING' ORDER BY occurredAt DESC, id DESC")
    fun observePending(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE status = 'PENDING' ORDER BY occurredAt DESC, id DESC")
    suspend fun pending(): List<TransactionEntity>

    @Query(
        "SELECT * FROM transactions WHERE status = 'CONFIRMED' AND day LIKE :monthPrefix || '%' " +
            "ORDER BY occurredAt DESC, id DESC"
    )
    fun observeMonth(monthPrefix: String): Flow<List<TransactionEntity>>

    @Query(
        "SELECT substr(day, 1, 7) AS month, currency, SUM(amountMinor) AS totalMinor, COUNT(*) AS entries " +
            "FROM transactions WHERE status = 'CONFIRMED' GROUP BY substr(day, 1, 7), currency ORDER BY month"
    )
    fun observeMonthTotals(): Flow<List<MonthTotal>>

    @Query(
        "SELECT day, currency, SUM(amountMinor) AS totalMinor FROM transactions " +
            "WHERE status = 'CONFIRMED' GROUP BY day, currency ORDER BY day"
    )
    fun observeDayTotals(): Flow<List<DayTotal>>

    @Query(
        "SELECT merchantKey, subcategoryId, occurredAt, amountMinor, source FROM transactions " +
            "WHERE status = 'CONFIRMED' AND subcategoryId IS NOT NULL AND merchantKey != '' " +
            "ORDER BY occurredAt DESC LIMIT 4000"
    )
    suspend fun learnedRows(): List<LearnedRow>

    @Query("DELETE FROM transactions WHERE source = 'TEST'")
    suspend fun deleteTestTransactions()

    /** Every counted payment, newest first, for the Payments page. */
    @Query("SELECT * FROM transactions WHERE status = 'CONFIRMED' ORDER BY day DESC, occurredAt DESC, id DESC")
    fun observeHistory(): Flow<List<TransactionEntity>>

    /** Every counted payment, for insights. */
    @Query("SELECT * FROM transactions WHERE status = 'CONFIRMED' AND subcategoryId IS NOT NULL")
    fun observeConfirmed(): Flow<List<TransactionEntity>>

    /** Pounds spent per category in a month (yyyy-MM), for category caps. */
    @Query(
        "SELECT s.categoryId AS categoryId, SUM(t.amountMinor) AS totalMinor FROM transactions t " +
            "JOIN subcategories s ON t.subcategoryId = s.id " +
            "WHERE t.status = 'CONFIRMED' AND t.currency = 'GBP' AND t.day LIKE :monthPrefix || '%' " +
            "GROUP BY s.categoryId"
    )
    fun observeCategorySums(monthPrefix: String): Flow<List<CategorySum>>

    // Exchange rates
    @Query("SELECT DISTINCT currency, day FROM transactions WHERE currency != 'GBP'")
    suspend fun foreignDays(): List<ForeignDay>

    @Query("SELECT * FROM fx_rates")
    fun observeFxRates(): Flow<List<FxRateEntity>>

    @Query("SELECT * FROM fx_rates")
    suspend fun fxRates(): List<FxRateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFxRates(rows: List<FxRateEntity>)

    // Trips
    @Query("SELECT * FROM away_ranges ORDER BY startDay")
    fun observeAwayRanges(): Flow<List<AwayRangeEntity>>

    @Query("SELECT * FROM away_ranges ORDER BY startDay")
    suspend fun awayRanges(): List<AwayRangeEntity>

    @Insert
    suspend fun insertAwayRanges(rows: List<AwayRangeEntity>)

    // Starter examples
    @Query("SELECT * FROM starter_examples")
    suspend fun starterExamples(): List<StarterExampleEntity>

    @Insert
    suspend fun insertStarterExamples(rows: List<StarterExampleEntity>)

    // Alert log
    @Insert
    suspend fun insertLog(row: CaptureLogEntity)

    @Query("DELETE FROM capture_log WHERE id NOT IN (SELECT id FROM capture_log ORDER BY id DESC LIMIT 400)")
    suspend fun trimLog()

    @Query("SELECT * FROM capture_log ORDER BY id DESC LIMIT 400")
    fun observeLog(): Flow<List<CaptureLogEntity>>
}
