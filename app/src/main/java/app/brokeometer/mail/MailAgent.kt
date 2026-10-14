package app.brokeometer.mail

import app.brokeometer.BuildConfig
import app.brokeometer.data.ClaudeClient
import app.brokeometer.data.GeminiClient
import app.brokeometer.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** What the email side is busy with, for the progress card. */
data class MailProgress(
    val stage: Stage = Stage.IDLE,
    val account: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
) {
    enum class Stage { IDLE, READING, SORTING, TIDYING, DONE, FAILED }

    val busy: Boolean get() = stage == Stage.READING || stage == Stage.SORTING || stage == Stage.TIDYING
}

sealed interface UnsubscribeOutcome {
    data object Done : UnsubscribeOutcome
    data class OpenWeb(val url: String) : UnsubscribeOutcome
    data class OpenMail(val uri: String) : UnsubscribeOutcome
    data class Failed(val message: String) : UnsubscribeOutcome
}

/**
 * The email side of the app. For now it reads who emails the owner, has the AI group them into
 * categories, and lists mailing lists. It does not change any email yet.
 */
class MailAgent(
    private val context: Context,
    private val dao: MailDao,
    private val prefs: Prefs,
    private val claude: ClaudeClient,
    private val gemini: GeminiClient,
    private val scope: CoroutineScope,
    private val client: MailClient = MailClient(),
) {
    private val _progress = MutableStateFlow(MailProgress())
    val progress: StateFlow<MailProgress> = _progress
    private var job: Job? = null

    fun server(account: MailAccountEntity) = MailServer(account.host, account.port, account.ssl)

    /** Checks the password works, then saves the account. Returns what went wrong, or null. */
    suspend fun addAccount(address: String, provider: String, password: String, custom: MailServer? = null): String? {
        val clean = address.trim().lowercase()
        if (!clean.contains('@')) return "That does not look like an email address."
        val server = custom ?: MailProviders.servers[provider] ?: return "Pick Gmail or Yahoo."
        val pass = password.replace(" ", "").trim()
        if (pass.isEmpty()) return "Paste the app password."
        withContext(Dispatchers.IO) { client.check(server, clean, pass) }?.let { return it }
        prefs.setMailPassword(clean, pass)
        val existing = dao.accounts().firstOrNull { it.address == clean }
        if (existing == null) {
            dao.insertAccount(
                MailAccountEntity(
                    address = clean,
                    provider = if (custom != null) MailProviders.CUSTOM else provider,
                    host = server.host,
                    port = server.port,
                    ssl = server.ssl,
                    addedAt = System.currentTimeMillis(),
                )
            )
        }
        return null
    }

    suspend fun removeAccount(account: MailAccountEntity) {
        dao.deleteSendersOf(account.id)
        dao.deleteAccount(account.id)
        prefs.setMailPassword(account.address, null)
    }

    /** Which mailboxes a run reads before sorting. */
    enum class Read { ALL, NEW, NONE }

    /**
     * Reads the mailboxes and sorts any sender without a category. It runs as a foreground job with
     * its own notification, so it carries on when the app is closed. [fresh] makes the categories again.
     */
    fun readAndSort(fresh: Boolean = false, read: Read = Read.ALL) {
        if (job?.isActive == true || _progress.value.busy) return
        _progress.value = MailProgress(
            if (read == Read.NONE) MailProgress.Stage.SORTING else MailProgress.Stage.READING,
            message = "Starting",
        )
        MailSortWorker.start(context, fresh, read)
    }

    /** Sorts again without reading the mailboxes. */
    fun sortOnly(fresh: Boolean) = readAndSort(fresh, Read.NONE)

    /** Goes through every unread email already in the inboxes, as a background job with a notification. */
    fun tidyUp() {
        if (job?.isActive == true || _progress.value.busy) return
        _progress.value = MailProgress(MailProgress.Stage.TIDYING, message = "Starting")
        MailSortWorker.startTidy(context)
    }

    /** For the tidy-up, which runs in [InboxAgent]. */
    fun report(progress: MailProgress) {
        _progress.value = progress
    }

    fun stop() {
        job?.cancel()
        MailSortWorker.stop(context)
        _progress.value = MailProgress(MailProgress.Stage.IDLE, message = "Stopped.")
    }

    /** The work itself, called by [MailSortWorker]. One account failing does not stop the others. */
    suspend fun run(fresh: Boolean, read: Read) {
        val ctx = coroutineContext
        job = ctx[Job]
        val problems = ArrayList<String>()
        try {
            if (read != Read.NONE) {
                for (account in dao.accounts()) {
                    if (!ctx.isActive) return
                    if (read == Read.NEW && account.scannedAt > 0) continue
                    try {
                        read(account)
                    } catch (e: MailClient.MailError) {
                        problems += "${account.address}: ${e.message}"
                    }
                }
            }
            sort(fresh, problems)
        } catch (e: MailClient.MailError) {
            _progress.value = MailProgress(MailProgress.Stage.FAILED, message = (problems + e.message.orEmpty()).joinToString("\n"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            _progress.value = MailProgress(MailProgress.Stage.IDLE, message = "Stopped.")
            throw e
        } catch (e: Exception) {
            _progress.value = MailProgress(MailProgress.Stage.FAILED, message = "Something went wrong: ${e.javaClass.simpleName}")
        } finally {
            job = null
        }
    }

    private suspend fun read(account: MailAccountEntity) {
        val password = prefs.mailPassword(account.address)
        if (password.isEmpty()) throw MailClient.MailError("The app password for ${account.address} is missing. Remove the account and add it again.")
        _progress.value = MailProgress(MailProgress.Stage.READING, account.address)
        val ctx = coroutineContext
        val tallies = withContext(Dispatchers.IO) {
            client.tallySenders(
                server = server(account),
                address = account.address,
                password = password,
                limit = READ_LIMIT,
                now = System.currentTimeMillis(),
                keepGoing = { ctx.isActive },
                progress = { done, planned ->
                    _progress.value = MailProgress(MailProgress.Stage.READING, account.address, done, planned)
                },
            )
        }
        if (!ctx.isActive) return
        val existing = dao.sendersOf(account.id).associateBy { it.address }
        val rows = tallies.values.map { t ->
            val old = existing[t.address]
            MailSenderEntity(
                id = old?.id ?: 0,
                accountId = account.id,
                address = t.address,
                name = t.name.ifBlank { old?.name.orEmpty() },
                categoryId = old?.categoryId,
                categorySource = old?.categorySource ?: SOURCE_AI,
                total = t.total,
                unread = t.unread,
                recent = t.recent,
                firstAt = t.firstAt,
                lastAt = t.lastAt,
                subjects = t.recentSubjects().joinToString("\n"),
                unsubscribe = t.unsubscribe,
                oneClick = t.oneClick,
                unsubscribedAt = old?.unsubscribedAt,
            )
        }
        rows.chunked(500).forEach { dao.upsertSenders(it) }
        dao.updateAccount(account.copy(scannedAt = System.currentTimeMillis(), scannedCount = tallies.values.sumOf { it.total }))
    }

    private suspend fun sort(fresh: Boolean, problems: MutableList<String> = ArrayList()) {
        if (fresh) {
            dao.clearAiCategories()
            // Senders the owner moved by hand keep their category name if it comes back.
        }
        var categories = dao.categories()
        val senders = dao.senders()
        if (senders.isEmpty()) {
            _progress.value = MailProgress(MailProgress.Stage.DONE, message = "No emails found yet.")
            return
        }
        val byAddress = senders.groupBy { it.address }
        val summaries = byAddress.map { (address, rows) ->
            val newest = rows.maxBy { it.lastAt }
            SenderSummary(
                name = newest.name,
                address = address,
                emails = rows.sumOf { it.total },
                mailingList = rows.any { it.unsubscribe != null },
                subjects = rows.flatMap { it.subjects.lines() }.filter { it.isNotBlank() }.distinct(),
            )
        }.sortedByDescending { it.emails }

        if (fresh || categories.isEmpty()) {
            _progress.value = MailProgress(MailProgress.Stage.SORTING, message = "Making categories")
            val reply = askPatiently(MailPrompts.designCategories(summaries.take(DESIGN_SENDERS)), smart = true, maxTokens = 3000)
            val ideas = when (reply) {
                is Brain.Answer.Text -> MailPrompts.parseCategories(reply.text)
                is Brain.Answer.Failed -> throw MailClient.MailError(reply.message)
            }
            if (ideas.isEmpty()) throw MailClient.MailError("The AI did not come back with categories. Try again.")
            val oldByName = categories.associateBy { it.name.lowercase() }
            val moved = senders.filter { it.categorySource == SOURCE_USER }
                .mapNotNull { s -> categories.firstOrNull { it.id == s.categoryId }?.name?.lowercase()?.let { s to it } }
            dao.deleteCategories()
            ideas.forEachIndexed { i, idea ->
                // A switch the owner already set keeps its setting.
                val important = oldByName[idea.name.lowercase()]?.important ?: idea.important
                dao.insertCategory(MailCategoryEntity(name = idea.name, description = idea.description, important = important, position = i))
            }
            categories = dao.categories()
            val newByName = categories.associateBy { it.name.lowercase() }
            for ((sender, name) in moved) dao.setSenderCategory(sender.id, newByName[name]?.id, if (newByName[name] != null) SOURCE_USER else SOURCE_AI)
        }

        val validIds = categories.map { it.id }.toSet()
        val todo = summaries.filter { s ->
            byAddress[s.address].orEmpty().any { it.categorySource != SOURCE_USER && (it.categoryId == null || it.categoryId !in validIds) }
        }
        val pairs = categories.map { it.name to it.description }
        val names = categories.map { it.name }
        val sorted = HashSet<String>()
        var lastProblem: String? = null
        // The AI sometimes skips a few senders in a long list, so leftovers get up to two more goes in smaller lists.
        var left = todo
        for ((pass, size) in listOf(ASSIGN_CHUNK, ASSIGN_CHUNK / 2, ASSIGN_CHUNK / 4).withIndex()) {
            if (left.isEmpty()) break
            for (chunk in left.chunked(size)) {
                if (!coroutineContext.isActive) return
                _progress.value = MailProgress(
                    MailProgress.Stage.SORTING, done = sorted.size, total = todo.size,
                    message = if (pass == 0) brainName() else "${brainName()}, second look",
                )
                val map = when (val reply = askPatiently(MailPrompts.assignSenders(pairs, chunk), smart = false, maxTokens = 4000)) {
                    is Brain.Answer.Text -> MailPrompts.parseAssignments(reply.text, chunk.size, categories.size, names)
                        .also { if (it.isEmpty()) lastProblem = "The AI's answer could not be read." }
                    is Brain.Answer.Failed -> {
                        lastProblem = reply.message
                        // A bad key or no credit will not fix itself mid-run.
                        if (!retryable(reply.message)) break
                        emptyMap()
                    }
                }
                for ((i, c) in map) {
                    val categoryId = categories[c].id
                    byAddress[chunk[i].address].orEmpty()
                        .filter { it.categorySource != SOURCE_USER }
                        .forEach { dao.setSenderCategory(it.id, categoryId, SOURCE_AI) }
                    sorted += chunk[i].address
                }
            }
            left = left.filter { it.address !in sorted }
        }
        _progress.value = MailProgress(MailProgress.Stage.SORTING, done = sorted.size, total = todo.size, message = brainName())
        val notSorted = todo.size - sorted.size
        val summary = buildList {
            add(
                if (todo.isEmpty()) "Done. Every sender already has a category."
                else "Sorted ${"%,d".format(sorted.size)} of ${"%,d".format(todo.size)} senders into ${categories.size} categories."
            )
            if (notSorted > 0) add("${"%,d".format(notSorted)} not sorted yet" + (lastProblem?.let { ": $it" } ?: ".") + " Press Sort the rest to try them again.")
            addAll(problems.map { "Could not read $it" })
        }.joinToString("\n")
        _progress.value = MailProgress(
            if (notSorted > 0 || problems.isNotEmpty()) MailProgress.Stage.FAILED else MailProgress.Stage.DONE,
            message = summary,
        )
    }

    /** Asks again after a pause when the AI is busy or rate limited, rather than giving up. */
    suspend fun askPatiently(prompt: String, smart: Boolean, maxTokens: Int): Brain.Answer {
        var reply = brain().ask(MailPrompts.SYSTEM, prompt, smart, maxTokens)
        var attempt = 0
        while (reply is Brain.Answer.Failed && retryable(reply.message) && attempt < 4) {
            attempt++
            _progress.value = _progress.value.copy(message = "${brainName()} is busy, waiting a moment")
            kotlinx.coroutines.delay(20_000L * attempt)
            reply = brain().ask(MailPrompts.SYSTEM, prompt, smart, maxTokens)
        }
        return reply
    }

    private fun retryable(message: String): Boolean =
        listOf("too many", "busy", "try again", "free limit", "internet", "problems").any { message.contains(it, ignoreCase = true) }

    suspend fun setImportant(category: MailCategoryEntity, on: Boolean) = dao.updateCategory(category.copy(important = on))

    suspend fun moveSender(address: String, categoryId: Long) {
        dao.senders().filter { it.address == address }.forEach { dao.setSenderCategory(it.id, categoryId, SOURCE_USER) }
    }

    /** One-click lists are left straight from the phone; others open their page or an email to send. */
    suspend fun unsubscribe(sender: MailSenderEntity): UnsubscribeOutcome {
        val ways = UnsubscribeWays.parse(sender.unsubscribe, sender.oneClick)
        if (ways.oneClick && ways.web != null) {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL(ways.web).openConnection() as HttpURLConnection
                    try {
                        c.requestMethod = "POST"
                        c.connectTimeout = 15_000
                        c.readTimeout = 20_000
                        c.doOutput = true
                        c.instanceFollowRedirects = true
                        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        c.outputStream.use { it.write("List-Unsubscribe=One-Click".toByteArray()) }
                        c.responseCode in 200..399
                    } finally {
                        c.disconnect()
                    }
                }.getOrDefault(false)
            }
            if (ok) {
                markUnsubscribed(sender.address)
                return UnsubscribeOutcome.Done
            }
        }
        return when {
            ways.web != null -> UnsubscribeOutcome.OpenWeb(ways.web)
            ways.mail != null -> UnsubscribeOutcome.OpenMail(ways.mail)
            else -> UnsubscribeOutcome.Failed("This sender gives no way to unsubscribe. You could block them in Gmail or Yahoo instead.")
        }
    }

    suspend fun markUnsubscribed(address: String) {
        val now = System.currentTimeMillis()
        dao.senders().filter { it.address == address }.forEach { dao.markUnsubscribed(it.id, now) }
    }

    // The AI

    /** A quick question for the agent, with whichever AI is set up. */
    suspend fun ask(prompt: String, maxTokens: Int): Brain.Answer = brain().ask(MailPrompts.SYSTEM, prompt, smart = false, maxTokens = maxTokens)

    /** A quick question to the same AI the email uses (Claude, or Gemini), for other parts of the app. Null when there is none or it failed. */
    suspend fun askText(system: String, prompt: String, maxTokens: Int): String? =
        (brain().ask(system, prompt, smart = false, maxTokens = maxTokens) as? Brain.Answer.Text)?.text

    private val _problem = MutableStateFlow<String?>(null)
    /** The last thing that went wrong in the background, shown on the Activity tab. */
    val problem: StateFlow<String?> = _problem

    fun note(message: String?) {
        _problem.value = message
    }

    /** Takes down a warning about [address] once that account works again. */
    fun clearNoteAbout(address: String) {
        if (_problem.value?.contains(address, ignoreCase = true) == true) _problem.value = null
    }

    /** Clears the note if it starts with [prefix]: that problem has gone away. */
    fun clearNoteStarting(prefix: String) {
        if (_problem.value?.startsWith(prefix) == true) _problem.value = null
    }

    /** Claude while there is a key and credit, otherwise Gemini. */
    private fun brain(): Brain = when {
        BuildConfig.DEBUG && prefs.claudeKey.value == FAKE_KEY -> FakeBrain
        claude.hasKey -> ClaudeThenGemini(claude, gemini)
        gemini.hasKey -> GeminiBrain(gemini)
        else -> NoBrain
    }

    fun brainName(): String = when {
        BuildConfig.DEBUG && prefs.claudeKey.value == FAKE_KEY -> "Test sorter"
        claude.hasKey -> "Claude"
        gemini.hasKey -> "Gemini"
        else -> "No AI"
    }

    val hasBrain: Boolean get() = claude.hasKey || gemini.hasKey

    companion object {
        const val SOURCE_AI = "AI"
        const val SOURCE_USER = "USER"
        /** The newest emails read from each account. */
        const val READ_LIMIT = 15_000
        private const val DESIGN_SENDERS = 300
        private const val ASSIGN_CHUNK = 120
        /** Only in test builds: sorts by simple rules instead of calling an AI. */
        const val FAKE_KEY = "test-fake"
    }
}

