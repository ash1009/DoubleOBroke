package app.brokeometer.travel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.brokeometer.BrokeApp
import app.brokeometer.R
import app.brokeometer.data.Prefs
import app.brokeometer.data.Repository
import app.brokeometer.data.TransactionEntity
import app.brokeometer.data.TxSource
import app.brokeometer.data.TxStatus
import app.brokeometer.ui.MainActivity
import app.brokeometer.util.Dates
import app.brokeometer.util.Money
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The nightly trip check: reads the day's positions, finds tube and bus trips, prices them with TfL's
 * fares and adds them to Payments as waiting, dated when each trip happened. Also keeps card charges
 * and Oyster top-ups from counting twice.
 */
class TravelAgent(
    private val context: Context,
    private val dao: TravelDao,
    private val repository: Repository,
    private val prefs: Prefs,
    private val tfl: StopLookup = TflClient(dao),
) {

    /** What one check found. [added] went to Payments; [already] were there from an earlier check. */
    data class Checked(val day: LocalDate, val trips: List<PricedTrip>, val added: Int, val already: Int, val points: Int)

    suspend fun check(day: LocalDate, notify: Boolean): Checked {
        val zone = Dates.zone()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val points = dao.points(from, to).map { Fix(it.at, it.lat, it.lon, it.accuracy, it.speed) }
        val legs = TripFinder.legs(points)
        val trips = TripPlanner.plan(legs, tfl, points)
        val withFares = trips.map { t -> t to if (t.kind == TripKind.RAIL) runCatching { tfl.fare(t.from, t.to) }.getOrNull() else null }
        val priced = Fares.price(withFares, railcard = prefs.railcard.value.isNotBlank())

        // Leave out trips already added by an earlier check (same kind, overlapping times).
        val dayKey = Dates.dayOf(from)
        val found = repository.dao.onDays(listOf(dayKey)).filter { it.source == TxSource.TRAVEL }
        // Guesses from an earlier check that this one doesn't find exactly (the finder has got better,
        // say) go, so the new ones can take their place, as long as you haven't accepted them yet.
        // Ones you accepted are yours to keep or delete.
        val keys = priced.map { sourceKey(it.trip) }.toSet()
        val stale = found.filter { tx -> tx.status == TxStatus.PENDING && tx.sourceKey !in keys }
        stale.forEach { repository.dao.deleteTransaction(it.id) }
        val existing = found - stale.toSet()
        // A card charge for this day that came in before the check: the trips it pays for aren't Oyster.
        val charges = repository.dao.onDays(listOf(dayKey, day.plusDays(1).toString(), day.plusDays(2).toString()))
            .filter { it.source == TxSource.BANK && isTravelCharge(it.merchantKey, it.merchantName) }
        val onCard = HashSet<Trip>()
        for (charge in charges) {
            val open = priced.filter { it.trip !in onCard && it.pence > 0 }
            subsetSumming(open.map { it.pence }, charge.amountMinor)?.forEach { onCard += open[it].trip }
        }
        var added = 0
        var already = 0
        val catalog = repository.catalog()
        val metro = catalog.subcategories.firstOrNull { it.code == "travel.metro" }?.id
        val bus = catalog.subcategories.firstOrNull { it.code == "travel.bus" }?.id
        fun noteFor(p: PricedTrip) = buildString {
            val t = p.trip
            append("${Dates.clock(t.startAt)} to ${Dates.clock(t.endAt)}. ${p.note}.")
            if (t.kind == TripKind.RAIL && t.via.isNotEmpty()) append(" Likely ${t.via.joinToString(", ")}.")
        }
        for (p in priced) {
            val t = p.trip
            val same = existing.firstOrNull { overlaps(it, t) }
            if (t in onCard || same != null) {
                // Found again. If it's still waiting for you and its fare has changed (this check found an
                // earlier bus, so this one is free under the Hopper, say), the new fare replaces the old.
                if (same != null && same.status == TxStatus.PENDING && same.amountMinor != p.pence) {
                    repository.dao.updateTransaction(same.copy(amountMinor = p.pence, note = noteFor(p)))
                }
                already++
                continue
            }
            val name = if (t.kind == TripKind.RAIL) "Tube" else "Bus" + (t.via.firstOrNull()?.let { " $it" } ?: "")
            val note = noteFor(p)
            val row = TransactionEntity(
                amountMinor = p.pence,
                currency = "GBP",
                merchantName = name,
                merchantKey = if (t.kind == TripKind.RAIL) "TFL TUBE TRIP" else "TFL BUS TRIP",
                descriptor = "",
                place = "${t.from.name} to ${t.to.name}",
                account = "Oyster",
                occurredAt = t.startAt,
                day = Dates.dayOf(t.startAt),
                subcategoryId = if (t.kind == TripKind.RAIL) metro else bus,
                status = TxStatus.PENDING,
                source = TxSource.TRAVEL,
                sourceKey = sourceKey(t),
                guessSource = "TRAVEL",
                guessConfidence = 0.9f,
                note = note,
                createdAt = System.currentTimeMillis(),
            )
            if (repository.dao.insertTransaction(row) > 0) added++ else already++
        }
        if (notify && added > 0) notifyFound(day, priced.filterNot { p -> existing.any { overlaps(it, p.trip) } })
        return Checked(day, priced, added, already, points.size)
    }

    private fun sourceKey(t: Trip) = "travel|${t.kind}|${t.from.id}|${t.to.id}|${t.startAt / 60_000}"

    /** The same trip already on Payments: same kind, starting around when this one starts or during it. */
    private fun overlaps(tx: TransactionEntity, t: Trip): Boolean {
        val railTx = tx.merchantKey == "TFL TUBE TRIP"
        if (railTx != (t.kind == TripKind.RAIL)) return false
        return tx.occurredAt in (t.startAt - 15 * 60_000L)..(t.endAt + 5 * 60_000L)
    }

    private fun notifyFound(day: LocalDate, trips: List<PricedTrip>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        TravelTracker.ensureChannel(context)
        val total = trips.sumOf { it.pence }
        val lines = trips.joinToString("\n") { p ->
            "${Dates.clock(p.trip.startAt)}  ${p.trip.from.name} to ${p.trip.to.name}, ${Money.format(p.pence, "GBP")}"
        }
        val title = "${trips.size} ${if (trips.size == 1) "trip" else "trips"} ${if (day == LocalDate.now()) "today" else "on " + Dates.pretty(day)}, ${Money.format(total, "GBP")}"
        val open = PendingIntent.getActivity(
            context, 42,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_PENDING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, TravelTracker.CHANNEL_TRIPS)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle(title)
            .setContentText("Check them: tap one to change it, or if you paid for a friend too.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$lines\n\nCheck them: tap one to change it, or if you paid for a friend too."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("travel", day.toEpochDay().toInt(), n)
        } catch (e: SecurityException) {
            // Notifications are off; the trips are still waiting on the Payments page.
        }
    }

    // Card charges and top-ups

    /**
     * An Oyster top-up from the bank: trips count instead, so it isn't spending. Other TfL payments,
     * such as Santander Cycles, are left as they are.
     */
    fun isOysterTopUp(merchantKey: String, merchantName: String): Boolean {
        val text = "$merchantKey $merchantName".uppercase()
        val topUp = text.contains("OYSTER") ||
            ((text.contains("TFL") || text.contains("TRANSPORT FOR LONDON")) && Regex("""TOP[ -]?UP""").containsMatchIn(text))
        return prefs.trackTrips.value && topUp && !isTravelCharge(merchantKey, merchantName)
    }

    /** TfL's daily charge for contactless travel, which arrives a day or so after the trips. */
    fun isTravelCharge(merchantKey: String, merchantName: String): Boolean {
        val text = "$merchantKey $merchantName".uppercase()
        return text.contains("TFL") && text.contains("TRAVEL")
    }

    /**
     * A contactless TfL charge came in. The trips it pays for were taken with the card, not Oyster, so
     * they are taken off (the charge stands for them). If the charge doesn't match any trips, a note
     * says so: maybe you paid for a friend too. Returns the note to put on the charge, or null.
     */
    suspend fun onTravelCharge(charge: TransactionEntity): String? {
        if (!prefs.trackTrips.value) return null
        val chargeDay = LocalDate.parse(charge.day)
        val days = (0..3).map { chargeDay.minusDays(it.toLong()).toString() }
        val trips = repository.dao.onDays(days).filter { it.source == TxSource.TRAVEL && it.amountMinor > 0 }
        if (trips.isEmpty()) return null
        // The day (or trips within a day) whose fares add up to the charge.
        for (day in days) {
            val those = trips.filter { it.day == day }
            if (those.isEmpty()) continue
            val match = subsetSumming(those.map { it.amountMinor }, charge.amountMinor)?.map { those[it] }
            if (match != null) {
                match.forEach { repository.dao.deleteTransaction(it.id) }
                val names = match.joinToString(", ") { it.place }
                return "Paid by card: covers ${match.size} ${if (match.size == 1) "trip" else "trips"} on ${Dates.pretty(day)} ($names), so they aren't counted twice."
            }
        }
        val yesterday = trips.filter { it.day == chargeDay.minusDays(1).toString() }.ifEmpty { trips.filter { it.day == charge.day } }
        val sum = yesterday.sumOf { it.amountMinor }
        return if (sum > 0 && charge.amountMinor > sum) {
            "TfL charged ${Money.format(charge.amountMinor, "GBP")}, but the trips found came to ${Money.format(sum, "GBP")}. " +
                "Paid for someone else too? If these trips were on the card, delete them from Payments."
        } else {
            "This TfL charge doesn't match the trips found. If some trips were on the card, delete them from Payments."
        }
    }

    /** Which of these fares add up to [target] (to within 5p), trying all of them first. Null if none do. */
    private fun subsetSumming(fares: List<Long>, target: Long): List<Int>? {
        if (fares.isEmpty()) return null
        if (abs(fares.sum() - target) <= 5) return fares.indices.toList()
        if (fares.size > 12) return null
        val n = fares.size
        for (mask in (1 until (1 shl n)).sortedByDescending { Integer.bitCount(it) }) {
            val pick = (0 until n).filter { mask and (1 shl it) != 0 }
            if (abs(pick.sumOf { fares[it] } - target) <= 5) return pick
        }
        return null
    }

    /** Old positions and step readings go after 30 days. */
    suspend fun tidy() {
        val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        dao.deletePointsBefore(before)
        dao.deleteStepSamplesBefore(before)
    }
}

