package app.brokeometer.capture

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
import app.brokeometer.data.MoneyMoveEntity
import app.brokeometer.data.Prefs
import app.brokeometer.data.TransactionEntity
import app.brokeometer.ui.MainActivity
import app.brokeometer.ui.PaybackActivity
import app.brokeometer.ui.UpdateActivity
import app.brokeometer.util.Dates
import app.brokeometer.util.Money

/** Posts the "entered for Lunch, Food" alert with its Accept and Update buttons. */
class Notifier(private val context: Context, private val prefs: Prefs) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_PAYMENTS,
            context.getString(R.string.channel_payments),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_payments_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NUDGES,
                context.getString(R.string.channel_nudges),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_nudges_description) }
        )
    }

    /** A one-off note about a category cap. Returns false when alerts are off. */
    fun showNudge(categoryId: Long, title: String, body: String): Boolean {
        if (!canPost()) return false
        val open = PendingIntent.getActivity(
            context,
            REQUEST_INSIGHTS,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_INSIGHTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup("nudges")
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NUDGE_ID_BASE - categoryId.toInt(), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** [label] is the guessed "Lunch, Food", or null when the app has no guess. */
    fun showPending(tx: TransactionEntity, label: String?) {
        if (!canPost()) return
        val amount = Money.format(tx.amountMinor, tx.currency)
        val title = "$amount at ${tx.merchantName}"
        val body = if (label != null) {
            "Entered for $label on ${Dates.pretty(tx.day)}"
        } else {
            "Not sure where this goes. Tap Choose to file it."
        }

        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_PENDING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val update = PendingIntent.getActivity(
            context,
            requestCode(tx.id),
            Intent(context, UpdateActivity::class.java)
                .setData(Uri.parse("brokeometer://entry/${tx.id}"))
                .putExtra(EXTRA_TX_ID, tx.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PAYMENTS)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(tx.occurredAt)
            .setShowWhen(true)
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setOngoing(prefs.keepAlerts.value)
            // Its own group, so Android does not fold several alerts into one bundle.
            .setGroup("entry-${tx.id}")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (label != null) {
            val accept = PendingIntent.getBroadcast(
                context,
                requestCode(tx.id),
                Intent(context, ActionReceiver::class.java)
                    .setAction(ACTION_ACCEPT)
                    .setData(Uri.parse("brokeometer://entry/${tx.id}"))
                    .putExtra(EXTRA_TX_ID, tx.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Accept", accept)
            builder.addAction(0, "Update", update)
        } else {
            builder.addAction(0, "Choose", update)
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId(tx.id), builder.build())
        } catch (e: SecurityException) {
            // Notifications were turned off between the check and the post. The entry still waits in the app.
        }
    }

    /**
     * Money in from someone else: is it paying you back? Paying back opens the list of your recent
     * payments; My account marks it as a transfer from yourself; Other keeps it as money in.
     */
    fun showMoneyIn(move: MoneyMoveEntity) {
        if (!canPost()) return
        val amount = Money.format(move.amountMinor, move.currency)
        val who = move.counterparty.ifBlank { "someone" }
        val title = "$amount from $who"
        val body = "In your ${move.bank} account. Is this paying you back for something?"
        val code = moneyCode(move.id)
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_MONEY_IN,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_MONEY_IN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val payback = PendingIntent.getActivity(
            context,
            code,
            Intent(context, PaybackActivity::class.java)
                .setData(Uri.parse("brokeometer://money/${move.id}"))
                .putExtra(EXTRA_MOVE_ID, move.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun broadcast(action: String) = PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, ActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("brokeometer://money/${move.id}"))
                .putExtra(EXTRA_MOVE_ID, move.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PAYMENTS)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(move.occurredAt)
            .setShowWhen(true)
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setGroup("money-${move.id}")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Paying back", payback)
            .addAction(0, "My account", broadcast(ACTION_MONEY_MINE))
            .addAction(0, "Other", broadcast(ACTION_MONEY_OTHER))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(moneyCode(move.id), notification)
        } catch (e: SecurityException) {
            // Alerts were turned off in between. The money in still waits on the Money in page.
        }
    }

    fun cancelMoneyIn(moveId: Long) {
        NotificationManagerCompat.from(context).cancel(moneyCode(moveId))
    }

    /** Money in alerts sit far from payment alerts, whose ids are the payments' own. */
    private fun moneyCode(moveId: Long): Int = (MONEY_ID_BASE + moveId % 500_000_000L).toInt()

    fun cancel(txId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(txId))
    }

    private fun notificationId(txId: Long): Int = (txId % Int.MAX_VALUE).toInt()

    private fun requestCode(txId: Long): Int = (txId % Int.MAX_VALUE).toInt()

    companion object {
        const val CHANNEL_PAYMENTS = "payments"
        const val CHANNEL_NUDGES = "nudges"
        private const val REQUEST_INSIGHTS = 2
        private const val NUDGE_ID_BASE = -1000
        const val EXTRA_TX_ID = "tx_id"
        const val ACTION_ACCEPT = "app.brokeometer.action.ACCEPT"
        const val ACTION_MONEY_MINE = "app.brokeometer.action.MONEY_MINE"
        const val ACTION_MONEY_OTHER = "app.brokeometer.action.MONEY_OTHER"
        const val EXTRA_MOVE_ID = "move_id"
        private const val MONEY_ID_BASE = 1_500_000_000L
        private const val REQUEST_MONEY_IN = 3
        private const val REQUEST_OPEN = 1
    }
}
