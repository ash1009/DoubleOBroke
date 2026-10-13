package app.brokeometer.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class ImportedSpend(
    val day: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val category: String,
    val subcategory: String,
    val note: String,
)

data class ImportedAway(val start: LocalDate, val end: LocalDate, val label: String)

data class ImportBundle(
    val spends: List<ImportedSpend>,
    val away: List<ImportedAway>,
    /** Lines that could not be read, as "line 12: reason". */
    val problems: List<String>,
)

class ImportFormatException(message: String) : Exception(message)

/**
 * Reads a Broke O Meter import file. It is a CSV with this header:
 *
 *     kind,date,end_date,amount,currency,category,subcategory,note
 *
 * "spend" lines are payments; "away" lines are trips (date to end_date) left out of averages.
 */
object ImportCsv {

    val HEADER = listOf("kind", "date", "end_date", "amount", "currency", "category", "subcategory", "note")

    fun parse(text: String): ImportBundle {
        val rows = rows(text.removePrefix("﻿"))
        val header = rows.firstOrNull()?.map { it.trim().lowercase() }
        if (header == null || header.take(HEADER.size) != HEADER) {
            throw ImportFormatException("This is not a Double O Broke import file.")
        }
        val spends = ArrayList<ImportedSpend>()
        val away = ArrayList<ImportedAway>()
        val problems = ArrayList<String>()
        rows.drop(1).forEachIndexed { index, cells ->
            val line = index + 2
            if (cells.all { it.isBlank() }) return@forEachIndexed
            fun cell(i: Int) = cells.getOrNull(i)?.trim().orEmpty()
            try {
                when (cell(0).lowercase()) {
                    "spend" -> {
                        val amount = amountMinor(cell(3)) ?: throw IllegalArgumentException("amount")
                        val currency = cell(4).uppercase().ifEmpty { "GBP" }
                        if (currency.length != 3) throw IllegalArgumentException("currency")
                        if (cell(5).isEmpty() || cell(6).isEmpty()) throw IllegalArgumentException("category")
                        spends += ImportedSpend(LocalDate.parse(cell(1)), amount, currency, cell(5), cell(6), cell(7))
                    }
                    "away" -> away += ImportedAway(LocalDate.parse(cell(1)), LocalDate.parse(cell(2)), cell(7))
                    else -> throw IllegalArgumentException("kind")
                }
            } catch (e: DateTimeParseException) {
                problems += "line $line: date"
            } catch (e: IllegalArgumentException) {
                problems += "line $line: ${e.message}"
            }
        }
        return ImportBundle(spends, away, problems)
    }

    /** "12.50" becomes 1250. Null when it is not a positive amount. */
    fun amountMinor(text: String): Long? = try {
        val value = BigDecimal(text.replace(",", "")).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        if (value > 0) value else null
    } catch (e: NumberFormatException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

    /** Splits CSV text into rows of cells. Handles quoted cells with commas, quotes and line breaks. */
    fun rows(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(c)
                }
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.clear() }
                '\r' -> Unit
                '\n' -> { row.add(cell.toString()); cell.clear(); out.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString()); out.add(row)
        }
        return out
    }
}
