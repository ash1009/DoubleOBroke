package app.brokeometer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
    val colorIndex: Int,
)

@Entity(
    tableName = "subcategories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId")],
)
data class SubcategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val name: String,
    val position: Int,
    /** Fixed name for built-in sub-categories, for example "food.lunch". Null for ones the user adds. */
    val code: String? = null,
)

object TxStatus {
    /** Captured and guessed, waiting for Accept or Update. Not counted in totals. */
    const val PENDING = "PENDING"
    const val CONFIRMED = "CONFIRMED"
}

object TxSource {
    const val BANK = "BANK"
    const val MANUAL = "MANUAL"
    const val TEST = "TEST"
    /** Brought in from an import file, such as the old expense sheet. */
    const val IMPORT = "IMPORT"
    /** A tube or bus trip found from where the phone went that day. */
    const val TRAVEL = "TRAVEL"
}

@Entity(
    tableName = "transactions",
    indices = [
        Index("day"),
        Index("status"),
        Index("merchantKey"),
        Index(value = ["sourceKey"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Amount in the smallest unit of [currency] (pence for GBP). Always kept in the original currency. */
    val amountMinor: Long,
    val currency: String,
    val merchantName: String,
    val merchantKey: String,
    /** The merchant text exactly as the bank sent it. */
    val descriptor: String,
    val place: String,
    val account: String,
    /** When the payment happened, epoch milliseconds. */
    val occurredAt: Long,
    /** Local calendar day, yyyy-MM-dd. Months are selected by its first seven characters. */
    val day: String,
    val subcategoryId: Long?,
    val status: String,
    val source: String,
    /** Identity of the bank alert this came from, so the same alert is never saved twice. */
    val sourceKey: String?,
    val guessSource: String,
    val guessConfidence: Float,
    val note: String,
    val createdAt: Long,
    /** Where the phone was when a card payment came in, if the owner allows it. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Metres. */
    val locationAccuracy: Float? = null,
    /**
     * What was really paid, when friends have paid some of it back. [amountMinor] is then your share,
     * so every total counts only that. Null when nothing was paid back.
     */
    val paidMinor: Long? = null,
)

/** Examples the app starts with: common shops, made up, until the owner's own choices take over. */
@Entity(tableName = "starter_examples")
data class StarterExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantKey: String,
    val subcategoryId: Long,
    val hour: Int?,
    val amountMinor: Long?,
)

/** Trips: days in these ranges are left out of averages. */
@Entity(tableName = "away_ranges")
data class AwayRangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startDay: String,
    val endDay: String,
    val label: String,
)

/** Exchange rates by day, for showing other currencies in pounds. Filled in a later version. */
@Entity(tableName = "fx_rates", primaryKeys = ["day", "currency"])
data class FxRateEntity(
    val day: String,
    val currency: String,
    val unitsPerGbp: Double,
)

/** What the app did with each bank alert it saw, for troubleshooting. */
@Entity(tableName = "capture_log")
data class CaptureLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val packageName: String,
    val title: String,
    val text: String,
    val outcome: String,
)

object MoveKind {
    /** Between two of your own accounts. Never spending. */
    const val TRANSFER = "TRANSFER"
    /** Someone paying you back for a payment: that payment then counts only your share. */
    const val PAYBACK = "PAYBACK"
    /** Money in that is neither, such as a gift. */
    const val OTHER = "OTHER"
    /** Money in from someone else that you haven't said anything about yet. */
    const val UNSORTED = "UNSORTED"
    /** Money put on the Oyster card (direction OUT): it moves from the bank to Oyster, and isn't spending. */
    const val TOPUP = "TOPUP"
}

/**
 * Money that isn't a payment: money coming in (direction IN), and money you sent to one of your own
 * accounts (direction OUT, always a transfer). A transfer seen at both banks is two rows that point at
 * each other.
 */
@Entity(
    tableName = "money_moves",
    indices = [Index("day"), Index("kind"), Index(value = ["sourceKey"], unique = true)],
)
data class MoneyMoveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "IN" or "OUT". */
    val direction: String,
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    /** Who sent it (IN) or who it went to (OUT), as the bank shows it. */
    val counterparty: String,
    val reference: String,
    /** The alert's text, as sent. */
    val descriptor: String,
    /** "HSBC" or "Starling". */
    val bank: String,
    val account: String,
    val occurredAt: Long,
    val day: String,
    /** The other side of a transfer between your own accounts. */
    val pairId: Long? = null,
    /** The payment this pays back. */
    val paysBackId: Long? = null,
    val sourceKey: String?,
    val note: String = "",
    val createdAt: Long,
) {
    companion object {
        const val IN = "IN"
        const val OUT = "OUT"
    }
}
