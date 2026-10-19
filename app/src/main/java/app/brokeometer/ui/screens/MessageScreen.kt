package app.brokeometer.ui.screens

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.core.content.FileProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.mail.FullEmail
import app.brokeometer.mail.MailAccountEntity
import app.brokeometer.mail.MailActions
import app.brokeometer.mail.MailProviders
import app.brokeometer.mail.MailReader
import app.brokeometer.mail.StarredMail
import app.brokeometer.ui.MailViewModel
import app.brokeometer.ui.MessageViewModel
import app.brokeometer.ui.components.QuietButton
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import kotlinx.coroutines.launch

/**
 * One email in full: what the agent did with it and why, buttons for it, then the email itself.
 * Pictures from the internet stay off until "Show pictures", so senders can't tell it was opened.
 */
@Composable
fun MessageScreen(
    onBack: () -> Unit,
    onReply: (Long) -> Unit,
    page: MessageViewModel = viewModel(),
    vm: MailViewModel = viewModel(),
) {
    val ids = page.ids
    val working by vm.working.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Swipe left for the next email in the Activity list, right for the one before.
    val pager = rememberPagerState(initialPage = ids.indexOf(page.id).coerceAtLeast(0)) { ids.size }
    val currentId = ids.getOrElse(pager.currentPage) { page.id }
    val entry by remember(currentId) { page.entry(currentId) }.collectAsStateWithLifecycle(null)

    // Opening an email counts as reading it, once each (marking it unread again here is left alone).
    var readIds by rememberSaveable { mutableStateOf(listOf<Long>()) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p ->
            val id = ids.getOrNull(p) ?: return@collect
            page.prefetchAround(p)
            if (id !in readIds) {
                val log = page.log(id) ?: return@collect
                readIds = readIds + id
                vm.openedRead(log)
            }
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.done.collect { d ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val r = snackbar.showSnackbar(d.message, actionLabel = if (d.undo != null) "Undo" else null, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) d.undo?.invoke()
            }
        }
    }

    val e = entry
    val busy = e != null && e.id in working
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BrokeTopBar(title = if (ids.size > 1) "${pager.currentPage + 1} of ${ids.size}" else "", onBack = onBack) {
                if (e != null) {
                    when {
                        busy -> CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(22.dp))
                        MailActions.leftUnread(e) -> IconButton(onClick = { vm.markRead(e) }) {
                            Icon(Icons.Outlined.Drafts, contentDescription = "Mark read", tint = brand.action)
                        }
                        MailActions.canMarkUnread(e) -> IconButton(onClick = { vm.markUnread(e) }) {
                            Icon(Icons.Outlined.Email, contentDescription = "Mark unread", tint = muted)
                        }
                    }
                    if (e.id > 0) {
                        IconButton(onClick = { onReply(e.id) }) {
                            Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = "Reply", tint = brand.action)
                        }
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(padding),
            beyondViewportPageCount = 1,
            key = { ids[it] },
        ) { p ->
            MessagePage(ids[p], page, vm, onReply, current = p == pager.currentPage)
        }
    }
}

