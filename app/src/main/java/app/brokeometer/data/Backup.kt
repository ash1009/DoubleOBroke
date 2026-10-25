package app.brokeometer.data

import app.brokeometer.domain.ImportFormatException
import app.brokeometer.insights.Goals
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

/** Everything needed to put the app back as it was: entries, categories, trips and settings. Never any keys. */
data class BackupData(
    val exportedAt: Long,
    val appVersion: String,
    val categories: List<CategoryEntity>,
    val subcategories: List<SubcategoryEntity>,
    val transactions: List<TransactionEntity>,
    val away: List<AwayRangeEntity>,
    val settings: BackupSettings,
    /** The profile picture as base64 JPEG, or null. */
    val avatar: String?,
    /** The password vault, still encrypted with the master password, or null. */
    val vault: String? = null,
    /** Money in and transfers. */
    val moves: List<BackupMove> = emptyList(),
    /** Notes, as a JSON list ([app.brokeometer.notes.NotesBackup]), or null. */
    val notes: String? = null,
)

/**
 * A money in or transfer row in a backup. Row ids differ between phones, so the payment it pays back is
 * kept by that payment's identity, and the other side of a transfer by its alert key.
 */
data class BackupMove(
    val move: MoneyMoveEntity,
    val paysBack: String? = null,
    val pairKey: String? = null,
)

data class BackupSettings(
    val username: String = "",
    val hiddenRegulars: Set<String> = emptySet(),
    /** Category caps here are keyed by the backup's own category ids. */
    val goals: Goals = Goals(),
    /** Fixed costs (like rent), by the backup's own category and sub-category ids. */
    val fixedCategories: Set<Long> = emptySet(),
    val fixedSubcategories: Set<Long> = emptySet(),
)

/** Reads and writes backup files. Plain JSON, so it can be opened and checked by hand. */
object BackupCodec {

    const val FORMAT = "broke-o-meter-backup"
    const val VERSION = 1

    /** A backup is JSON; the old expense sheet is CSV. */
    fun looksLikeBackup(text: String): Boolean = text.trimStart('﻿', ' ', '\n', '\r', '\t').startsWith("{")

