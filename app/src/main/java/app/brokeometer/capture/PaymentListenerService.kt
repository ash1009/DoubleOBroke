package app.brokeometer.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.brokeometer.BrokeApp
import app.brokeometer.data.TxSource
import app.brokeometer.domain.IciciParser
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch

/**
 * Android hands this service every notification once the user turns on notification access.
 * Only HSBC's and Starling's are looked at; everything else is dropped without being read. For App limits, it also
 * notes when an Instagram message notification is tapped (not what it says).
 */
class PaymentListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        // The email agent clears email-app notifications through this connection.
        MailAppAlerts.attach(this)
        // Catch up on bank alerts that arrived while the app was not running.
        val current = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        }
        current?.forEach { handle(it, replay = true) }
    }

    override fun onListenerDisconnected() {
        MailAppAlerts.detach(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn, replay = false)
    }

    private fun handle(sbn: StatusBarNotification, replay: Boolean) {
        val app = application as? BrokeApp ?: return
        val sender = sbn.packageName ?: return
        if (sender == packageName) return

        val fromBank = sender.contains("hsbc", ignoreCase = true) || sender.contains("starling", ignoreCase = true)
        val fromTest = sender == SHELL_PACKAGE && app.container.prefs.testMode
        val fromMessages = MESSAGING_HINTS.any { sender.contains(it, ignoreCase = true) }
        val fromMailApp = MailAppAlerts.isMailApp(sbn, app.container.prefs.testMode)
        if (!fromBank && !fromTest && !fromMessages && !fromMailApp) return

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        // A new email in an email app: the agent reads it, and clears this notification once it has
        // dealt with the email, so only the agent's own notification (if any) is left.
        if (fromMailApp) {
            if (!replay) {
                val hint = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                val agentOn = app.container.prefs.mailAgentOn.value
                app.scope.launch {
                    app.container.repository.log(
                        sender, title, text,
                        if (agentOn) "Email: the agent is checking it" + (hint?.let { " ($it)" } ?: "")
                        else "Email: the agent is off, left alone",
                    )
                }
                app.container.inboxAgent.onMailAppNotification(sender, hint, sbn.key)
            }
            return
        }
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()

        // ICICI Bank SMS, shown by the messaging app. Other people's messages are never read past the sender.
        if ((fromMessages || fromTest) && IciciParser.isIcici(title, if (fromTest) text else null)) {
            val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?.messages
                ?.mapNotNull { m -> m.text?.toString()?.let { it to m.timestamp } }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf((text ?: "") to sbn.postTime)
            app.scope.launch {
                // Messaging apps repeat earlier messages in the same alert; only the newest is new.
                messages.forEachIndexed { index, (body, at) ->
                    try {
                        app.container.capture.onIciciMessage(
                            packageName = sender,
                            postedAt = if (at > 0) at else sbn.postTime,
                            sender = title,
                            text = body,
                            source = if (fromTest) TxSource.TEST else TxSource.BANK,
                            replay = replay || index < messages.lastIndex,
                        )
                    } catch (e: Exception) {
                        app.container.repository.log(sender, title, body, "Problem: ${e.javaClass.simpleName}")
                    }
                }
            }
            return
        }
        if (fromMessages) return
        val key = sbn.key ?: "${sender}:${sbn.id}"
        val postedAt = sbn.postTime

        app.scope.launch {
            try {
                val stored = app.container.capture.onBankAlert(
                    packageName = sender,
                    alertKey = key,
                    postedAt = postedAt,
                    title = title,
                    text = text,
                    source = if (fromTest) TxSource.TEST else TxSource.BANK,
                    replay = replay,
                )
                // The payment is in the app now, so the bank's own alert is a duplicate.
                if (stored) clearBankAlert(key)
            } catch (e: Exception) {
                app.container.repository.log(sender, title, text, "Problem: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun clearBankAlert(key: String) {
        try {
            cancelNotification(key)
        } catch (e: RuntimeException) {
            // The listener was disconnected in the meantime. The alert just stays.
        }
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        /** Package names of SMS apps contain one of these, for example com.google.android.apps.messaging. */
        val MESSAGING_HINTS = listOf("messag", "sms", "mms")
    }
}
