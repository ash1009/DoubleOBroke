package app.brokeometer.capture

import app.brokeometer.data.Repository
import app.brokeometer.domain.AiChoice
import app.brokeometer.domain.VoiceDraft
import app.brokeometer.domain.VoicePayment
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Adds a payment from a spoken sentence, such as "3.4 pounds for metro at Vauxhall". When the amount
 * or the category can't be worked out, it hands back what it understood so the form can be filled in.
 * [ask] sends a question to the AI and returns its answer, or null when there is no AI.
 */
class VoiceAdder(
    private val repository: Repository,
    private val ask: suspend (system: String, prompt: String) -> String?,
) {
    sealed interface Result {
        data class Saved(val id: Long, val message: String) : Result
        data class Check(val draft: VoiceDraft, val message: String) : Result
    }

    suspend fun add(spoken: String): Result {
        val local = VoicePayment.local(spoken)
        val catalog = repository.catalog()
        val choices = catalog.subcategories.mapNotNull { sub ->
            catalog.category(sub.categoryId)?.let { AiChoice(sub.id, "${it.name} / ${sub.name}") }
        }
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK))
        val reply = ask(VoicePayment.SYSTEM, VoicePayment.prompt(spoken, choices, today))
        val draft = VoicePayment.merge(local, VoicePayment.parseAi(reply, choices))
        val amount = draft.amountMinor
        val sub = draft.subcategoryId
        if (amount == null || amount <= 0) return Result.Check(draft, "Heard \"$spoken\" but no amount. Fill it in and tap Save.")
        if (sub == null) {
            val why = if (reply == null) "No AI to pick the category" else "Couldn't pick the category"
            return Result.Check(draft, "$why for \"$spoken\". Pick it and tap Save.")
        }
        val currency = draft.currency ?: "GBP"
        val date = LocalDate.now().minusDays(draft.daysAgo.toLong())
        val occurredAt = if (draft.daysAgo == 0) System.currentTimeMillis() else Dates.middayMillis(date)
        val where = draft.where.ifBlank { draft.note }.ifBlank { "Cash" }
        val id = repository.addManual(
            amountMinor = amount,
            label = where,
            occurredAt = occurredAt,
            subcategoryId = sub,
            note = if (draft.where.isBlank()) "" else draft.note,
            currency = currency,
            place = draft.place,
        )
        val parts = listOfNotNull(
            Money.format(amount, currency),
            where,
            draft.place.takeIf { it.isNotBlank() },
            catalog.label(sub),
            if (draft.daysAgo == 1) "yesterday" else if (draft.daysAgo > 1) Dates.pretty(date) else null,
        )
        return Result.Saved(id, "Added " + parts.joinToString(", "))
    }
}