    fun write(data: BackupData): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", data.exportedAt)
        root.put("appVersion", data.appVersion)
        root.put("categories", JSONArray().apply {
            data.categories.forEach { c ->
                put(JSONObject().put("id", c.id).put("name", c.name).put("position", c.position).put("colorIndex", c.colorIndex))
            }
        })
        root.put("subcategories", JSONArray().apply {
            data.subcategories.forEach { s ->
                put(
                    JSONObject().put("id", s.id).put("categoryId", s.categoryId).put("name", s.name)
                        .put("position", s.position).putOpt("code", s.code)
                )
            }
        })
        root.put("transactions", JSONArray().apply { data.transactions.forEach { put(transactionJson(it)) } })
        root.put("moneyMoves", JSONArray().apply { data.moves.forEach { put(moveJson(it)) } })
        root.put("away", JSONArray().apply {
            data.away.forEach { a -> put(JSONObject().put("startDay", a.startDay).put("endDay", a.endDay).put("label", a.label)) }
        })
        val goals = data.settings.goals
        root.put("settings", JSONObject().apply {
            put("username", data.settings.username)
            put("hiddenRegulars", JSONArray(data.settings.hiddenRegulars.sorted()))
            put("goals", JSONObject().apply {
                putOpt("savingsMinor", goals.savingsMinor)
                putOpt("savingsAt", goals.savingsAt)
                putOpt("lastUntil", goals.lastUntil?.toString())
                putOpt("monthlyLimitMinor", goals.monthlyLimitMinor)
                putOpt("noSpendTarget", goals.noSpendTarget)
                put("categoryCaps", JSONObject().apply { goals.categoryCaps.forEach { (id, cap) -> put(id.toString(), cap) } })
            })
            put("fixedCategories", JSONArray(data.settings.fixedCategories.sorted()))
            put("fixedSubcategories", JSONArray(data.settings.fixedSubcategories.sorted()))
        })
        root.putOpt("avatar", data.avatar)
        root.putOpt("vault", data.vault)
        data.notes?.let { root.put("notes", JSONArray(it)) }
        return root.toString()
    }

    private fun transactionJson(t: TransactionEntity): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("amountMinor", t.amountMinor)
        put("currency", t.currency)
        put("merchantName", t.merchantName)
        put("merchantKey", t.merchantKey)
        put("descriptor", t.descriptor)
        put("place", t.place)
        put("account", t.account)
        put("occurredAt", t.occurredAt)
        put("day", t.day)
        putOpt("subcategoryId", t.subcategoryId)
        put("status", t.status)
        put("source", t.source)
        putOpt("sourceKey", t.sourceKey)
        put("guessSource", t.guessSource)
        put("guessConfidence", t.guessConfidence.toDouble().takeIf { it.isFinite() } ?: 0.0)
        put("note", t.note)
        put("createdAt", t.createdAt)
        putOpt("latitude", t.latitude)
        putOpt("longitude", t.longitude)
        putOpt("locationAccuracy", t.locationAccuracy?.toDouble())
        putOpt("paidMinor", t.paidMinor)
    }

    private fun moveJson(b: BackupMove): JSONObject = JSONObject().apply {
        val m = b.move
        put("direction", m.direction)
        put("kind", m.kind)
        put("amountMinor", m.amountMinor)
        put("currency", m.currency)
        put("counterparty", m.counterparty)
        put("reference", m.reference)
        put("descriptor", m.descriptor)
        put("bank", m.bank)
        put("account", m.account)
        put("occurredAt", m.occurredAt)
        put("day", m.day)
        putOpt("sourceKey", m.sourceKey)
        put("note", m.note)
        put("createdAt", m.createdAt)
        putOpt("paysBack", b.paysBack)
        putOpt("pairKey", b.pairKey)
    }

    fun read(text: String): BackupData {
        val root = try {
            JSONObject(text.trimStart('﻿'))
        } catch (e: JSONException) {
            throw ImportFormatException("This file is not a Double O Broke backup.")
        }
        if (root.optString("format") != FORMAT) throw ImportFormatException("This file is not a Double O Broke backup.")
        if (root.optInt("version", 0) > VERSION) {
            throw ImportFormatException("This backup was made by a newer version of the app. Update the app, then try again.")
        }
        try {
            val categories = root.objects("categories").map { c ->
                CategoryEntity(
                    id = c.getLong("id"),
                    name = c.getString("name"),
                    position = c.optInt("position", 0),
                    colorIndex = c.optInt("colorIndex", 0),
                )
            }
            val subcategories = root.objects("subcategories").map { s ->
                SubcategoryEntity(
                    id = s.getLong("id"),
                    categoryId = s.getLong("categoryId"),
                    name = s.getString("name"),
                    position = s.optInt("position", 0),
                    code = s.stringOrNull("code"),
                )
            }
            val transactions = root.objects("transactions").map { t ->
                TransactionEntity(
                    id = t.optLong("id", 0),
                    amountMinor = t.getLong("amountMinor"),
                    currency = t.getString("currency"),
                    merchantName = t.optString("merchantName"),
                    merchantKey = t.optString("merchantKey"),
                    descriptor = t.optString("descriptor"),
                    place = t.optString("place"),
                    account = t.optString("account"),
                    occurredAt = t.getLong("occurredAt"),
                    day = t.getString("day"),
                    subcategoryId = t.longOrNull("subcategoryId"),
                    status = t.getString("status"),
                    source = t.getString("source"),
                    sourceKey = t.stringOrNull("sourceKey"),
                    guessSource = t.optString("guessSource"),
                    guessConfidence = t.optDouble("guessConfidence", 1.0).toFloat(),
                    note = t.optString("note"),
                    createdAt = t.optLong("createdAt", 0),
                    latitude = t.doubleOrNull("latitude"),
                    longitude = t.doubleOrNull("longitude"),
                    locationAccuracy = t.doubleOrNull("locationAccuracy")?.toFloat(),
                    paidMinor = t.longOrNull("paidMinor"),
                )
            }
            val moves = root.objects("moneyMoves").map { m ->
                BackupMove(
                    move = MoneyMoveEntity(
                        direction = m.getString("direction"),
                        kind = m.getString("kind"),
                        amountMinor = m.getLong("amountMinor"),
                        currency = m.getString("currency"),
                        counterparty = m.optString("counterparty"),
                        reference = m.optString("reference"),
                        descriptor = m.optString("descriptor"),
                        bank = m.optString("bank"),
                        account = m.optString("account"),
                        occurredAt = m.getLong("occurredAt"),
                        day = m.getString("day"),
                        sourceKey = m.stringOrNull("sourceKey"),
                        note = m.optString("note"),
                        createdAt = m.optLong("createdAt", 0),
                    ),
                    paysBack = m.stringOrNull("paysBack"),
                    pairKey = m.stringOrNull("pairKey"),
                )
            }
            val away = root.objects("away").map { a ->
                AwayRangeEntity(startDay = a.getString("startDay"), endDay = a.getString("endDay"), label = a.optString("label"))
            }
            val s = root.optJSONObject("settings") ?: JSONObject()
            val g = s.optJSONObject("goals") ?: JSONObject()
            val caps = g.optJSONObject("categoryCaps") ?: JSONObject()
            val settings = BackupSettings(
                username = s.optString("username"),
                hiddenRegulars = (s.optJSONArray("hiddenRegulars") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                },
                goals = Goals(
                    savingsMinor = g.longOrNull("savingsMinor"),
                    savingsAt = g.longOrNull("savingsAt"),
                    lastUntil = g.stringOrNull("lastUntil")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    monthlyLimitMinor = g.longOrNull("monthlyLimitMinor"),
                    noSpendTarget = g.longOrNull("noSpendTarget")?.toInt(),
                    categoryCaps = caps.keys().asSequence().mapNotNull { k ->
                        val id = k.toLongOrNull() ?: return@mapNotNull null
                        id to caps.getLong(k)
                    }.toMap(),
                ),
                fixedCategories = s.longSet("fixedCategories"),
                fixedSubcategories = s.longSet("fixedSubcategories"),
            )
            return BackupData(
                exportedAt = root.optLong("exportedAt", 0),
                appVersion = root.optString("appVersion"),
                categories = categories,
                subcategories = subcategories,
                transactions = transactions,
                away = away,
                settings = settings,
                avatar = root.stringOrNull("avatar"),
                vault = root.stringOrNull("vault"),
                moves = moves,
                notes = root.optJSONArray("notes")?.toString(),
            )
        } catch (e: JSONException) {
            throw ImportFormatException("This backup is damaged and could not be read.")
        }
    }

    private fun JSONObject.objects(name: String): List<JSONObject> {
        val arr = optJSONArray(name) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun JSONObject.longOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)
    private fun JSONObject.doubleOrNull(name: String): Double? = if (isNull(name)) null else getDouble(name)
    private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)
    private fun JSONObject.longSet(name: String): Set<Long> {
        val arr = optJSONArray(name) ?: return emptySet()
        return (0 until arr.length()).mapNotNull { arr.optLong(it, -1L).takeIf { id -> id >= 0 } }.toSet()
    }
}

