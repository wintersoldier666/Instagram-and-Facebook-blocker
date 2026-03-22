package com.socialguard.blocker.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.socialguard.blocker.data.database.AppDatabase
import com.socialguard.blocker.data.model.BlockingSettings
import com.socialguard.blocker.data.model.SessionRecord
import com.socialguard.blocker.data.model.UsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class BlockingRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val settingsDao = db.blockingSettingsDao()
    private val usageDao = db.usageRecordDao()
    private val sessionDao = db.sessionRecordDao()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun today(): String = dateFormat.format(Date())

    // ---------- Settings ----------

    fun observeSettings(): LiveData<BlockingSettings?> = settingsDao.observeSettings()

    suspend fun getSettings(): BlockingSettings = withContext(Dispatchers.IO) {
        settingsDao.getSettings() ?: BlockingSettings().also { settingsDao.saveSettings(it) }
    }

    suspend fun saveSettings(settings: BlockingSettings) = withContext(Dispatchers.IO) {
        settingsDao.saveSettings(settings)
    }

    suspend fun setBlockInstagram(value: Boolean) = withContext(Dispatchers.IO) {
        settingsDao.setBlockInstagram(value)
    }

    suspend fun setBlockFacebook(value: Boolean) = withContext(Dispatchers.IO) {
        settingsDao.setBlockFacebook(value)
    }

    suspend fun markPopupShown(packageName: String) = withContext(Dispatchers.IO) {
        when {
            packageName.contains("instagram") -> settingsDao.setPopupShownInstagram(true)
            packageName.contains("facebook") -> settingsDao.setPopupShownFacebook(true)
        }
    }

    /**
     * Call at the end of a day (or when the date changes) to evaluate and update the streak.
     * Increments if daily usage was within the limit for both apps; resets otherwise.
     */
    suspend fun evaluateAndUpdateStreak(
        instagramMinutes: Long,
        facebookMinutes: Long
    ) = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettings() ?: return@withContext
        if (!settings.dailyLimitEnabled) return@withContext
        val today = today()
        if (settings.streakLastDate == today) return@withContext // already evaluated today

        val underLimit = instagramMinutes <= settings.dailyLimitMinutesInstagram &&
                facebookMinutes <= settings.dailyLimitMinutesFacebook
        val newStreak = if (underLimit) settings.currentStreak + 1 else 0
        settingsDao.updateStreak(newStreak, today)
    }

    // Reset daily popup flags if the stored date differs from today
    suspend fun refreshDailyPopupFlags() = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettings() ?: return@withContext
        val today = today()
        if (settings.popupShownDate != today) {
            settingsDao.saveSettings(
                settings.copy(
                    popupShownTodayInstagram = false,
                    popupShownTodayFacebook = false,
                    popupShownDate = today
                )
            )
        }
    }

    // ---------- Usage Records ----------

    fun observeTodayRecords(): LiveData<List<UsageRecord>> = usageDao.observeRecordsForDate(today())

    fun observeRecentInstagram(): LiveData<List<UsageRecord>> =
        usageDao.observeRecentRecords(INSTAGRAM_PKG, 7)

    fun observeRecentFacebook(): LiveData<List<UsageRecord>> =
        usageDao.observeRecentRecords(FACEBOOK_PKG, 7)

    suspend fun addUsageMinutes(packageName: String, minutes: Long) = withContext(Dispatchers.IO) {
        usageDao.addUsage(packageName, today(), minutes, 0)
    }

    suspend fun getTodayMinutes(packageName: String): Long = withContext(Dispatchers.IO) {
        usageDao.getRecord(packageName, today())?.totalMinutes ?: 0L
    }

    // ---------- Session Records ----------

    suspend fun startSession(packageName: String): Long = withContext(Dispatchers.IO) {
        val session = SessionRecord(
            packageName = packageName,
            startTime = System.currentTimeMillis(),
            date = today()
        )
        val id = sessionDao.insertSession(session)
        // Increment session count
        usageDao.addUsage(packageName, today(), 0, 1)
        id
    }

    // Kept for API compatibility — prefer endSessionDirect
    suspend fun endSession(sessionId: Long, packageName: String, startTimeMs: Long) =
        endSessionDirect(sessionId, packageName, startTimeMs)

    suspend fun endSessionDirect(sessionId: Long, packageName: String, startTimeMs: Long) =
        withContext(Dispatchers.IO) {
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTimeMs
            sessionDao.updateSession(sessionId, endTime, duration)
            val minutes = duration / 60_000L
            if (minutes > 0) {
                usageDao.addUsage(packageName, today(), minutes, 0)
            }
        }

    companion object {
        const val INSTAGRAM_PKG = "com.instagram.android"
        const val FACEBOOK_PKG = "com.facebook.katana"
        const val FACEBOOK_LITE_PKG = "com.facebook.lite"

        val TRACKED_PACKAGES = setOf(INSTAGRAM_PKG, FACEBOOK_PKG, FACEBOOK_LITE_PKG)

        @Volatile
        private var INSTANCE: BlockingRepository? = null

        fun getInstance(context: Context): BlockingRepository {
            return INSTANCE ?: synchronized(this) {
                BlockingRepository(context).also { INSTANCE = it }
            }
        }
    }
}
