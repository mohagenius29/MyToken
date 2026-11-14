package com.mytoken.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "meters")
data class Meter(
    @PrimaryKey val number: String,
    val label: String = "",
    val tracked: Boolean = false,
    val balanceUnits: Double? = null,
    val balanceAt: Long? = null
)

fun Meter.display(): String = label.ifBlank { if (number == "UNKNOWN") "Unknown meter" else number }

@Entity(
    tableName = "purchases",
    indices = [Index(value = ["token"], unique = true), Index(value = ["meterNumber"])]
)
data class Purchase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meterNumber: String,
    val token: String,
    val units: Double,
    val amount: Double,
    val dateMillis: Long,
    val used: Boolean = false,
    val manual: Boolean = false,
    /** "TOKEN" for a token message, "PAYMENT" for a payment confirmation with no token message. */
    val kind: String = "TOKEN"
)

/** A meter balance the user typed in (units left on the meter at a point in time). */
@Entity(tableName = "readings", indices = [Index(value = ["meterNumber"])])
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meterNumber: String,
    val units: Double,
    val atMillis: Long
)

@Dao
interface AppDao {
    @Query("SELECT * FROM meters ORDER BY number")
    fun meters(): Flow<List<Meter>>

    @Query("SELECT * FROM meters")
    suspend fun metersNow(): List<Meter>

    @Query("SELECT * FROM purchases ORDER BY dateMillis DESC")
    fun purchases(): Flow<List<Purchase>>

    @Query("SELECT * FROM purchases ORDER BY dateMillis DESC")
    suspend fun purchasesNow(): List<Purchase>

    @Query("SELECT * FROM readings ORDER BY atMillis")
    fun readings(): Flow<List<Reading>>

    @Query("SELECT * FROM readings ORDER BY atMillis")
    suspend fun readingsNow(): List<Reading>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPurchase(p: Purchase): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMeter(m: Meter): Long

    @Insert
    suspend fun insertReading(r: Reading): Long

    @Update
    suspend fun updateMeter(m: Meter)

    @Update
    suspend fun updatePurchase(p: Purchase)

    @Delete
    suspend fun deletePurchase(p: Purchase)

    @Delete
    suspend fun deleteReading(r: Reading)

    @Query("UPDATE purchases SET meterNumber = :to WHERE meterNumber = :from")
    suspend fun movePurchases(from: String, to: String)

    @Query("UPDATE readings SET meterNumber = :to WHERE meterNumber = :from")
    suspend fun moveReadings(from: String, to: String)

    @Query("DELETE FROM meters WHERE number = :n")
    suspend fun deleteMeter(n: String)

    @Query("DELETE FROM purchases")
    suspend fun clearPurchases()

    @Query("DELETE FROM meters")
    suspend fun clearMeters()

    @Query("DELETE FROM readings")
    suspend fun clearReadings()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE purchases ADD COLUMN kind TEXT NOT NULL DEFAULT 'TOKEN'")
        db.execSQL("CREATE TABLE IF NOT EXISTS readings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, meterNumber TEXT NOT NULL, units REAL NOT NULL, atMillis INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_readings_meterNumber ON readings(meterNumber)")
        db.execSQL("INSERT INTO readings (meterNumber, units, atMillis) SELECT number, balanceUnits, balanceAt FROM meters WHERE balanceUnits IS NOT NULL AND balanceAt IS NOT NULL")
    }
}

@Database(entities = [Meter::class, Purchase::class, Reading::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "mytoken.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("mytoken", Context.MODE_PRIVATE)
    fun threshold(c: Context): Int = sp(c).getInt("threshold", 3)
    fun setThreshold(c: Context, v: Int) = sp(c).edit().putInt("threshold", v).apply()
    fun lastLow(c: Context, n: String): Long = sp(c).getLong("low_$n", 0L)
    fun setLastLow(c: Context, n: String, t: Long) = sp(c).edit().putLong("low_$n", t).apply()
    fun selectedMeter(c: Context): String? = sp(c).getString("selected", null)
    fun setSelectedMeter(c: Context, v: String) = sp(c).edit().putString("selected", v).apply()
}