/** One way of asking an AI a question. */
interface Brain {
    sealed interface Answer {
        data class Text(val text: String) : Answer
        data class Failed(val message: String) : Answer
    }

    suspend fun ask(system: String, prompt: String, smart: Boolean, maxTokens: Int): Answer
}

private class ClaudeThenGemini(private val claude: ClaudeClient, private val gemini: GeminiClient) : Brain {
    override suspend fun ask(system: String, prompt: String, smart: Boolean, maxTokens: Int): Brain.Answer =
        when (val r = claude.ask(system, prompt, smart, maxTokens)) {
            is ClaudeClient.Reply.Text -> Brain.Answer.Text(r.text)
            is ClaudeClient.Reply.Failed ->
                if (r.noCredit && gemini.hasKey) GeminiBrain(gemini).ask(system, prompt, smart, maxTokens)
                else Brain.Answer.Failed(r.message)
        }
}

private class GeminiBrain(private val gemini: GeminiClient) : Brain {
    override suspend fun ask(system: String, prompt: String, smart: Boolean, maxTokens: Int): Brain.Answer =
        when (val r = gemini.ask("$system\n\n$prompt")) {
            is GeminiClient.Reply.Text -> Brain.Answer.Text(r.text)
            is GeminiClient.Reply.Failed -> Brain.Answer.Failed(r.message)
        }
}

