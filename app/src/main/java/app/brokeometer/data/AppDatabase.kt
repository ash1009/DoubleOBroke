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
    ],
    version = 2,
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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "brokeometer.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
