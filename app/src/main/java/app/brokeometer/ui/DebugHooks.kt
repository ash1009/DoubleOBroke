package app.brokeometer.ui

import app.brokeometer.travel.StepSampleEntity
import app.brokeometer.limits.AppLimit
import app.brokeometer.limits.LimitWatcher

import app.brokeometer.travel.TravelTracker
import app.brokeometer.travel.TrackPointEntity
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import app.brokeometer.BrokeApp
import app.brokeometer.data.TransactionEntity
import app.brokeometer.data.TxSource
import app.brokeometer.data.TxStatus
import app.brokeometer.domain.Merchants
import app.brokeometer.mail.MailProviders
import app.brokeometer.vault.VaultImport
import app.brokeometer.mail.MailServer
import app.brokeometer.insights.Goals
import app.brokeometer.util.Dates
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Used only by the test build on an emulator, to put the app in a known state for screenshots.
 * [MainActivity] calls this only when BuildConfig.DEBUG is true, so none of it runs in the app
 * that gets installed on a phone.
 *
 *   --es theme light|dark|system   switch theme
 *   --ez test_mode true            read alerts posted from a computer with adb
 *   --ez save_location true        save the phone's location with card payments
 *   --ez demo true                 fill three months with made-up entries
 *   --es username NAME             set the account name
 *   --es import_file NAME          read NAME from the app's own files folder, as Import does
 *   --es export_file NAME          save a backup as NAME in the app's own files folder
 *   --es gemini_key KEY            set a Gemini key (to check backups leave it out)
 *   --es claude_key KEY            set a Claude key; "test-fake" sorts email by simple rules, no AI
 *   --es mail_test "a@b,pass,host,port"  add an email account on a plain test mail server
 *   --ez mail_read true            read the email accounts and sort the senders
 *   --ez mail_agent true|false     turn the email agent on or off
 *   --es vault_master PASS         make the password vault with PASS (or open it, if there is one)
 *   --es vault_note NAME           read NAME from the app's files folder as a pasted note, for review
 *   --es voice_text WORDS          add a payment as if WORDS were spoken ("_" for spaces)
 *   --es attach_test NAME          the next new email starts with NAME (from the app's files folder) attached
 *   --ez travel_demo true          today's noted places become a made-up day: tube there and back, and a bus
 *   --ez track_trips true|false    turn noting trips on or off
 *   --ez limits_fast true          App limits' waits take seconds instead of minutes
 *   --es limits_add "pkg,Label,minutes"  give an app a limit straight away (0 minutes closes it now)
 *   --es goals "savings=800000,limit=70000,nospend=6,until=2027-06-30,cap.food=30000"
 *                                  set goals (amounts in pence, caps by category name)
 */
object DebugHooks {

    /** A file the next new email starts with, for the CI test of sending files. */
    @Volatile var pendingAttach: android.net.Uri? = null

    fun takeAttach(): android.net.Uri? = pendingAttach.also { pendingAttach = null }

    fun apply(intent: Intent, app: BrokeApp, vm: AppViewModel) {
        val prefs = app.container.prefs
        intent.getStringExtra("theme")?.let { prefs.setTheme(it) }
        intent.getStringExtra("username")?.let { prefs.setUsername(it) }
        if (intent.hasExtra("test_mode")) prefs.testMode = intent.getBooleanExtra("test_mode", false)
        if (intent.hasExtra("save_location")) prefs.setSaveLocation(intent.getBooleanExtra("save_location", false))
        if (intent.getBooleanExtra("demo", false)) app.scope.launch { fillDemo(app) }
        intent.getStringExtra("goals")?.let { text -> app.scope.launch { setGoals(app, text) } }
        intent.getStringExtra("gemini_key")?.let { prefs.setGeminiKey(it) }
        intent.getStringExtra("claude_key")?.let { prefs.setClaudeKey(it) }
        intent.getStringExtra("mail_test")?.let { spec ->
            app.scope.launch {
                val parts = spec.split(',')
                if (parts.size == 4) {
                    val problem = app.container.mail.addAccount(
                        parts[0], MailProviders.CUSTOM, parts[1], MailServer(parts[2], parts[3].toInt(), ssl = false),
                    )
                    app.container.repository.log("mail", "test account", parts[0], problem ?: "added")
                }
            }
        }
        if (intent.getBooleanExtra("mail_read", false)) app.container.mail.readAndSort()
        intent.getStringExtra("vault_master")?.let { master ->
            app.scope.launch {
                val vault = app.container.vault
                try {
                    if (vault.exists) vault.unlockWithPassword(master.toCharArray()) else vault.create(master.toCharArray())
                } catch (e: Exception) {
                    // A wrong password in a test run just leaves it locked.
                }
            }
        }
        intent.getStringExtra("voice_text")?.let { vm.addByVoice(it.replace('_', ' ')) }
        if (intent.hasExtra("track_trips")) {
            prefs.setTrackTrips(intent.getBooleanExtra("track_trips", false))
            TravelTracker.sync(app)
        }
        if (intent.getBooleanExtra("travel_demo", false)) app.scope.launch { travelDemo(app) }
        if (intent.hasExtra("limits_fast")) app.container.limits.fast = intent.getBooleanExtra("limits_fast", false)
        intent.getStringExtra("limits_add")?.split(',')?.takeIf { it.size == 3 }?.let { (pkg, label, minutes) ->
            app.container.limits.setNow(AppLimit(pkg, label, minutes.toInt()))
            LimitWatcher.sync(app)
        }
        intent.getStringExtra("attach_test")?.let { name ->
            pendingAttach = android.net.Uri.fromFile(File(app.getExternalFilesDir(null), name))
        }
        intent.getStringExtra("vault_note")?.let { name ->
            app.scope.launch {
                val text = runCatching { File(app.getExternalFilesDir(null), name).readText() }.getOrNull() ?: return@launch
                app.container.vault.prepareImport(VaultImport.fromNote(text))
            }
        }
        if (intent.hasExtra("mail_agent")) {
            val on = intent.getBooleanExtra("mail_agent", false)
            app.scope.launch { app.container.inboxAgent.setOn(on) }
        }
        intent.getStringExtra("export_file")?.let { name ->
            app.scope.launch {
                val text = app.container.backups.backupText()
                File(app.getExternalFilesDir(null), name).writeText(text)
            }
        }
        intent.getStringExtra("import_file")?.let { name ->
            vm.readImport(Uri.fromFile(File(app.getExternalFilesDir(null), name)))
        }
    }

    /** A made-up day of places: home, walk to Southgate, tube to Vauxhall (changing at Green Park), back, then a bus. */
    private suspend fun travelDemo(app: BrokeApp) {
        val dao = app.container.travelDatabase.dao()
        val day = java.time.LocalDate.now()
        val zone = Dates.zone()
        fun at(h: Int, m: Int, s: Int = 0) = day.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()
        val points = ArrayList<TrackPointEntity>()
        fun add(lat: Double, lon: Double, t: Long) {
            points += TrackPointEntity(at = t, lat = lat, lon = lon, accuracy = 15f, speed = null)
        }
        fun stay(p: Pair<Double, Double>, start: Long, minutes: Int) = (0..minutes * 4).forEach { add(p.first, p.second, start + it * 15_000L) }
        fun move(a: Pair<Double, Double>, b: Pair<Double, Double>, start: Long, minutes: Double) {
            val steps = (minutes * 4).toInt()
            (0..steps).forEach { i ->
                val f = i.toDouble() / steps
                add(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, start + i * 15_000L)
            }
        }
        val home = 51.6368 to -0.1335
        val wp = 51.6322 to -0.1280
        val gp = 51.5069 to -0.1426
        val vx = 51.4860 to -0.1240
        val office = 51.4880 to -0.1280
        val stopA = 51.63251 to -0.12771
        val stopB = 51.62795 to -0.09704
        stay(home, at(7, 50), 10)
        move(home, wp, at(8, 0), 8.0)
        stay(wp, at(8, 8), 4)
        stay(gp, at(8, 36), 3)
        move(vx, office, at(8, 50), 5.0)
        stay(office, at(8, 56), 30)
        move(office, vx, at(18, 0), 5.0)
        stay(vx, at(18, 5), 2)
        move(wp, home, at(18, 45), 8.0)
        stay(home, at(18, 54), 5)
        move(stopA, 51.6303 to -0.1115, at(19, 10), 4.0)
        stay(51.6303 to -0.1115, at(19, 14, 15), 1)
        move(51.6303 to -0.1115, stopB, at(19, 15, 30), 4.0)
        stay(stopB, at(19, 20), 3)
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        dao.deletePoints(from, from + 86_400_000L)
        dao.insertPoints(points)
        // The step counter through the same day: 3,900 steps.
        dao.deleteStepSamples(from, from + 86_400_000L)
        dao.insertStepSamples(
            listOf(at(7, 50) to 50_000L, at(8, 10) to 51_150L, at(8, 55) to 51_700L, at(18, 5) to 52_300L, at(18, 55) to 53_400L, at(19, 25) to 53_900L)
                .map { (t, total) -> StepSampleEntity(at = t, total = total) }
        )
    }

    private suspend fun setGoals(app: BrokeApp, text: String) {
        val parts = text.split(';', ',').mapNotNull { part ->
            val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            k.trim() to v.trim()
        }.toMap()
        val categories = app.container.repository.dao.categories()
        val caps = parts.filterKeys { it.startsWith("cap.") }.mapNotNull { (k, v) ->
            val name = k.removePrefix("cap.")
            val id = categories.firstOrNull { it.name.lowercase().filter(Char::isLetter) == name }?.id ?: return@mapNotNull null
            v.toLongOrNull()?.let { id to it }
        }.toMap()
        app.container.prefs.setGoals(
            Goals(
                savingsMinor = parts["savings"]?.toLongOrNull(),
                savingsAt = parts["savings"]?.let { System.currentTimeMillis() - 3 * 24 * 3600 * 1000L },
                lastUntil = parts["until"]?.let { LocalDate.parse(it) },
                monthlyLimitMinor = parts["limit"]?.toLongOrNull(),
                noSpendTarget = parts["nospend"]?.toIntOrNull(),
                categoryCaps = caps,
            )
        )
    }

    private data class Demo(val name: String, val code: String, val pence: Long, val hour: Int)

    private val pool = listOf(
        Demo("Uber Eats", "food.lunch", 1760, 13), Demo("Uber Eats", "food.dinner", 2182, 20),
        Demo("Subway", "food.lunch", 1555, 15), Demo("Taste Of Lahore", "food.dinner", 2250, 20),
        Demo("Sainsburys S/Mkts", "grocery.eatery", 1015, 18), Demo("Tesco Stores", "grocery.eatery", 655, 19),
        Demo("TfL Travel Charge", "travel.metro", 1070, 8), Demo("TfL Travel Charge", "travel.bus", 175, 9),
        Demo("Uber Trip", "travel.cab", 1895, 23), Demo("EE Topup Vesta", "bills.phone", 1000, 11),
        Demo("Google Play Apps", "entertainment.game", 499, 21), Demo("Odeon", "entertainment.movie", 1399, 19),
        Demo("Circuit Go", "personalcare.laundry", 360, 10), Demo("Barber Shop", "personalcare.barber", 2000, 14),
        Demo("NYX GMGVending", "food.others", 300, 16), Demo("Uniqlo", "fashion.clothes", 2990, 15),
    )

    private suspend fun fillDemo(app: BrokeApp) {
        val repository = app.container.repository
        repository.ensureSeeded()
        val dao = repository.dao
        if (dao.learnedRows().isNotEmpty()) return
        val idByCode = dao.subcategories().mapNotNull { s -> s.code?.let { it to s.id } }.toMap()
        val today = LocalDate.now()
        val thisMonth = YearMonth.from(today)
        var n = 0
        app.container.database.withTransaction {
        for (back in 3 downTo 0) {
            val month = thisMonth.minusMonths(back.toLong())
            val lastDay = if (back == 0) today.dayOfMonth else month.lengthOfMonth()
            for (day in 1..lastDay) {
                // Two or three entries a day, picked in a fixed pattern so screenshots are repeatable.
                val count = 2 + (day + back) % 2
                repeat(count) { k ->
                    val demo = pool[(day * 3 + k * 5 + back * 7) % pool.size]
                    val at = month.atDay(day).atTime(LocalTime.of(demo.hour, (day * 7 + k * 11) % 60))
                        .atZone(Dates.zone()).toInstant().toEpochMilli()
                    val sub = idByCode[demo.code] ?: return@repeat
                    dao.insertTransaction(
                        TransactionEntity(
                            amountMinor = demo.pence + ((day * 37 + k * 13) % 9) * 25L,
                            currency = "GBP",
                            merchantName = demo.name,
                            merchantKey = Merchants.keyOf(demo.name),
                            descriptor = demo.name.uppercase(),
                            place = "London",
                            account = "BANK A/C",
                            occurredAt = at,
                            day = Dates.dayOf(at),
                            subcategoryId = sub,
                            status = TxStatus.CONFIRMED,
                            source = TxSource.TEST,
                            sourceKey = "demo-${n++}",
                            guessSource = "USER",
                            guessConfidence = 1f,
                            note = "",
                            createdAt = at,
                        )
                    )
                }
            }
        }
        }
    }
}
