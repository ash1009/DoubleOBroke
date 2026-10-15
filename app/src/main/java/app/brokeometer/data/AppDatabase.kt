package app.brokeometer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        SubcategoryEntity::class,
        TransactionEntity::class,
        StarterExampleEntity::class,
        AwayRangeEntity::class,
        FxRateEntity::class,
        CaptureLogEntity::class,
        MoneyMoveEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BrokeDao

    companion object {
        /** Version 2 keeps where the phone was for each payment. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE transactions ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE transactions ADD COLUMN locationAccuracy REAL")
            }
        }

        /** Version 3 keeps money in and transfers, and what was really paid when friends paid some back. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN paidMinor INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `money_moves` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `direction` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `counterparty` TEXT NOT NULL, " +
                        "`reference` TEXT NOT NULL, `descriptor` TEXT NOT NULL, `bank` TEXT NOT NULL, `account` TEXT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, `day` TEXT NOT NULL, `pairId` INTEGER, `paysBackId` INTEGER, " +
                        "`sourceKey` TEXT, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_moves_day` ON `money_moves` (`day`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_moves_kind` ON `money_moves` (`kind`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_money_moves_sourceKey` ON `money_moves` (`sourceKey`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "brokeometer.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
