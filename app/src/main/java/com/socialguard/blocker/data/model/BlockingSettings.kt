package com.socialguard.blocker.data.model

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

    // Daily usage popup
    val showUsagePopup: Boolean = true,
    val popupShownTodayInstagram: Boolean = false,
    val popupShownTodayFacebook: Boolean = false,
    val popupShownDate: String = ""   // "yyyy-MM-dd"
)
