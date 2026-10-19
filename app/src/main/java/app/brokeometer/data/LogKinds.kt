package app.brokeometer.data

/** The alert log's kinds of line, for its filter. */
object LogKinds {

    enum class Kind(val label: String) { PAYMENTS("Payments"), EMAIL("Email"), NEWS("News") }

    /** What News writes in the log as the sender. */
    const val NEWS_SOURCE = "news"

    /** What a line is about: News's briefings, an email app's alert, or else a bank's (payments and money in). */
    fun kindOf(packageName: String, outcome: String): Kind = when {
        packageName == NEWS_SOURCE || outcome.startsWith("News") -> Kind.NEWS
        outcome.startsWith("Email") -> Kind.EMAIL
        else -> Kind.PAYMENTS
    }
}
