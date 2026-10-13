package app.brokeometer.mail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.brokeometer.BrokeApp
import app.brokeometer.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reading every mailbox and sorting the senders can take a while, so it runs as a foreground job
 * with a progress notification. That keeps it going when the app is closed or the screen goes off.
 */
class MailSortWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val agent = (applicationContext as BrokeApp).container.mail
        val fresh = inputData.getBoolean(FRESH, false)
        val read = runCatching { MailAgent.Read.valueOf(inputData.getString(READ) ?: "ALL") }.getOrDefault(MailAgent.Read.ALL)
        // Not allowed from the background on newer Android; the work still runs, just without the notification.
        runCatching { setForeground(info(applicationContext, agent.progress.value)) }
        coroutineScope {
            val updates = launch {
                agent.progress
                    .map { it.copy(done = it.done / 50 * 50) }
                    .distinctUntilChanged()
                    .collect { p -> runCatching { notify(applicationContext, p) } }
            }
            if (inputData.getBoolean(TIDY, false)) {
                (applicationContext as BrokeApp).container.inboxAgent.tidy()
            } else {
                agent.run(fresh, read)
            }
            updates.cancel()
        }
        NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        info(applicationContext, (applicationContext as BrokeApp).container.mail.progress.value)

    companion object {
        private const val WORK_NAME = "mail-sort"
        private const val FRESH = "fresh"
        private const val READ = "read"
        private const val TIDY = "tidy"
        private const val CHANNEL = "mail-sorting"
        private const val NOTIFICATION_ID = 4_200

        fun start(context: Context, fresh: Boolean, read: MailAgent.Read) {
            val request = OneTimeWorkRequestBuilder<MailSortWorker>()
                .setInputData(workDataOf(FRESH to fresh, READ to read.name))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun startTidy(context: Context) {
            val request = OneTimeWorkRequestBuilder<MailSortWorker>()
                .setInputData(workDataOf(TIDY to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun build(context: Context, p: MailProgress): android.app.Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reading and sorting email", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shows progress while the app reads your mailboxes and sorts senders." }
            )
            val title = when (p.stage) {
                MailProgress.Stage.READING -> "Reading ${p.account ?: "your email"}"
                MailProgress.Stage.TIDYING -> "Tidying up ${p.account ?: "old emails"}"
                else -> "Sorting senders"
            }
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setColor(ContextCompat.getColor(context, R.color.amber))
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
            if (p.total > 0) {
                builder.setProgress(p.total, p.done.coerceAtMost(p.total), false)
                    .setContentText("${"%,d".format(p.done)} of ${"%,d".format(p.total)}")
            } else {
                builder.setProgress(0, 0, true)
                p.message?.let { builder.setContentText(it) }
            }
            return builder.build()
        }

        private fun info(context: Context, p: MailProgress): ForegroundInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, build(context, p), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, build(context, p))
            }

        private fun notify(context: Context, p: MailProgress) {
            if (!p.busy) return
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, p))
        }
    }
}
