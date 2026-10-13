package app.brokeometer.data

import app.brokeometer.domain.ImportBundle
import app.brokeometer.util.Dates
import java.time.LocalDate

/** What an import would add, worked out before anything is written. */
data class ImportPlan(
    val rows: List<TransactionEntity>,
    val away: List<AwayRangeEntity>,
    /** Entries skipped because the same import already brought them in. */
    val alreadyThere: Int,
    /** Entries skipped because the app was already reading HSBC alerts on those days. */
    val trackedByBank: Int,
    /** "Food / Snacks" names that match no sub-category in the app, with how many entries each. */
    val unmatched: Map<String, Int>,
    val problems: List<String>,
) {
    val firstDay: String? = rows.minOfOrNull { it.day }
    val lastDay: String? = rows.maxOfOrNull { it.day }
    val totalsByCurrency: Map<String, Long> =
        rows.groupBy { it.currency }.mapValues { (_, list) -> list.sumOf { it.amountMinor } }
    val isEmpty: Boolean get() = rows.isEmpty() && away.isEmpty()
}

object ImportPlanner {

    fun plan(
        bundle: ImportBundle,
        catalog: Catalog,
        importedKeys: Set<String>,
        firstBankDay: String?,
        existingAway: List<AwayRangeEntity>,
        now: Long = System.currentTimeMillis(),
    ): ImportPlan {
        val byCode = catalog.subcategories.mapNotNull { sub -> sub.code?.let { it to sub.id } }.toMap()
        val byName = catalog.subcategories.mapNotNull { sub ->
            val category = catalog.category(sub.categoryId) ?: return@mapNotNull null
            Seed.code(category.name, sub.name) to sub.id
        }.toMap()

        val seen = HashMap<String, Int>()
        val rows = ArrayList<TransactionEntity>()
        val unmatched = LinkedHashMap<String, Int>()
        var alreadyThere = 0
        var trackedByBank = 0

        for (spend in bundle.spends) {
            val code = Seed.code(spend.category, spend.subcategory)
            val subId = byCode[code] ?: byName[code]
            if (subId == null) {
                val name = "${spend.category} / ${spend.subcategory}"
                unmatched[name] = (unmatched[name] ?: 0) + 1
                continue
            }
            val day = Dates.toDay(spend.day)
            // The same day, category and amount can appear more than once; number them so each is kept.
            val base = "import|$day|$code|${spend.amountMinor}|${spend.currency}"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            val key = "$base|$n"
            when {
                key in importedKeys -> alreadyThere++
                firstBankDay != null && day >= firstBankDay -> trackedByBank++
                else -> rows += TransactionEntity(
                    amountMinor = spend.amountMinor,
                    currency = spend.currency,
                    merchantName = "",
                    merchantKey = "",
                    descriptor = "",
                    place = "",
                    account = "",
                    occurredAt = Dates.middayMillis(spend.day),
                    day = day,
                    subcategoryId = subId,
                    status = TxStatus.CONFIRMED,
                    source = TxSource.IMPORT,
                    sourceKey = key,
                    guessSource = Repository.GUESS_USER,
                    guessConfidence = 1f,
                    note = spend.note,
                    createdAt = now,
                )
            }
        }

        val haveAway = existingAway.map { it.startDay to it.endDay }.toSet()
        val away = bundle.away
            .map { trip ->
                val (start, end) = ordered(trip.start, trip.end)
                AwayRangeEntity(startDay = Dates.toDay(start), endDay = Dates.toDay(end), label = trip.label)
            }
            .filter { (it.startDay to it.endDay) !in haveAway }
            .distinctBy { it.startDay to it.endDay }

        return ImportPlan(rows, away, alreadyThere, trackedByBank, unmatched, bundle.problems)
    }

    private fun ordered(a: LocalDate, b: LocalDate) = if (a <= b) a to b else b to a
}
