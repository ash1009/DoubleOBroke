package app.brokeometer

import android.app.Application
import app.brokeometer.capture.AiGuesser
import app.brokeometer.capture.CaptureCoordinator
import app.brokeometer.capture.MoneyAgent
import app.brokeometer.capture.LocationFetcher
import app.brokeometer.capture.Notifier
import app.brokeometer.capture.NudgeWatcher
import app.brokeometer.capture.VoiceAdder
import app.brokeometer.limits.LimitStore
import app.brokeometer.limits.LimitWatcher
import app.brokeometer.limits.UsageReader
import app.brokeometer.travel.TravelAgent
import app.brokeometer.travel.TravelCheckWorker
import app.brokeometer.travel.TravelDatabase
import app.brokeometer.travel.TravelTracker
import app.brokeometer.data.AppDatabase
import app.brokeometer.data.BackupManager
import app.brokeometer.data.ClaudeClient
import app.brokeometer.mail.MailAgent
import app.brokeometer.mail.InboxAgent
import app.brokeometer.vault.VaultManager
import app.brokeometer.mail.MailDatabase
import app.brokeometer.mail.MailNotifier
import app.brokeometer.mail.MailReader
import app.brokeometer.mail.StarredMail
import app.brokeometer.news.NewsAgent
import app.brokeometer.news.NewsWorker
import app.brokeometer.data.FxUpdater
import app.brokeometer.data.GeminiClient
import app.brokeometer.data.Prefs
import app.brokeometer.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BrokeApp : Application() {

    /** Work that must outlive a screen or a broadcast, such as saving a payment. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannel()
        container.mailNotifier.ensureChannels()
        scope.launch {
            container.repository.ensureSeeded()
            container.fx.refresh()
        }
        container.nudges.start(scope)
        if (container.prefs.backupFolder.value != null) BackupManager.schedule(this)
        if (container.prefs.mailAgentOn.value) InboxAgent.schedule(this)
        if (container.news.on.value) NewsWorker.schedule(this)
        TravelTracker.ensureChannel(this)
        if (container.prefs.trackTrips.value) {
            TravelTracker.sync(this)
            TravelCheckWorker.schedule(this)
        }
        // App limits carries on watching (Android may not allow starting it from here; it's tried again on opening the app).
        runCatching { LimitWatcher.sync(this) }
    }
}

class AppContainer(app: BrokeApp) {
    val prefs = Prefs(app)
    val database: AppDatabase = AppDatabase.create(app)
    val repository = Repository(database)
    val notifier = Notifier(app, prefs)
    val location = LocationFetcher(app)
    val gemini = GeminiClient(prefs)
    val claude = ClaudeClient(prefs)
    val ai = AiGuesser(repository, claude, gemini)
    val money = MoneyAgent(database, repository, prefs, notifier)
    val capture = CaptureCoordinator(repository, notifier, location, prefs, app.scope, ai, money)
    val fx = FxUpdater(repository.dao)
    val nudges = NudgeWatcher(repository.dao, prefs, notifier)
    val vault = VaultManager(app, app.scope)
    val backups = BackupManager(app, repository, prefs, vault)
    val mailDatabase: MailDatabase = MailDatabase.create(app)
    val mail = MailAgent(app, mailDatabase.dao(), prefs, claude, gemini, app.scope)
    val mailNotifier = MailNotifier(app)
    val inboxAgent = InboxAgent(app, mailDatabase.dao(), prefs, mail, mailNotifier, app.scope, repository)
    val mailReader = MailReader(mailDatabase.dao(), prefs, mail)
    val starredMail = StarredMail(mailDatabase.dao(), prefs, mail)
    val news = NewsAgent(app, claude)
    val travelDatabase: TravelDatabase = TravelDatabase.create(app)
    val travel = TravelAgent(app, travelDatabase.dao(), repository, prefs).also { capture.travel = it }
    val limits = LimitStore(app)
    val usage = UsageReader(app)
    val voice = VoiceAdder(repository) { system, prompt -> mail.askText(system, prompt, maxTokens = 300) }
}
