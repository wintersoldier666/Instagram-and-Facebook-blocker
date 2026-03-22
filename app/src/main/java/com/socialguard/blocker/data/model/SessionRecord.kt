package com.quell.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_records")
data class SessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startTime: Long,       // epoch millis
    val endTime: Long = 0,
    val durationMs: Long = 0,
    val date: String = ""      // "yyyy-MM-dd" for easy querying
)
