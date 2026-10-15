package app.brokeometer.capture

import app.brokeometer.data.ClaudeClient
import app.brokeometer.data.GeminiClient
import app.brokeometer.data.Repository
import app.brokeometer.data.TransactionEntity
import app.brokeometer.domain.AiAnswer
import app.brokeometer.domain.AiChoice
import app.brokeometer.domain.AiPayment
import app.brokeometer.domain.AiPrompt
import app.brokeometer.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Files a payment from a shop the phone has never seen, by asking an AI: Claude first, and Gemini
 * when there is no Claude key or Claude can't answer (no credit left, no internet, a bad reply).
 */
class AiGuesser(
    private val repository: Repository,
    private val claude: ClaudeClient,
    private val gemini: GeminiClient,
) {

    data class Outcome(val answer: AiAnswer?, val note: String)

    val ready: Boolean get() = claude.hasKey || gemini.hasKey

    /** [useClaude] false asks Gemini only, for the Gemini key check in Settings. */
    suspend fun ask(payment: AiPayment, useClaude: Boolean = true): Outcome {
        val catalog = repository.catalog()
        val choices = catalog.subcategories.mapNotNull { sub ->
            catalog.category(sub.categoryId)?.let { AiChoice(sub.id, "${it.name} / ${sub.name}") }
        }
        val prompt = AiPrompt.build(payment, choices)
        var claudeNote: String? = null
        if (useClaude && claude.hasKey) {
            when (val reply = claude.ask(SYSTEM, prompt, maxTokens = 100)) {
                is ClaudeClient.Reply.Text -> {
                    val answer = AiPrompt.parse(reply.text, choices)
                    if (answer != null) return Outcome(answer, catalog.label(answer.subcategoryId) ?: "")
                    claudeNote = "Claude's answer could not be used."
                }
                is ClaudeClient.Reply.Failed -> claudeNote = "Claude: ${reply.message}"
            }
        }
        if (!gemini.hasKey) return Outcome(null, claudeNote ?: "No AI key in the app yet.")
        return when (val reply = gemini.ask(prompt)) {
            is GeminiClient.Reply.Failed -> Outcome(null, listOfNotNull(claudeNote, "Gemini: ${reply.message}").joinToString(" "))
            is GeminiClient.Reply.Text -> {
                val answer = AiPrompt.parse(reply.text, choices)
                if (answer == null) Outcome(null, listOfNotNull(claudeNote, "Gemini's answer could not be used.").joinToString(" "))
                else Outcome(answer, catalog.label(answer.subcategoryId) ?: "")
            }
        }
    }

    suspend fun ask(tx: TransactionEntity): Outcome = ask(paymentOf(tx))

    companion object {
        private const val SYSTEM = "You sort card payments into spending categories. Reply with JSON only."
        private val whenFormat = DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.UK)

        fun paymentOf(tx: TransactionEntity) = AiPayment(
            merchantName = tx.merchantName,
            descriptor = tx.descriptor,
            place = tx.place,
            amount = Money.format(tx.amountMinor, tx.currency),
            time = whenFormat.format(Instant.ofEpochMilli(tx.occurredAt).atZone(ZoneId.systemDefault())),
        )
    }
}
