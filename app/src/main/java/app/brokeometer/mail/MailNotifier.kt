package app.brokeometer.mail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.brokeometer.R
import app.brokeometer.capture.ActionReceiver
import app.brokeometer.ui.MainActivity

/**
 * The agent's own notifications: one per important email, one for an email it couldn't sort, one for an
 * email it couldn't check yet, and one for spam that might be wanted. The email apps' own notifications
 * are cleared, so these are the only ones.
 */
class MailNotifier(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, context.getString(R.string.channel_mail), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_mail_description) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SPAM, context.getString(R.string.channel_spam), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_spam_description) }
        )
    }

    /** An email that matters. With a [code] (OTP), the code is shown and a button copies it. */
    fun important(log: MailLogEntity, account: MailAccountEntity, code: String? = null) {
        val from = log.fromName.ifBlank { log.fromAddress }
        val body = listOf(log.subject, log.summary).filter { it.isNotBlank() }.distinct().joinToString("\n")
        val builder = base(CHANNEL_MAIL, log, account)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (code != null) {
            builder.setContentTitle("Code $code")
                .setContentText("$from: ${log.subject}")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$from\n${log.subject}").setSummaryText(account.address))
                .addAction(0, "Copy code", copyCode(log.id, code))
        } else {
            builder.setContentTitle(from)
                .setContentText(log.summary.ifBlank { log.subject })
                .setStyle(NotificationCompat.BigTextStyle().bigText(body).setSummaryText(account.address))
        }
        builder.addAction(0, "Mark read", action(ACTION_MAIL_READ, log.id))
            .addAction(0, "Archive", action(ACTION_MAIL_ARCHIVE, log.id))
        post(log.id, builder)
    }

    /** An email the AI couldn't decide on: you're told, and it's left unread in the inbox. */
    fun undecided(log: MailLogEntity, account: MailAccountEntity) {
        val from = log.fromName.ifBlank { log.fromAddress }
        val text = "Couldn't sort this one: ${log.subject.ifBlank { "(no subject)" }}"
        val builder = base(CHANNEL_MAIL, log, account)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(from)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$text\nThe AI couldn't decide on it, so it's left unread in your inbox. If it doesn't matter, mark it read or archive it.")
                    .setSummaryText(account.address),
            )
            .addAction(0, "Mark read", action(ACTION_MAIL_READ, log.id))
            .addAction(0, "Archive", action(ACTION_MAIL_ARCHIVE, log.id))
        post(log.id, builder)
    }

    /**
     * An email the app couldn't check yet (the mailbox couldn't be reached, or doesn't have it yet), told
     * from the email app's own notification, which was cleared. Tapping opens that email app.
     */
    fun unchecked(alert: AppAlert) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val subject = alert.text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val launch = context.packageManager.getLaunchIntentForPackage(alert.packageName)
            ?: Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_EMAIL)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, alert.key.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "New email" + if (subject.isNotEmpty()) ": $subject" else ""
        val builder = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(alert.title.ifBlank { "New email" })
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$text\nThe app couldn't check it yet, so it's left as it is. It tries again on the next check.")
                    .also { style -> alert.account?.let { style.setSummaryText(it) } },
            )
            .setContentIntent(open)
            .setGroup("mail-unchecked-${alert.key.hashCode()}")
        try {
            NotificationManagerCompat.from(context).notify(TAG_UNCHECKED, alert.key.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // Notifications were turned off.
        }
    }

    fun cancelUnchecked(alert: AppAlert) {
        NotificationManagerCompat.from(context).cancel(TAG_UNCHECKED, alert.key.hashCode())
    }

    private fun copyCode(logId: Long, code: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id(logId),
        Intent(context, ActionReceiver::class.java)
            .setAction(ACTION_MAIL_COPY_CODE)
            .setData(Uri.parse("brokeometer://mail/$logId"))
            .putExtra(EXTRA_LOG_ID, logId)
            .putExtra(EXTRA_CODE, code),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun maybeNotSpam(log: MailLogEntity, account: MailAccountEntity) {
        val from = log.fromName.ifBlank { log.fromAddress }
        val text = "In spam, but it might be real: ${log.subject}"
        val builder = base(CHANNEL_SPAM, log, account)
            .setContentTitle("Check spam: $from")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${log.summary}").setSummaryText(account.address))
        post(log.id, builder)
    }

    fun cancel(logId: Long) {
        NotificationManagerCompat.from(context).cancel(TAG, id(logId))
    }

    private fun base(channel: String, log: MailLogEntity, account: MailAccountEntity): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setWhen(log.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setContentIntent(openMailApp(account, log.id))
            .setGroup("mail-${log.id}")

    /** Tapping opens the email app the account belongs to, or the Email page if it is not on the phone. */
    private fun openMailApp(account: MailAccountEntity, logId: Long): PendingIntent {
        val pkg = when (account.provider) {
            MailProviders.GMAIL -> GMAIL_PACKAGE
            MailProviders.YAHOO -> YAHOO_PACKAGE
            else -> null
        }
        val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_EMAIL)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, id(logId), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(name: String, logId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id(logId),
        Intent(context, ActionReceiver::class.java)
            .setAction(name)
            .setData(Uri.parse("brokeometer://mail/$logId"))
            .putExtra(EXTRA_LOG_ID, logId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(logId: Long, builder: NotificationCompat.Builder) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(context).notify(TAG, id(logId), builder.build())
        } catch (e: SecurityException) {
            // Notifications were turned off; the email is still in the inbox and on the Activity list.
        }
    }

    private fun id(logId: Long): Int = (logId % Int.MAX_VALUE).toInt()

    companion object {
        const val CHANNEL_MAIL = "mail"
        const val CHANNEL_SPAM = "spam"
        private const val TAG = "mail"
        private const val TAG_UNCHECKED = "mail-unchecked"
        const val EXTRA_LOG_ID = "mail_log_id"
        const val ACTION_MAIL_READ = "app.brokeometer.action.MAIL_READ"
        const val ACTION_MAIL_ARCHIVE = "app.brokeometer.action.MAIL_ARCHIVE"
        const val ACTION_MAIL_COPY_CODE = "app.brokeometer.action.MAIL_COPY_CODE"
        const val EXTRA_CODE = "mail_code"
        const val GMAIL_PACKAGE = "com.google.android.gm"
        const val YAHOO_PACKAGE = "com.yahoo.mobile.client.android.mail"
    }
}
