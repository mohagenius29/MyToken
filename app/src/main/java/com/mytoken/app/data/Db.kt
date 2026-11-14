package com.mytoken.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "meters")
data class Meter(
    @PrimaryKey val number: String,
    val label: String = "",
    val tracked: Boolean = false,
    val balanceUnits: Double? = null,
    val balanceAt: Long? = null
)

fun Meter.display(): String = label.ifBlank { number }

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
    val manual: Boolean = false
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPurchase(p: Purchase): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMeter(m: Meter): Long

    @Update
    suspend fun updateMeter(m: Meter)

    @Update
    suspend fun updatePurchase(p: Purchase)

    @Delete
    suspend fun deletePurchase(p: Purchase)

    @Query("DELETE FROM purchases")
    suspend fun clearPurchases()

    @Query("DELETE FROM meters")
    suspend fun clearMeters()
}

@Database(entities = [Meter::class, Purchase::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "mytoken.db")
                .build().also { instance = it }
        }
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("mytoken", Context.MODE_PRIVATE)
    fun threshold(c: Context): Int = sp(c).getInt("threshold", 3)
    fun setThreshold(c: Context, v: Int) = sp(c).edit().putInt("threshold", v).apply()
    fun selectedMeter(c: Context): String? = sp(c).getString("selected", null)
    fun setSelectedMeter(c: Context, v: String) = sp(c).edit().putString("selected", v).apply()
}