/** What a restore would add. Nothing on the phone is ever changed or deleted by a restore. */
data class RestorePlan(
    val backup: BackupData,
    /** Entries to add, still carrying the backup's own sub-category ids. */
    val rows: List<TransactionEntity>,
    val away: List<AwayRangeEntity>,
    /** Backup sub-category id to the matching sub-category already on the phone. */
    val subMap: Map<Long, Long>,
    /** Sub-categories from the backup that the phone does not have yet. */
    val missingSubs: List<SubcategoryEntity>,
    /** Names of whole categories that will be added. */
    val newCategories: List<String>,
    val alreadyThere: Int,
    /** Goals from the backup, used only when the phone has none. */
    val restoresGoals: Boolean,
    /** The password vault, used only when the phone has none. */
    val restoresVault: Boolean = false,
    /** Money in and transfers the phone doesn't have yet. */
    val moves: List<BackupMove> = emptyList(),
) {
    val firstDay: String? = rows.minOfOrNull { it.day }
    val lastDay: String? = rows.maxOfOrNull { it.day }
    val isEmpty: Boolean get() = rows.isEmpty() && away.isEmpty() && missingSubs.isEmpty() && !restoresGoals && !restoresVault && moves.isEmpty()
}

object RestorePlanner {

    /** The same entry always gives the same identity, on any phone. */
    fun identity(t: TransactionEntity): String =
        t.sourceKey ?: "row|${t.createdAt}|${t.occurredAt}|${t.amountMinor}|${t.currency}"

