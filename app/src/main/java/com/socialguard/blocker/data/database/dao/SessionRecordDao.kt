package com.socialguard.blocker.data.database.dao

import androidx.room.*
import com.socialguard.blocker.data.model.SessionRecord

@Dao
interface SessionRecordDao {

    @Insert
    suspend fun insertSession(session: SessionRecord): Long

    @Query("UPDATE session_records SET endTime = :endTime, durationMs = :durationMs WHERE id = :id")
    suspend fun updateSession(id: Long, endTime: Long, durationMs: Long)

    @Query("SELECT * FROM session_records WHERE packageName = :pkg AND date = :date ORDER BY startTime DESC")
    suspend fun getSessionsForDate(pkg: String, date: String): List<SessionRecord>

    @Query("SELECT COUNT(*) FROM session_records WHERE packageName = :pkg AND date = :date")
    suspend fun countSessionsForDate(pkg: String, date: String): Int

    @Query("DELETE FROM session_records WHERE date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String)
}
