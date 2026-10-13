package app.brokeometer.domain

/** One sub-category offered to the AI: its id and "Main / Sub" name. */
data class AiChoice(val id: Long, val label: String)

/** What the AI is told about one payment. */
data class AiPayment(
    val merchantName: String,
    val descriptor: String,
    val place: String,
    val amount: String,
    /** For example "Thursday 14:05". */
    val time: String,
)

data class AiAnswer(val subcategoryId: Long, val confidence: Float)

/**
 * Builds the question for the cloud AI, and reads its answer. Only used for shops the phone has
 * never seen; after the owner accepts or changes the answer, the phone learns it for next time.
 */
object AiPrompt {

    fun build(payment: AiPayment, choices: List<AiChoice>): String = buildString {
        appendLine("You sort card payments into spending categories for one person, a student living in London.")
        appendLine("Choose the single best sub-category for this payment.")
        appendLine()
        appendLine("Payment:")
        appendLine("- Shop: ${payment.merchantName}")
        if (payment.descriptor.isNotBlank() && payment.descriptor != payment.merchantName) {
            appendLine("- Bank text: ${payment.descriptor}")
        }
        if (payment.place.isNotBlank()) appendLine("- Town or website: ${payment.place}")
        appendLine("- Amount: ${payment.amount}")
        appendLine("- When: ${payment.time}")
        appendLine()
        appendLine("Sub-categories (id: Main / Sub):")
        choices.forEach { appendLine("${it.id}: ${it.label}") }
        appendLine()
        append("""Reply with JSON only, like {"id": 12, "confidence": 0.8}. """)
        append("Meals: breakfast before 11am, lunch until 4:30pm, dinner after that. ")
        append("If you really cannot tell, pick the closest and give a low confidence.")
    }

    private val idPattern = Regex(""""id"\s*:\s*"?(\d+)"?""")
    private val confidencePattern = Regex(""""confidence"\s*:\s*"?([0-9]*\.?[0-9]+)"?""")

    /** Reads {"id": 12, "confidence": 0.8} from the AI's reply. Null when it is not usable. */
    fun parse(reply: String?, choices: List<AiChoice>): AiAnswer? {
        if (reply.isNullOrBlank()) return null
        val id = idPattern.find(reply)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (choices.none { it.id == id }) return null
        val confidence = confidencePattern.find(reply)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
        return AiAnswer(id, confidence.coerceIn(0f, 1f))
    }
}
