package com.quell.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.*

object UsageStatsHelper {

    /**
     * Returns true if the app has Usage Stats permission granted.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Returns total foreground time in minutes for [packageName] today.
     */
    fun getTodayUsageMinutes(context: Context, packageName: String): Long {
        if (!hasUsageStatsPermission(context)) return 0L

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance()
        val endTime = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        return stats
            .filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }
            .div(60_000L)
    }

    /**
     * Returns a map of date-string -> minutes for the past [days] days.
     */
    fun getUsageForLastDays(
        context: Context,
        packageName: String,
        days: Int = 7
    ): Map<String, Long> {
        if (!hasUsageStatsPermission(context)) return emptyMap()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance()
        val endTime = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -days)
        val startTime = cal.timeInMillis

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

        return stats
            .filter { it.packageName == packageName && it.totalTimeInForeground > 0 }
            .associate { stat ->
                val dateCal = Calendar.getInstance().apply { timeInMillis = stat.firstTimeStamp }
                val dateStr = String.format(
                    Locale.getDefault(),
                    "%04d-%02d-%02d",
                    dateCal.get(Calendar.YEAR),
                    dateCal.get(Calendar.MONTH) + 1,
                    dateCal.get(Calendar.DAY_OF_MONTH)
                )
                dateStr to stat.totalTimeInForeground / 60_000L
            }
    }

    /**
     * Returns true if [packageName] is currently the foreground app.
     *
     * Uses UsageEvents (last 30 min) to reliably track MOVE_TO_FOREGROUND /
     * MOVE_TO_BACKGROUND transitions. Falls back to queryUsageStats if no recent
     * events exist (e.g. user has been idle on same screen for >30 min).
     */
    fun isAppInForeground(context: Context, packageName: String): Boolean {
        if (!hasUsageStatsPermission(context)) return false

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        // Query events for last 30 minutes
        val events = usm.queryEvents(now - 30 * 60_000L, now)
        val event = UsageEvents.Event()
        var lastForeground: String? = null
        var hasEvents = false

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            hasEvents = true
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> lastForeground = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND ->
                    if (lastForeground == event.packageName) lastForeground = null
            }
        }

        if (hasEvents) return lastForeground == packageName

        // No events in last 30 min: user hasn't switched apps. Use daily stats to see
        // which app was most recently brought to foreground today.
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, now)
        val mostRecent = stats?.maxByOrNull { it.lastTimeUsed } ?: return false
        return mostRecent.packageName == packageName
    }
}