/** One email of the ones you can swipe through. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessagePage(id: Long, page: MessageViewModel, vm: MailViewModel, onReply: (Long) -> Unit, current: Boolean) {
    val entry by remember(id) { page.entry(id) }.collectAsStateWithLifecycle(null)
    val accounts by page.accounts.collectAsStateWithLifecycle()
    val email by remember(id) { page.email(id) }.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val quietSenders by vm.quietSenders.collectAsStateWithLifecycle()
    val importantSenders by vm.importantSenders.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val starredRows by vm.starred.collectAsStateWithLifecycle()
    val starring by vm.starring.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val e = entry
    if (e == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) { Text("This email isn't on the list any more.") }
        return
    }
    val account = accounts.firstOrNull { it.id == e.accountId }
    val busy = e.id in working
    val full = (email as? MailReader.Opened.Email)?.email
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        // Who, what about, to which account and when.
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(e.fromName.ifBlank { e.fromAddress }, style = MaterialTheme.typography.headlineSmall)
            if (e.fromName.isNotBlank()) Text(e.fromAddress, style = MaterialTheme.typography.bodyMedium, color = muted)
            Spacer(Modifier.height(10.dp))
            Text(e.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("To ${account?.address ?: "you"}")
                    full?.cc?.takeIf { it.isNotBlank() }?.let { append(", cc $it") }
                    append(", ${Dates.pretty(Dates.dayOf(full?.date?.takeIf { it > 0 } ?: e.at))} ${Dates.clock(full?.date?.takeIf { it > 0 } ?: e.at)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }

        // What the agent did, and why.
        val (label, color) = outcomeOf(e)
        val quiet = e.fromAddress.lowercase() in quietSenders
        val vip = e.fromAddress.lowercase() in importantSenders
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
        ) {
            Text(label + if (e.categoryName.isNotBlank() && e.folder == "inbox") ", ${e.categoryName}" else "", style = MaterialTheme.typography.titleSmall, color = color)
            if (e.summary.isNotBlank() && e.summary != e.subject) {
                Spacer(Modifier.height(4.dp))
                Text(e.summary, style = MaterialTheme.typography.bodyMedium)
            }
            if (e.cleared) {
                Spacer(Modifier.height(4.dp))
                Text("The email app's own notification was cleared.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (quiet) {
                Spacer(Modifier.height(4.dp))
                Text("You said this sender isn't important, so their emails are marked read as they arrive.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (vip) {
                Spacer(Modifier.height(4.dp))
                Text("You said this sender is important, so their emails always reach you.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (current && problem != null) {
                Spacer(Modifier.height(6.dp))
                Text(problem!!, style = MaterialTheme.typography.bodySmall, color = brand.broke)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A starred email the agent never handled can only be read and starred here.
                if (e.id > 0) {
                QuietButton("Reply", onClick = { onReply(e.id) }, height = 40.dp)
                when {
                    MailActions.leftUnread(e) -> QuietButton("Mark read", onClick = { vm.markRead(e) }, enabled = !busy, height = 40.dp)
                    MailActions.canMarkUnread(e) -> QuietButton("Mark unread", onClick = { vm.markUnread(e) }, enabled = !busy, height = 40.dp)
                }
                if (!e.undone && (e.action == MailActions.SPAM_FLAGGED || e.action == MailActions.SPAM_UNCHECKED)) {
                    QuietButton("It's spam", onClick = { vm.markSpam(e) }, enabled = !busy, height = 40.dp)
                    QuietButton("Not spam", onClick = { vm.notSpam(e) }, enabled = !busy, height = 40.dp)
                } else if (!e.undone && e.action == MailActions.SPAM_DELETED && e.messageId != null) {
                    QuietButton("Not spam", onClick = { vm.notSpam(e) }, enabled = !busy, height = 40.dp)
                }
                if (MailActions.canDelete(e)) QuietButton("Delete", onClick = { vm.delete(e) }, enabled = !busy, height = 40.dp, color = brand.broke)
                if (MailActions.binned(e)) QuietButton("Put back in inbox", onClick = { vm.restore(e) }, enabled = !busy, height = 40.dp)
                if (MailActions.inInbox(e)) {
                    when {
                        vip -> QuietButton("Sender back to normal", onClick = { vm.notImportantSender(e) }, height = 40.dp)
                        quiet -> QuietButton("They matter again", onClick = { vm.unquietSender(e) }, height = 40.dp)
                        else -> {
                            QuietButton("Sender is important", onClick = { vm.importantSender(e) }, enabled = !busy, height = 40.dp)
                            QuietButton("Sender not important", onClick = { vm.quietSender(e) }, enabled = !busy, height = 40.dp)
                        }
                    }
                }
                }
                if (account?.provider == MailProviders.GMAIL || account?.provider == MailProviders.YAHOO) {
                    QuietButton(
                        if (account?.provider == MailProviders.GMAIL) "Open in Gmail" else "Open in Yahoo",
                        onClick = { openInMailApp(context, account, e.messageId) },
                        height = 40.dp,
                    )
                }
            }
        }

        // The email itself.
        when (val opened = email) {
            null -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Opening the email...", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            is MailReader.Opened.Problem -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(opened.message, style = MaterialTheme.typography.bodyMedium, color = muted)
                Spacer(Modifier.height(8.dp))
                QuietButton("Try again", onClick = { page.load(id) }, height = 40.dp)
            }
            is MailReader.Opened.Email -> {
                val starred = vm.isStarred(e.accountId, e.messageId, starredRows, starring)
                val changing = e.messageId != null && MailViewModel.starKey(e.accountId, e.messageId) in starring
                EmailBody(opened.email, open = { i -> page.attachment(id, i) }) {
                    if (e.messageId != null) {
                        IconButton(
                            onClick = {
                                vm.setStar(
                                    e.accountId, e.messageId, !starred,
                                    StarredMail.About(e.fromName, e.fromAddress, e.subject, opened.email.date.takeIf { it > 0 } ?: e.at, seen = true),
                                )
                            },
                            enabled = !changing,
                        ) {
                            Icon(
                                if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = if (starred) "Unstar" else "Star",
                                tint = if (starred) brand.action else muted,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmailBody(email: FullEmail, open: suspend (Int) -> MailReader.Saved?, star: @Composable () -> Unit = {}) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val brand = LocalBrand.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pictures by rememberSaveable { mutableStateOf(false) }
    var fetching by remember { mutableStateOf<Int?>(null) }
    if (email.attachments.isNotEmpty()) {
        // Attached files: tap one to open it with an app on the phone (from there it can be saved or shared).
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            email.attachments.forEachIndexed { i, name ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(enabled = fetching == null) {
                            fetching = i
                            scope.launch {
                                val saved = open(i)
                                fetching = null
                                if (saved == null) {
                                    Toast.makeText(context, "Couldn't get that file. Try again.", Toast.LENGTH_SHORT).show()
                                } else {
                                    openFile(context, saved)
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (fetching == i) {
                        CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
    val html = email.html
    // The star, with Show pictures beside it when the email has pictures from the internet.
    Row(Modifier.padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        star()
        if (html != null && email.loadsFromWeb && !pictures) {
            Spacer(Modifier.width(4.dp))
            // The button on the left, easy to reach with the left thumb.
            QuietButton("Show pictures", onClick = { pictures = true }, height = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text("Pictures are off, so the sender can't see you opened it.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
        }
    }
    if (html != null) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.White),
        ) {
            HtmlView(html, pictures)
        }
    } else {
        Text(
            email.text?.replace("\r", "")?.trim().orEmpty().ifEmpty { "This email has no text." },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

/** The email's own HTML, with scripts off and links opening in the browser. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlView(html: String, pictures: Boolean) {
    val page = remember(html) { prepared(html) }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = false
                isVerticalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        try {
                            view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: ActivityNotFoundException) {
                            // Nothing on the phone opens that kind of link.
                        }
                        return true
                    }
                }
            }
        },
        update = { view ->
            val key = "${page.hashCode()}-$pictures"
            if (view.tag != key) {
                view.tag = key
                view.settings.blockNetworkLoads = !pictures
                view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * Emails written for phones say how wide to show them; others are drawn about 600 pixels wide and are
 * shrunk to fit. Plain ones get the phone's width so the text isn't tiny.
 */
