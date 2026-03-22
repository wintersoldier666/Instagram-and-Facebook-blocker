package com.socialguard.blocker.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usage_records",
    indices = [Index(value = ["packageName", "date"], unique = true)]
)
data class UsageRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val date: String,          // "yyyy-MM-dd"
    val totalMinutes: Long = 0,
    val sessionCount: Int = 0
)
