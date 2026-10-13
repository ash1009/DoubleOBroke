package app.brokeometer.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.brokeometer.BrokeApp
import app.brokeometer.mail.MailNotifier
import kotlinx.coroutines.launch

/** Handles the Accept button on a payment alert, and Copy code, Mark read and Archive on an email from the agent. */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MailNotifier.ACTION_MAIL_COPY_CODE) {
            copyCode(context, intent)
            return
        }
        if (intent.action == MailNotifier.ACTION_MAIL_READ || intent.action == MailNotifier.ACTION_MAIL_ARCHIVE) {
            mail(context, intent)
            return
        }
        if (intent.action != Notifier.ACTION_ACCEPT) return
        val id = intent.getLongExtra(Notifier.EXTRA_TX_ID, -1L)
        if (id < 0) return
        val app = context.applicationContext as? BrokeApp ?: return
        val result = goAsync()
        app.scope.launch {
            try {
                if (app.container.repository.accept(id)) app.container.notifier.cancel(id)
            } finally {
                result.finish()
            }
        }
    }

    /** Copies the code, hidden from the clipboard preview and wiped after a minute, and clears the notification. */
    private fun copyCode(context: Context, intent: Intent) {
        val code = intent.getStringExtra(MailNotifier.EXTRA_CODE) ?: return
        val app = context.applicationContext as? BrokeApp ?: return
        app.container.vault.copy("Code", code)
        app.container.mailNotifier.cancel(intent.getLongExtra(MailNotifier.EXTRA_LOG_ID, -1L))
        // Android 13 and newer show their own "Copied" message.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(context, "Code copied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun mail(context: Context, intent: Intent) {
        val id = intent.getLongExtra(MailNotifier.EXTRA_LOG_ID, -1L)
        if (id < 0) return
        val app = context.applicationContext as? BrokeApp ?: return
        // The notification goes at once; the mail server is told in the background.
        app.container.mailNotifier.cancel(id)
        val result = goAsync()
        app.scope.launch {
            try {
                if (intent.action == MailNotifier.ACTION_MAIL_ARCHIVE) app.container.inboxAgent.archive(id)
                else app.container.inboxAgent.markRead(id)
            } finally {
                result.finish()
            }
        }
    }
}
