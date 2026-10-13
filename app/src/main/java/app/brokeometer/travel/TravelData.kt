package app.brokeometer.travel

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One position of the phone. Kept on the phone only, for 30 days. */
@Entity(tableName = "track_points", indices = [Index("at")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val lat: Double,
    val lon: Double,
    /** Metres. */
    val accuracy: Float,
    /** Metres a second, when the phone knew it. */
    val speed: Float?,
)

/**
 * The phone's step counter at a moment: [total] is the steps it has counted since it last started,
 * so a day's steps are the rise over the day (starting again from 0 after a restart).
 */
@Entity(tableName = "step_samples", indices = [Index("at")])
data class StepSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val total: Long,
)

/** A TfL station or stop already looked up, so the same one isn't asked for twice. */
@Entity(tableName = "tfl_stops")
data class TflStopEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** "4", "1+2" or "" for bus stops. */
    val zone: String,
    /** "rail" for tube, rail, Overground and Elizabeth line stations; "bus" for bus stops. */
    val kind: String,
)

/** A fare already looked up: pence for peak and off-peak, between two stations. */
@Entity(tableName = "tfl_fares", primaryKeys = ["fromId", "toId"])
data class TflFareEntity(
    val fromId: String,
    val toId: String,
    val peak: Long,
    val offPeak: Long,
    /** TfL's own words for when peak fares apply, such as "Monday to Friday from 0630 to 0930". */
    val peakWhen: String,
    val fetchedAt: Long,
)

@Dao
interface TravelDao {
    @Insert
    suspend fun insertPoint(point: TrackPointEntity): Long

    @Insert
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_points WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun points(from: Long, to: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE at >= :from AND at < :to ORDER BY at")
    fun observePoints(from: Long, to: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT COUNT(*) FROM track_points WHERE at >= :from AND at < :to")
    fun observeCount(from: Long, to: Long): Flow<Int>

    @Query("SELECT MAX(at) FROM track_points")
    fun observeLast(): Flow<Long?>

    @Query("DELETE FROM track_points WHERE at < :before")
    suspend fun deletePointsBefore(before: Long)

    @Query("DELETE FROM track_points WHERE at >= :from AND at < :to")
    suspend fun deletePoints(from: Long, to: Long)

    @Insert
    suspend fun insertStepSample(sample: StepSampleEntity)

    @Insert
    suspend fun insertStepSamples(samples: List<StepSampleEntity>)

    @Query("SELECT * FROM step_samples WHERE at >= :from AND at < :to ORDER BY at")
    fun observeStepSamples(from: Long, to: Long): Flow<List<StepSampleEntity>>

    @Query("SELECT * FROM step_samples WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun stepSamplesIn(from: Long, to: Long): List<StepSampleEntity>

    /** The last reading before [before]: where a day's count starts from. */
    @Query("SELECT * FROM step_samples WHERE at < :before ORDER BY at DESC LIMIT 1")
    suspend fun stepSampleBefore(before: Long): StepSampleEntity?

    @Query("DELETE FROM step_samples WHERE at < :before")
    suspend fun deleteStepSamplesBefore(before: Long)

    @Query("DELETE FROM step_samples WHERE at >= :from AND at < :to")
    suspend fun deleteStepSamples(from: Long, to: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStops(stops: List<TflStopEntity>)

    @Query("SELECT * FROM tfl_stops WHERE kind = :kind AND lat BETWEEN :south AND :north AND lon BETWEEN :west AND :east")
    suspend fun stopsIn(kind: String, south: Double, north: Double, west: Double, east: Double): List<TflStopEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFare(fare: TflFareEntity)

    @Query("SELECT * FROM tfl_fares WHERE fromId = :fromId AND toId = :toId")
    suspend fun fare(fromId: String, toId: String): TflFareEntity?
}

@Database(
    entities = [TrackPointEntity::class, TflStopEntity::class, TflFareEntity::class, StepSampleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun dao(): TravelDao

    companion object {
        /** Version 2 adds the step counter's readings; the places already noted are kept. */
        private val ADD_STEPS = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `step_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `total` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_step_samples_at` ON `step_samples` (`at`)")
            }
        }

        fun create(context: Context): TravelDatabase =
            Room.databaseBuilder(context.applicationContext, TravelDatabase::class.java, "travel.db")
                .addMigrations(ADD_STEPS)
                .build()
    }
}