/** Runs the trip check a little before midnight, for the day that's ending, then sets itself for the next night. */
class TravelCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as BrokeApp).container
        val prefs = container.prefs
        if (!prefs.trackTrips.value) return Result.success()
        // Run late (after midnight)? Then it's the day before that's being checked.
        val day = ZonedDateTime.now().minusMinutes(30).toLocalDate()
        val result = try {
            // A day missed (phone off at night) is checked too, quietly.
            val last = prefs.lastTravelDay.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
            if (last != null && last.isBefore(day.minusDays(1)) && !last.isBefore(day.minusDays(3))) {
                container.travel.check(day.minusDays(1), notify = true)
            }
            container.travel.check(day, notify = true)
            container.travel.tidy()
            prefs.lastTravelDay = day.toString()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
        if (result !is Result.Retry) schedule(applicationContext, fromItself = true)
        return result
    }

    companion object {
        private const val NAME = "travel-check"

        /** Next run at 23:50, needing the internet for TfL's fares. */
        fun schedule(context: Context, fromItself: Boolean = false) {
            val prefs = (context.applicationContext as BrokeApp).container.prefs
            val work = WorkManager.getInstance(context)
            if (!prefs.trackTrips.value) {
                work.cancelUniqueWork(NAME)
                return
            }
            val now = ZonedDateTime.now()
            var next = now.with(LocalTime.of(23, 50))
            if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<TravelCheckWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // From inside the check itself, the next one waits for this one to finish instead of stopping it.
            work.enqueueUniqueWork(NAME, if (fromItself) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }
    }
}