private fun prepared(html: String): String {
    val hasViewport = Regex("""(?i)<meta[^>]+name\s*=\s*["']?viewport""").containsMatchIn(html)
    val wide = Regex("""(?i)width\s*[:=]\s*["']?(5[5-9]\d|[6-9]\d\d)""").containsMatchIn(html)
    return buildString {
        append("<meta charset=\"utf-8\">")
        if (!hasViewport && !wide) append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<style>body{margin:14px;font-family:sans-serif;color:#202124;background:#ffffff;word-wrap:break-word} img{max-width:100%;height:auto} pre{white-space:pre-wrap}</style>")
        append(html)
    }
}

/** Opens a fetched attachment with whichever app on the phone handles that kind of file. */
private fun openFile(context: Context, saved: MailReader.Saved) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", saved.file)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, saved.type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(view, saved.file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone opens that kind of file.", Toast.LENGTH_SHORT).show()
    }
}

/** Opens the email in Gmail (straight to it when Gmail can), or opens Yahoo Mail. */
private fun openInMailApp(context: Context, account: MailAccountEntity?, messageId: String?) {
    val gmail = "com.google.android.gm"
    val yahoo = "com.yahoo.mobile.client.android.mail"
    if (account?.provider == MailProviders.GMAIL && messageId != null) {
        val id = messageId.trim().removePrefix("<").removeSuffix(">")
        val url = "https://mail.google.com/mail/u/${Uri.encode(account.address)}/#search/rfc822msgid%3A${Uri.encode(id)}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(gmail).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // Gmail doesn't open links like this one; open the app instead.
        }
    }
    val pkg = when (account?.provider) {
        MailProviders.GMAIL -> gmail
        MailProviders.YAHOO -> yahoo
        else -> null
    }
    val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (launch != null) {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else {
        Toast.makeText(context, "The email app isn't on this phone.", Toast.LENGTH_SHORT).show()
    }
}
