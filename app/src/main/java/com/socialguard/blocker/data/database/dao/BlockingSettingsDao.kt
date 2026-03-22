package com.socialguard.blocker.data.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.socialguard.blocker.data.model.BlockingSettings

@Dao
interface BlockingSettingsDao {

    @Query("SELECT * FROM blocking_settings WHERE id = 1")
    fun observeSettings(): LiveData<BlockingSettings?>

    @Query("SELECT * FROM blocking_settings WHERE id = 1")
    suspend fun getSettings(): BlockingSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: BlockingSettings)

    @Query("UPDATE blocking_settings SET blockInstagram = :value WHERE id = 1")
    suspend fun setBlockInstagram(value: Boolean)

    @Query("UPDATE blocking_settings SET blockFacebook = :value WHERE id = 1")
    suspend fun setBlockFacebook(value: Boolean)

    @Query("UPDATE blocking_settings SET popupShownTodayInstagram = :shown, popupShownTodayFacebook = :shown, popupShownDate = :date WHERE id = 1")
    suspend fun resetDailyPopupFlags(shown: Boolean, date: String)

    @Query("UPDATE blocking_settings SET popupShownTodayInstagram = :value WHERE id = 1")
    suspend fun setPopupShownInstagram(value: Boolean)

    @Query("UPDATE blocking_settings SET popupShownTodayFacebook = :value WHERE id = 1")
    suspend fun setPopupShownFacebook(value: Boolean)

    @Query("UPDATE blocking_settings SET currentStreak = :streak, streakLastDate = :date WHERE id = 1")
    suspend fun updateStreak(streak: Int, date: String)
}
