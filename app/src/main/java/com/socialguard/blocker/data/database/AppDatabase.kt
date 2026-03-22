package com.socialguard.blocker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.socialguard.blocker.data.database.dao.BlockingSettingsDao
import com.socialguard.blocker.data.database.dao.SessionRecordDao
import com.socialguard.blocker.data.database.dao.UsageRecordDao
import com.socialguard.blocker.data.model.BlockingSettings
import com.socialguard.blocker.data.model.SessionRecord
import com.socialguard.blocker.data.model.UsageRecord

@Database(
    entities = [BlockingSettings::class, UsageRecord::class, SessionRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockingSettingsDao(): BlockingSettingsDao
    abstract fun usageRecordDao(): UsageRecordDao
    abstract fun sessionRecordDao(): SessionRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "social_guard.db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Insert default settings row with explicit column values
                            db.execSQL(
                                """INSERT OR IGNORE INTO blocking_settings (
                                    id, blockInstagram, blockFacebook,
                                    blockInstagramReels, blockInstagramExplore, blockInstagramDMs,
                                    blockFacebookMarketplace, blockFacebookWatch, blockFacebookGaming,
                                    timeLockEnabled, timeLockStartHour, timeLockStartMinute,
                                    timeLockEndHour, timeLockEndMinute,
                                    sessionLimitEnabled, sessionLimitMinutes,
                                    showUsagePopup, popupShownTodayInstagram, popupShownTodayFacebook,
                                    popupShownDate
                                ) VALUES (
                                    1, 0, 0, 0, 0, 0, 0, 0, 0,
                                    0, 22, 0, 7, 0,
                                    0, 5,
                                    1, 0, 0, ''
                                )"""
                            )
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