    fun plan(
        backup: BackupData,
        catalog: Catalog,
        existingIdentities: Set<String>,
        existingAway: List<AwayRangeEntity>,
        phoneGoals: Goals,
        existingMoveKeys: Set<String> = emptySet(),
    ): RestorePlan {
        val backupCategories = backup.categories.associateBy { it.id }
        val phoneByCode = catalog.subcategories.mapNotNull { s -> s.code?.let { it to s.id } }.toMap()
        val phoneCategoryByName = catalog.categories.associateBy { norm(it.name) }

        val subMap = HashMap<Long, Long>()
        val missing = ArrayList<SubcategoryEntity>()
        for (sub in backup.subcategories) {
            val byCode = sub.code?.let { phoneByCode[it] }
            if (byCode != null) {
                subMap[sub.id] = byCode
                continue
            }
            val categoryName = backupCategories[sub.categoryId]?.name ?: continue
            val phoneCategory = phoneCategoryByName[norm(categoryName)]
            val byName = phoneCategory?.let { c ->
                catalog.subcategories.firstOrNull { it.categoryId == c.id && norm(it.name) == norm(sub.name) }
            }
            if (byName != null) subMap[sub.id] = byName.id else missing += sub
        }
        val newCategories = missing
            .mapNotNull { backupCategories[it.categoryId]?.name }
            .filter { norm(it) !in phoneCategoryByName }
            .distinctBy { norm(it) }

        val seen = HashSet(existingIdentities)
        var alreadyThere = 0
        val rows = ArrayList<TransactionEntity>()
        for (t in backup.transactions) {
            if (t.source == TxSource.TEST) continue
            if (!seen.add(identity(t))) {
                alreadyThere++
                continue
            }
            rows += t
        }

        val haveAway = existingAway.map { it.startDay to it.endDay }.toSet()
        val away = backup.away.filter { (it.startDay to it.endDay) !in haveAway }.distinctBy { it.startDay to it.endDay }

        return RestorePlan(
            backup = backup,
            rows = rows,
            away = away,
            subMap = subMap,
            missingSubs = missing,
            newCategories = newCategories,
            alreadyThere = alreadyThere,
            restoresGoals = phoneGoals.isEmpty && !backup.settings.goals.isEmpty,
            moves = backup.moves.filter { it.move.sourceKey == null || it.move.sourceKey !in existingMoveKeys }
                .distinctBy { it.move.sourceKey ?: "${it.move.occurredAt}|${it.move.amountMinor}" },
        )
    }

    /** Category caps are kept by category id, which can differ between phones. Match them by name. */
    fun mapCaps(caps: Map<Long, Long>, backupCategories: List<CategoryEntity>, phoneCategories: List<CategoryEntity>): Map<Long, Long> {
        val phoneByName = phoneCategories.associateBy { norm(it.name) }
        return caps.mapNotNull { (oldId, cap) ->
            val name = backupCategories.firstOrNull { it.id == oldId }?.name ?: return@mapNotNull null
            phoneByName[norm(name)]?.let { it.id to cap }
        }.toMap()
    }

    /** Fixed-cost categories, matched by name like the caps. */
    fun mapCategoryIds(ids: Set<Long>, backupCategories: List<CategoryEntity>, phoneCategories: List<CategoryEntity>): Set<Long> {
        val phoneByName = phoneCategories.associateBy { norm(it.name) }
        return ids.mapNotNull { old ->
            val name = backupCategories.firstOrNull { it.id == old }?.name ?: return@mapNotNull null
            phoneByName[norm(name)]?.id
        }.toSet()
    }

    /** Hidden regular payments start with the sub-category id: "12|SHOP|10". */
    fun mapHidden(keys: Set<String>, subMap: Map<Long, Long>): Set<String> = keys.mapNotNull { key ->
        val first = key.substringBefore('|')
        val rest = key.substringAfter('|', "")
        val newId = first.toLongOrNull()?.let { subMap[it] } ?: return@mapNotNull null
        "$newId|$rest"
    }.toSet()

    private fun norm(name: String) = name.trim().lowercase()
}
