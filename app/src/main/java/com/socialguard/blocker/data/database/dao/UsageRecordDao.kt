package com.quell.app.data.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.quell.app.data.model.UsageRecord

@Dao
interface UsageRecordDao {

    @Query("SELECT * FROM usage_records WHERE packageName = :pkg AND date = :date LIMIT 1")
    suspend fun getRecord(pkg: String, date: String): UsageRecord?

    @Query("SELECT * FROM usage_records WHERE date = :date")
    fun observeRecordsForDate(date: String): LiveData<List<UsageRecord>>

    @Query("SELECT * FROM usage_records WHERE packageName = :pkg ORDER BY date DESC LIMIT :limit")
    fun observeRecentRecords(pkg: String, limit: Int = 7): LiveData<List<UsageRecord>>

    @Query("SELECT * FROM usage_records WHERE packageName = :pkg ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentRecords(pkg: String, limit: Int = 7): List<UsageRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: UsageRecord)

    @Query("""
        INSERT OR REPLACE INTO usage_records (packageName, date, totalMinutes, sessionCount)
        VALUES (:pkg, :date,
            COALESCE((SELECT totalMinutes FROM usage_records WHERE packageName = :pkg AND date = :date), 0) + :addMinutes,
            COALESCE((SELECT sessionCount FROM usage_records WHERE packageName = :pkg AND date = :date), 0) + :addSessions
        )
    """)
    suspend fun addUsage(pkg: String, date: String, addMinutes: Long, addSessions: Int = 0)

    @Query("DELETE FROM usage_records WHERE date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String)
}
