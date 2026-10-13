package app.brokeometer.capture

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

/** Files a payment from a shop the phone has never seen, by asking Gemini. */
class AiGuesser(private val repository: Repository, private val gemini: GeminiClient) {

    data class Outcome(val answer: AiAnswer?, val note: String)

    val ready: Boolean get() = gemini.hasKey

    suspend fun ask(payment: AiPayment): Outcome {
        val catalog = repository.catalog()
        val choices = catalog.subcategories.mapNotNull { sub ->
            catalog.category(sub.categoryId)?.let { AiChoice(sub.id, "${it.name} / ${sub.name}") }
        }
        return when (val reply = gemini.ask(AiPrompt.build(payment, choices))) {
            is GeminiClient.Reply.Failed -> Outcome(null, reply.message)
            is GeminiClient.Reply.Text -> {
                val answer = AiPrompt.parse(reply.text, choices)
                if (answer == null) Outcome(null, "Gemini's answer could not be used.")
                else Outcome(answer, catalog.label(answer.subcategoryId) ?: "")
            }
        }
    }

    suspend fun ask(tx: TransactionEntity): Outcome = ask(paymentOf(tx))

    companion object {
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
