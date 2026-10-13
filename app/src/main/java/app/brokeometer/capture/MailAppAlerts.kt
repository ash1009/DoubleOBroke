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

    fun toAlert(sbn: StatusBarNotification): AppAlert? {
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
