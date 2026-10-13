package app.brokeometer.data

import app.brokeometer.insights.FxTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

fun fxTableOf(rows: List<FxRateEntity>): FxTable =
    FxTable(
        rows.groupBy { it.currency }.mapValues { (_, list) ->
            list.mapNotNull { row -> runCatching { LocalDate.parse(row.day) to row.unitsPerGbp }.getOrNull() }.toMap()
        }
    )

/**
 * Fetches the day's exchange rate for payments in other currencies, so insights can show them in
 * pounds. Uses the free European Central Bank reference rates published through Frankfurter.
 * Only dates and currency codes are sent, never amounts or shops.
 */
class FxUpdater(private val dao: BrokeDao) {

    private val lock = Mutex()

    /** Returns true when every payment in another currency has a rate. */
    suspend fun refresh(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val foreign = dao.foreignDays().mapNotNull { row ->
                runCatching { row.currency to LocalDate.parse(row.day) }.getOrNull()
            }
            if (foreign.isEmpty()) return@withContext true
            var table = fxTableOf(dao.fxRates())
            val missing = foreign.filter { (currency, day) -> !table.has(currency, day) }
            if (missing.isEmpty()) return@withContext true

            val today = LocalDate.now()
            for ((currency, days) in missing.groupBy({ it.first }, { it.second })) {
                var from = days.min().minusDays(7)
                val end = minOf(days.max().plusDays(3), today)
                while (from <= end) {
                    val to = minOf(from.plusDays(CHUNK_DAYS - 1), end)
                    try {
                        dao.insertFxRates(fetch(currency, from, to))
                    } catch (e: Exception) {
                        // No internet or an unknown currency. Tried again next time the app opens.
                    }
                    from = to.plusDays(1)
                }
            }
            table = fxTableOf(dao.fxRates())
            foreign.all { (currency, day) -> table.has(currency, day) }
        }
    }

    private fun fetch(currency: String, from: LocalDate, to: LocalDate): List<FxRateEntity> {
        val symbols = if (currency == "EUR") "GBP" else "GBP,$currency"
        val connection = URL("$BASE_URL/$from..$to?symbols=$symbols").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val rates = JSONObject(body).getJSONObject("rates")
            val out = ArrayList<FxRateEntity>()
            for (day in rates.keys()) {
                val perEuro = rates.getJSONObject(day)
                val gbp = perEuro.optDouble("GBP", Double.NaN)
                if (gbp.isNaN() || gbp <= 0) continue
                val units = if (currency == "EUR") 1.0 / gbp else perEuro.optDouble(currency, Double.NaN) / gbp
                if (!units.isNaN() && units > 0) out += FxRateEntity(day = day, currency = currency, unitsPerGbp = units)
            }
            return out
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://api.frankfurter.dev/v1"
        private const val CHUNK_DAYS = 90L
    }
}
