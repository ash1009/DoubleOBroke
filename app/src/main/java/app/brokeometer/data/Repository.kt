package app.brokeometer.data

import androidx.room.withTransaction
import app.brokeometer.domain.Categorizer
import app.brokeometer.domain.Example
import app.brokeometer.domain.Guess
import app.brokeometer.domain.GuessSource
import app.brokeometer.domain.ImportCsv
import app.brokeometer.domain.MealIds
import app.brokeometer.domain.ParsedPayment
import app.brokeometer.insights.Goals
import app.brokeometer.util.Dates
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class Repository(private val db: AppDatabase) {

    val dao: BrokeDao = db.dao()
    private val seedLock = Mutex()

    /** Puts the fixed category list and the starter examples in place on first run. */
    suspend fun ensureSeeded() {
        if (dao.categoryCount() > 0) return
        seedLock.withLock {
            if (dao.categoryCount() > 0) return
            db.withTransaction {
                val idByCode = HashMap<String, Long>()
                Seed.categories.forEachIndexed { index, category ->
                    val categoryId = dao.insertCategory(
                        CategoryEntity(name = category.name, position = index, colorIndex = index)
                    )
                    category.subs.forEachIndexed { subIndex, sub ->
                        val code = Seed.code(category.name, sub)
                        idByCode[code] = dao.insertSubcategory(
                            SubcategoryEntity(categoryId = categoryId, name = sub, position = subIndex, code = code)
                        )
                    }
                }
                dao.insertStarterExamples(
                    Seed.starters.mapNotNull { starter ->
                        val subId = idByCode[starter.code] ?: return@mapNotNull null
                        StarterExampleEntity(
                            merchantKey = starter.merchantKey,
                            subcategoryId = subId,
                            hour = starter.hour,
                            amountMinor = starter.amountMinor,
                        )
                    }
                )
            }
        }
    }

    suspend fun catalog(): Catalog = Catalog(dao.categories(), dao.subcategories())

    /** The phone's guess for a payment, from the owner's past choices. */
    suspend fun guess(merchantKey: String, merchantName: String, occurredAt: Long, amountMinor: Long): Guess {
        val subs = dao.subcategories()
        val idByCode = subs.mapNotNull { sub -> sub.code?.let { it to sub.id } }.toMap()
        val breakfast = idByCode["food.breakfast"]
        val lunch = idByCode["food.lunch"]
        val dinner = idByCode["food.dinner"]
        val meals = if (breakfast != null && lunch != null && dinner != null) MealIds(breakfast, lunch, dinner) else null

        val now = System.currentTimeMillis()
        val learned = dao.learnedRows().map { row ->
            Example(
                merchantKey = row.merchantKey,
                subcategoryId = row.subcategoryId,
                // Entries typed in by hand carry no real time of day.
                hour = if (row.source == TxSource.MANUAL) null else Dates.hourOf(row.occurredAt),
                amountMinor = row.amountMinor,
                starter = false,
                ageDays = TimeUnit.MILLISECONDS.toDays((now - row.occurredAt).coerceAtLeast(0)).toInt(),
            )
        }
        val starters = dao.starterExamples().map {
            Example(it.merchantKey, it.subcategoryId, it.hour, it.amountMinor, starter = true)
        }
        return Categorizer(meals) { code -> idByCode[code] }.guess(
            merchantKey = merchantKey,
            merchantName = merchantName,
            hour = Dates.hourOf(occurredAt),
            minute = Dates.minuteOf(occurredAt),
            amountMinor = amountMinor,
            examples = learned + starters,
        )
    }

    /**
     * Saves a payment read from a bank alert as pending, with the phone's guess.
     * Returns null when this alert was already saved.
     */
    suspend fun capture(
        payment: ParsedPayment,
        occurredAt: Long,
        sourceKey: String?,
        source: String,
    ): TransactionEntity? {
        ensureSeeded()
        val guess = guess(payment.merchantKey, payment.merchantName, occurredAt, payment.amountMinor)
        val row = TransactionEntity(
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            merchantName = payment.merchantName,
            merchantKey = payment.merchantKey,
            descriptor = payment.descriptor,
            place = payment.place,
            account = payment.account,
            occurredAt = occurredAt,
            day = Dates.dayOf(occurredAt),
            subcategoryId = guess.subcategoryId,
            status = TxStatus.PENDING,
            source = source,
            sourceKey = sourceKey,
            guessSource = guess.source.name,
            guessConfidence = guess.confidence,
            note = "",
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insertTransaction(row)
        return if (id <= 0) null else row.copy(id = id)
    }

    /** Accept the guess as it is. Does nothing when there is no guess to accept. */
    suspend fun accept(id: Long): Boolean {
        val row = dao.transaction(id) ?: return false
        if (row.subcategoryId == null) return false
        if (row.status != TxStatus.CONFIRMED) dao.updateTransaction(row.copy(status = TxStatus.CONFIRMED))
        return true
    }

    /** File the entry under a sub-category chosen by the user, and confirm it. */
    suspend fun setCategory(id: Long, subcategoryId: Long): Boolean {
        val row = dao.transaction(id) ?: return false
        dao.updateTransaction(
            row.copy(subcategoryId = subcategoryId, status = TxStatus.CONFIRMED, guessSource = GUESS_USER)
        )
        return true
    }

    suspend fun save(row: TransactionEntity) = dao.updateTransaction(row)

    /** Puts the AI's guess on a payment that is still waiting, unless the owner has already chosen. */
    suspend fun applyAiGuess(id: Long, subcategoryId: Long, confidence: Float): TransactionEntity? {
        val row = dao.transaction(id) ?: return null
        if (row.status != TxStatus.PENDING || row.guessSource == GUESS_USER) return null
        val updated = row.copy(subcategoryId = subcategoryId, guessSource = GUESS_AI, guessConfidence = confidence)
        dao.updateTransaction(updated)
        return updated
    }

    suspend fun delete(id: Long) = dao.deleteTransaction(id)

    /** An entry typed in by hand, for example cash. Counted straight away. */
    suspend fun addManual(
        amountMinor: Long,
        label: String,
        occurredAt: Long,
        subcategoryId: Long,
        note: String,
        currency: String = "GBP",
        place: String = "",
    ): Long {
        ensureSeeded()
        val name = label.trim().ifEmpty { "Cash" }
        return dao.insertTransaction(
            TransactionEntity(
                amountMinor = amountMinor,
                currency = currency,
                merchantName = name,
                merchantKey = app.brokeometer.domain.Merchants.keyOf(name).ifEmpty { name.uppercase() },
                descriptor = "",
                place = place.trim(),
                account = "",
                occurredAt = occurredAt,
                day = Dates.dayOf(occurredAt),
                subcategoryId = subcategoryId,
                status = TxStatus.CONFIRMED,
                source = TxSource.MANUAL,
                sourceKey = null,
                guessSource = GUESS_USER,
                guessConfidence = 1f,
                note = note.trim(),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun addCategory(name: String): Long {
        val position = dao.lastCategoryPosition() + 1
        return db.withTransaction {
            val id = dao.insertCategory(CategoryEntity(name = name.trim(), position = position, colorIndex = position))
            dao.insertSubcategory(SubcategoryEntity(categoryId = id, name = "Others", position = 0))
            id
        }
    }

    suspend fun addSubcategory(categoryId: Long, name: String): Long {
        val position = dao.lastSubcategoryPosition(categoryId) + 1
        return dao.insertSubcategory(SubcategoryEntity(categoryId = categoryId, name = name.trim(), position = position))
    }

    /** Reads an import file and works out what it would add, without writing anything. */
    suspend fun planImport(text: String): ImportPlan {
        ensureSeeded()
        val bundle = ImportCsv.parse(text)
        return ImportPlanner.plan(
            bundle = bundle,
            catalog = catalog(),
            importedKeys = dao.importedKeys().toHashSet(),
            firstBankDay = dao.firstBankDay(),
            existingAway = dao.awayRanges(),
        )
    }

    /** Writes a planned import. Returns how many entries were added. */
    suspend fun commitImport(plan: ImportPlan): Int = db.withTransaction {
        val added = plan.rows.chunked(400).sumOf { chunk -> dao.insertTransactions(chunk).count { it > 0 } }
        if (plan.away.isNotEmpty()) dao.insertAwayRanges(plan.away)
        added
    }

    /** Everything in the database a backup needs. Settings and the picture are added by the caller. */
    suspend fun backupData(settings: BackupSettings, avatar: String?, appVersion: String): BackupData {
        ensureSeeded()
        return BackupData(
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            categories = dao.categories(),
            subcategories = dao.subcategories(),
            transactions = dao.allTransactions(),
            away = dao.awayRanges(),
            settings = settings,
            avatar = avatar,
        )
    }

    /** Works out what a backup would add, without writing anything. */
    suspend fun planRestore(backup: BackupData, phoneGoals: Goals): RestorePlan {
        ensureSeeded()
        return RestorePlanner.plan(
            backup = backup,
            catalog = catalog(),
            existingIdentities = dao.allTransactions().mapTo(HashSet()) { RestorePlanner.identity(it) },
            existingAway = dao.awayRanges(),
            phoneGoals = phoneGoals,
        )
    }

    /**
     * Adds what a restore planned: missing categories first, then entries and trips.
     * Returns the full map from the backup's sub-category ids to the phone's.
     */
    suspend fun commitRestore(plan: RestorePlan): Pair<Int, Map<Long, Long>> = db.withTransaction {
        val subMap = HashMap(plan.subMap)
        val phoneCategories = dao.categories().toMutableList()
        val backupCategories = plan.backup.categories.associateBy { it.id }
        for (sub in plan.missingSubs.sortedWith(compareBy({ it.categoryId }, { it.position }))) {
            val oldCategory = backupCategories[sub.categoryId] ?: continue
            val category = phoneCategories.firstOrNull { it.name.trim().equals(oldCategory.name.trim(), ignoreCase = true) }
                ?: run {
                    val position = dao.lastCategoryPosition() + 1
                    val fresh = CategoryEntity(name = oldCategory.name.trim(), position = position, colorIndex = oldCategory.colorIndex)
                    fresh.copy(id = dao.insertCategory(fresh)).also { phoneCategories += it }
                }
            val position = dao.lastSubcategoryPosition(category.id) + 1
            subMap[sub.id] = dao.insertSubcategory(
                SubcategoryEntity(categoryId = category.id, name = sub.name, position = position, code = sub.code)
            )
        }
        val rows = plan.rows.map { row -> row.copy(id = 0, subcategoryId = row.subcategoryId?.let { subMap[it] }) }
        val added = rows.chunked(400).sumOf { chunk -> dao.insertTransactions(chunk).count { it > 0 } }
        if (plan.away.isNotEmpty()) dao.insertAwayRanges(plan.away.map { it.copy(id = 0) })
        added to subMap
    }

    suspend fun log(packageName: String, title: String?, text: String?, outcome: String) {
        dao.insertLog(
            CaptureLogEntity(
                at = System.currentTimeMillis(),
                packageName = packageName,
                title = title.orEmpty(),
                text = text.orEmpty(),
                outcome = outcome,
            )
        )
        dao.trimLog()
    }

    companion object {
        const val GUESS_USER = "USER"
        const val GUESS_AI = "AI"
        val NO_GUESS: String = GuessSource.NONE.name
    }
}
