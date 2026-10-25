package app.brokeometer.mail

/** Where things to do found in emails go: Tasks, when it wants them. */
interface TaskSink {
    /** Whether new emails should be asked about tasks at all. */
    fun wantsEmailTasks(): Boolean

    /** A task found in a new email, with the email's list entry. */
    fun fromEmail(accountId: Long, logId: Long, item: MailItem, task: MailPrompts.TaskHint)
}
