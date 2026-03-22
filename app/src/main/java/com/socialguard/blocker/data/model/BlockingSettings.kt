package com.quell.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocking_settings")
data class BlockingSettings(
    @PrimaryKey val id: Int = 1,

    // Master toggles
    val blockInstagram: Boolean = false,
    val blockFacebook: Boolean = false,

    // Content-specific locks (Instagram)
    val blockInstagramReels: Boolean = false,
    val blockInstagramExplore: Boolean = false,
    val blockInstagramDMs: Boolean = false,

    // Content-specific locks (Facebook)
    val blockFacebookMarketplace: Boolean = false,
    val blockFacebookWatch: Boolean = false,
    val blockFacebookGaming: Boolean = false,

    // Scheduled time lock
    val timeLockEnabled: Boolean = false,
    val timeLockStartHour: Int = 22,
    val timeLockStartMinute: Int = 0,
    val timeLockEndHour: Int = 7,
    val timeLockEndMinute: Int = 0,

    // Per-session time limit
    val sessionLimitEnabled: Boolean = false,
    val sessionLimitMinutes: Int = 5,

    // Daily time limit (block once total daily usage reaches X minutes)
    val dailyLimitEnabled: Boolean = false,
    val dailyLimitMinutesInstagram: Int = 30,
    val dailyLimitMinutesFacebook: Int = 30,

    // Streak tracking (consecutive days at or under daily limit)
    val currentStreak: Int = 0,
    val streakLastDate: String = "",  // "yyyy-MM-dd"

    // Daily usage popup
    val showUsagePopup: Boolean = true,
    val popupShownTodayInstagram: Boolean = false,
    val popupShownTodayFacebook: Boolean = false,
    val popupShownDate: String = ""   // "yyyy-MM-dd"
)
