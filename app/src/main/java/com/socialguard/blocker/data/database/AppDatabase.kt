package com.socialguard.blocker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.socialguard.blocker.data.database.dao.BlockingSettingsDao
import com.socialguard.blocker.data.database.dao.SessionRecordDao
import com.socialguard.blocker.data.database.dao.UsageRecordDao
import com.socialguard.blocker.data.model.BlockingSettings
import com.socialguard.blocker.data.model.SessionRecord
import com.socialguard.blocker.data.model.UsageRecord

@Database(
    entities = [BlockingSettings::class, UsageRecord::class, SessionRecord::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockingSettingsDao(): BlockingSettingsDao
    abstract fun usageRecordDao(): UsageRecordDao
    abstract fun sessionRecordDao(): SessionRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 → v2: add daily limit and streak columns */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE blocking_settings ADD COLUMN dailyLimitEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocking_settings ADD COLUMN dailyLimitMinutesInstagram INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE blocking_settings ADD COLUMN dailyLimitMinutesFacebook INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE blocking_settings ADD COLUMN currentStreak INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocking_settings ADD COLUMN streakLastDate TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "social_guard.db"
                )
                    .addMigrations(MIGRATION_1_2)
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
                                    dailyLimitEnabled, dailyLimitMinutesInstagram, dailyLimitMinutesFacebook,
                                    currentStreak, streakLastDate,
                                    showUsagePopup, popupShownTodayInstagram, popupShownTodayFacebook,
                                    popupShownDate
                                ) VALUES (
                                    1, 0, 0, 0, 0, 0, 0, 0, 0,
                                    0, 22, 0, 7, 0,
                                    0, 5,
                                    0, 30, 30,
                                    0, '',
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
