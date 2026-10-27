package app.brokeometer.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.brokeometer.mail.AppAlert
import java.lang.ref.WeakReference

/**
 * The email apps' own notifications: which apps count, what is showing now, and clearing one.
 * Works through the notification listener while Android has it connected.
 */
object MailAppAlerts {

    const val GMAIL = "com.google.android.gm"
    const val YAHOO = "com.yahoo.mobile.client.android.mail"

    /** Email apps whose notifications the agent takes over. */
    val PACKAGES = setOf(
        GMAIL,
        YAHOO,
        "com.microsoft.office.outlook",
        "com.samsung.android.email.provider",
    )

    /** Test builds: a notification posted from a computer with this title stands in for Gmail. */
    const val TEST_TITLE = "Mail test"
    private const val SHELL = "com.android.shell"

    @Volatile private var service: WeakReference<NotificationListenerService>? = null

    fun attach(listener: NotificationListenerService) {
        service = WeakReference(listener)
    }

    fun detach(listener: NotificationListenerService) {
        if (service?.get() === listener) service = null
    }

    fun isMailApp(sbn: StatusBarNotification, testMode: Boolean): Boolean =
        sbn.packageName in PACKAGES ||
            (testMode && sbn.packageName == SHELL &&
                sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.startsWith(TEST_TITLE) == true)

    /** Email-app notifications showing right now. Empty when the listener is not connected. */
    fun active(testMode: Boolean): List<AppAlert> {
        val listener = service?.get() ?: return emptyList()
        val all = try {
            listener.activeNotifications
        } catch (e: RuntimeException) {
            null
        } ?: return emptyList()
        return all.filter { isMailApp(it, testMode) }.mapNotNull { toAlert(it) }
    }

    fun isActive(key: String): Boolean {
        val listener = service?.get() ?: return false
        return try {
            listener.activeNotifications?.any { it.key == key } == true
        } catch (e: RuntimeException) {
            false
        }
    }

    fun cancel(key: String): Boolean {
        val listener = service?.get() ?: return false
        return try {
            listener.cancelNotification(key)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Clears an email app's notification the agent takes over, and its bundle header once nothing for
     * that account is left under it. False when it couldn't be cleared.
     */
    fun takeOver(alert: AppAlert): Boolean {
        if (!cancel(alert.key)) return false
        val left = active(testMode = true).filter { it.packageName == alert.packageName && it.key != alert.key }
        val sameAccount = { a: AppAlert -> a.account == null || alert.account == null || a.account.equals(alert.account, ignoreCase = true) }
        if (left.none { !it.summary && sameAccount(it) }) {
            left.filter { it.summary && sameAccount(it) }.forEach { cancel(it.key) }
        }
        return true
    }

    /**
     * Whether this email-app notification is a new email, not the app's own status (Gmail's "Syncing
     * mail" while it checks in the background, a progress bar, sending): those are left alone.
     */
    fun isNewEmail(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val e = n.extras ?: return false
        val ongoing = sbn.isOngoing ||
            n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
            n.category in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_STATUS, Notification.CATEGORY_SYSTEM) ||
            e.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        return !app.brokeometer.mail.AlertMatcher.isStatus(title, ongoing)
    }

    fun toAlert(sbn: StatusBarNotification): AppAlert? {
        if (!isNewEmail(sbn)) return null
        val n = sbn.notification ?: return null
        val e = n.extras ?: return null
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ").orEmpty()
        val text = listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .joinToString(" ") { e.getCharSequence(it)?.toString().orEmpty() } + " " + lines
        return AppAlert(
            key = sbn.key ?: return null,
            packageName = sbn.packageName,
            account = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.takeIf { it.contains('@') },
            title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = text,
            summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
    }
}
