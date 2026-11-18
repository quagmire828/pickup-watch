package com.pickupwatch.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "pickup_codes")
data class PickupCode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "courier") val courier: String?,
    @ColumnInfo(name = "station") val station: String?,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "event_time") val eventTime: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "picked") val picked: Boolean = false
)

@Dao
interface PickupDao {
    @Query("SELECT * FROM pickup_codes WHERE picked = 0 ORDER BY event_time DESC")
    suspend fun getUnpicked(): List<PickupCode>

    @Query("SELECT * FROM pickup_codes WHERE picked = 0 ORDER BY event_time DESC")
    fun observeUnpicked(): Flow<List<PickupCode>>

    @Query("SELECT * FROM pickup_codes ORDER BY event_time DESC")
    suspend fun getAll(): List<PickupCode>

    /**
     * 查找同码+同网点记录（不限 picked 状态）。
     * 已标记已取的不会重新添加。
     */
    @Query("SELECT * FROM pickup_codes WHERE code = :code AND (station = :station OR (:station IS NULL AND station IS NULL)) LIMIT 1")
    suspend fun findExisting(code: String, station: String?): PickupCode?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(code: PickupCode): Long

    @Update
    suspend fun update(code: PickupCode)

    @Query("UPDATE pickup_codes SET picked = 1 WHERE id = :id")
    suspend fun markPicked(id: Long)

    @Query("DELETE FROM pickup_codes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pickup_codes WHERE picked = 1 AND event_time < :before")
    suspend fun cleanOldPicked(before: Long)
}

@Database(entities = [PickupCode::class], version = 1, exportSchema = false)
abstract class PickupDatabase : RoomDatabase() {
    abstract fun pickupDao(): PickupDao

    companion object {
        @Volatile
        private var INSTANCE: PickupDatabase? = null

        fun getInstance(context: Context): PickupDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PickupDatabase::class.java,
                    "pickup.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
