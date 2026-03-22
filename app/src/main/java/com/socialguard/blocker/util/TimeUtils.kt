package com.socialguard.blocker.util

import java.util.*
import java.util.concurrent.TimeUnit

object TimeUtils {

    /**
     * Returns true if the current time falls within the blocked range.
     * Handles overnight ranges (e.g., 22:00 – 07:00).
     */
    fun isCurrentlyInBlockedRange(
        startHour: Int, startMinute: Int,
        endHour: Int, endMinute: Int
    ): Boolean {
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute

        return if (startMinutes <= endMinutes) {
            // Same-day range (e.g., 09:00 – 17:00)
            nowMinutes in startMinutes..endMinutes
        } else {
            // Overnight range (e.g., 22:00 – 07:00)
            nowMinutes >= startMinutes || nowMinutes <= endMinutes
        }
    }

    /**
     * Format minutes into "Xh Ym" string.
     */
    fun formatDuration(totalMinutes: Long): String {
        if (totalMinutes <= 0) return "0 min"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /**
     * Format milliseconds into a human-readable duration.
     */
    fun formatMs(ms: Long): String = formatDuration(ms / 60_000)

    /**
     * Returns today's date string "yyyy-MM-dd".
     */
    fun today(): String {
        val cal = Calendar.getInstance()
        return String.format(
            Locale.getDefault(),
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * Returns a human-readable time string like "10:30 PM".
     */
    fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(Locale.getDefault(), "%d:%02d %s", displayHour, minute, amPm)
    }

    /**
     * Returns how many minutes have elapsed since [startTimeMs].
     */
    fun elapsedMinutes(startTimeMs: Long): Long =
        TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - startTimeMs)
}
