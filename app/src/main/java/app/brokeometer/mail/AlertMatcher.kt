package app.brokeometer.mail

/** One notification from an email app, as much of it as the matching needs. */
data class AppAlert(
    val key: String,
    val packageName: String,
    /** The account it is for, when the app shows one (Gmail puts the address in the sub text). */
    val account: String?,
    val title: String,
    /** Text, big text and inbox lines together. */
    val text: String,
    val summary: Boolean,
)

/**
 * Works out which email-app notifications belong to emails the agent has dealt with, so they can be
 * cleared, and leaves alone any for emails it could not decide on. Kept apart from Android so it can be tested.
 */
object AlertMatcher {

    data class Result(
        /** Notification keys to clear. */
        val clear: List<String>,
        /** Activity entries whose notification was cleared. */
        val logIds: List<Long>,
    )

    private val handledActions = setOf(MailActions.NOTIFIED, MailActions.ARCHIVED, MailActions.READ_ELSEWHERE, MailActions.MARKED_READ, MailActions.DELETED)

    fun match(alerts: List<AppAlert>, entries: List<MailLogEntity>, accounts: Map<Long, String>): Result {
        val clear = ArrayList<String>()
        val ids = LinkedHashSet<Long>()
        val leftByPackage = HashMap<String, Int>()
        for (alert in alerts.filter { !it.summary }) {
            val forAccount = alert.account?.lowercase()?.let { a -> entries.filter { accounts[it.accountId]?.let { addr -> a.contains(addr) } == true } }
            val candidates = forAccount?.takeIf { it.isNotEmpty() } ?: entries
            val title = norm(alert.title)
            val blob = norm(alert.title + " " + alert.text)
            val matched = candidates.filter { e -> matches(e, title, blob) }
            val keep = matched.any { it.action == MailActions.UNDECIDED && !it.undone }
            val done = matched.filter { it.action in handledActions }
            if (!keep && done.isNotEmpty()) {
                clear += alert.key
                done.forEach { ids += it.id }
            } else {
                leftByPackage[alert.packageName] = (leftByPackage[alert.packageName] ?: 0) + 1
            }
        }
        // A bundle header with nothing left under it goes too.
        val touched = alerts.filter { !it.summary && it.key in clear }.map { it.packageName }.toSet()
        alerts.filter { it.summary && it.packageName in touched && (leftByPackage[it.packageName] ?: 0) == 0 }
            .forEach { clear += it.key }
        return Result(clear, ids.toList())
    }

    private fun matches(e: MailLogEntity, title: String, blob: String): Boolean {
        val subject = norm(e.subject.replace(Regex("^((re|fwd?|fw)\\s*:\\s*)+", RegexOption.IGNORE_CASE), "")).take(40)
        if (subject.length >= 3) return blob.contains(subject)
        // No usable subject: fall back to who it is from.
        val from = norm(e.fromName.ifBlank { e.fromAddress })
        return from.isNotEmpty() && (title == from || blob.contains(norm(e.fromAddress)))
    }

    fun norm(text: String?): String = text.orEmpty().lowercase().replace(Regex("\\s+"), " ").trim()
}
