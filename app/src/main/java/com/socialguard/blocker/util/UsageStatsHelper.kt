package com.socialguard.blocker.util

import android.app.AppOpsManager
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
     */
    fun isAppInForeground(context: Context, packageName: String): Boolean {
        if (!hasUsageStatsPermission(context)) return false

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000, now)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName == packageName
    }
}