private object NoBrain : Brain {
    override suspend fun ask(system: String, prompt: String, smart: Boolean, maxTokens: Int): Brain.Answer =
        Brain.Answer.Failed("Add a Claude key (or a Gemini key in Settings) so the app can sort your email.")
}

/** Test builds only: fixed categories and simple rules, so the whole flow can run on an emulator. */
private object FakeBrain : Brain {
    override suspend fun ask(system: String, prompt: String, smart: Boolean, maxTokens: Int): Brain.Answer {
        if (prompt.contains("said this about a payment")) {
            val ids = prompt.lines().mapNotNull { line -> Regex("""^(\d+): (.+)$""").find(line)?.groupValues }
            val pick = ids.firstOrNull { it[2].contains("Transport", ignoreCase = true) } ?: ids.firstOrNull()
            val said = prompt.lines().getOrNull(1).orEmpty().lowercase()
            val amount = Regex("""\d+(\.\d+)?""").find(said)?.value ?: "null"
            val (where, place) = if ("metro" in said) "Metro" to "Vauxhall" else "Pret" to ""
            return Brain.Answer.Text("""{"amount": $amount, "currency": "GBP", "where": "$where", "place": "$place", "note": "", "id": ${pick?.get(1) ?: 0}, "days_ago": 0}""")
        }
        // An answer that can't be read, as a real AI sometimes gives, for one email: the agent must carry on past it.
        val oneEmail = prompt.startsWith("This email is in the spam folder") || prompt.contains("Pick the one category this email belongs to")
        if (oneEmail && prompt.contains("unreadable", ignoreCase = true)) return Brain.Answer.Text("Hard to say, sorry.")
        if (prompt.startsWith("This email is in the spam folder")) {
            val keep = prompt.contains("invoice", ignoreCase = true) || prompt.contains("interview", ignoreCase = true)
            return Brain.Answer.Text(if (keep) """{"spam":false,"why":"Looks like a real invoice"}""" else """{"spam":true,"why":"Prize scam"}""")
        }
        if (prompt.contains("Pick the one category this email belongs to")) {
            val names = prompt.lines().takeWhile { it.isNotBlank() }.drop(1).map { it.substringAfter(". ").substringBefore(":").lowercase() }
            val email = prompt.substringAfter("Email:").substringBefore("Pick the one category").lowercase()
            // A promotions sender writing about your own viewing request: filed as a promotion, but personal.
            val personal = "viewing" in email
            val want = when {
                personal -> "promotions"
                "security" in email || "sign in" in email -> "bank alerts"
                "bill" in email -> "bills"
                "% off" in email || "sale" in email || "offer" in email -> "promotions"
                "reading" in email || "newsletter" in email -> "newsletters"
                else -> "real people"
            }
            val index = names.indexOf(want).coerceAtLeast(0)
            return Brain.Answer.Text("""{"category":$index,"personal":$personal,"summary":"Test summary for $want"}""")
        }
        if (prompt.contains("Make a list of")) {
            return Brain.Answer.Text(
                """{"categories":[
                {"name":"Real people","description":"Emails written by a person","important":true},
                {"name":"Bank alerts","description":"Security and account alerts","important":true},
                {"name":"Bills","description":"Bills due and payments","important":true},
                {"name":"Newsletters","description":"Regular reading","important":false},
                {"name":"Promotions","description":"Sales and offers","important":false}]}"""
            )
        }
        val senders = prompt.lines().filter { it.startsWith("s") && it.contains(". ") && it.contains("|") }
        val pairs = senders.mapNotNull { line ->
            val key = line.substringBefore('.').trim()
            val text = line.lowercase()
            val category = when {
                "bank" in text || "security" in text -> 1
                "bill" in text || "invoice" in text -> 2
                "sale" in text || "% off" in text || "offer" in text -> 4
                "mailing list" in text || "reading" in text || "newsletter" in text -> 3
                else -> 0
            }
            "\"$key\":$category"
        }
        val personal = senders.filter { "viewing" in it.lowercase() }.map { "\"${it.substringBefore('.').trim()}\"" }
        return Brain.Answer.Text("""{"a":{${pairs.joinToString(",")}},"p":[${personal.joinToString(",")}]}""")
    }
}
