package com.quell.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.quell.app.MainActivity
import com.quell.app.R
import com.quell.app.data.repository.BlockingRepository
import com.quell.app.overlay.BlockingOverlayManager
import com.quell.app.util.PermissionHelper
import com.quell.app.util.TimeUtils
import com.quell.app.util.UsageStatsHelper
import kotlinx.coroutines.*

/**
 * Persistent foreground service.
 *
 * Two roles:
 * 1. Always shows the "Quell Active" notification to keep the process alive
 *    so that BlockingAccessibilityService can reliably restart after OEM kills.
 *
 * 2. BANKING-SAFE MODE — when the user has disabled the Accessibility Service
 *    (so banking apps can't detect it), this service takes over basic blocking:
 *    it polls UsageStatsManager every second and shows the blocking overlay when
 *    a master-blocked, time-locked, or daily-limited app enters the foreground.
 *    Section-level blocking (Reels, Watch, etc.) is not available in this mode.
 */
class MonitoringForegroundService : Service() {

    companion object {
        private const val TAG = "QuellMonitor"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "quell_monitor"
        private const val CHANNEL_NAME = "Quell Monitor"
        private const val POLL_INTERVAL_MS = 1_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine error", t) }
    )

    private lateinit var repository: BlockingRepository
    private lateinit var overlayManager: BlockingOverlayManager

    // Polling runnable — only active when accessibility service is off
    private var pollRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        repository = BlockingRepository.getInstance(applicationContext)
        overlayManager = BlockingOverlayManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        maybeStartPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        serviceScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Banking-safe polling
    // -------------------------------------------------------------------------

    /**
     * Only poll when the accessibility service is not running. When it IS running it
     * handles all blocking; polling would be redundant and waste battery.
     */
    private fun maybeStartPolling() {
        if (PermissionHelper.isAccessibilityServiceEnabled(applicationContext)) {
            // Accessibility service is doing the work — no need to poll.
            stopPolling()
            return
        }
        if (!UsageStatsHelper.hasUsageStatsPermission(applicationContext)) {
            Log.w(TAG, "No usage stats permission — cannot poll in banking-safe mode")
            return
        }
        if (pollRunnable != null) return  // already polling
        Log.i(TAG, "Accessibility service off — starting banking-safe polling")
        pollRunnable = object : Runnable {
            override fun run() {
                // Re-check: if accessibility comes back, stop polling and let it handle things
                if (PermissionHelper.isAccessibilityServiceEnabled(applicationContext)) {
                    Log.i(TAG, "Accessibility re-enabled — stopping banking-safe poll")
                    pollRunnable = null
                    return
                }
                checkAndBlock()
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        mainHandler.post(pollRunnable!!)
    }

    private fun stopPolling() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun checkAndBlock() {
        if (overlayManager.isShowing()) return  // already blocked

        serviceScope.launch {
            try {
                // Find which (if any) tracked app is currently in foreground
                val foregroundPkg = BlockingRepository.TRACKED_PACKAGES
                    .firstOrNull { UsageStatsHelper.isAppInForeground(applicationContext, it) }
                    ?: return@launch  // no tracked app in foreground

                val settings = repository.getSettings()
                val todayMin = UsageStatsHelper.getTodayUsageMinutes(applicationContext, foregroundPkg)

                // --- Same rule order as BlockingAccessibilityService ---

                // 1. Master block
                val masterBlocked = when {
                    foregroundPkg == BlockingRepository.INSTAGRAM_PKG && settings.blockInstagram -> true
                    foregroundPkg in setOf(
                        BlockingRepository.FACEBOOK_PKG,
                        BlockingRepository.FACEBOOK_LITE_PKG
                    ) && settings.blockFacebook -> true
                    else -> false
                }
                if (masterBlocked) {
                    showBlock(foregroundPkg, "This app is blocked by Quell", todayMin)
                    return@launch
                }

                // 2. Daily time limit
                if (settings.dailyLimitEnabled) {
                    val limit = if (foregroundPkg == BlockingRepository.INSTAGRAM_PKG)
                        settings.dailyLimitMinutesInstagram else settings.dailyLimitMinutesFacebook
                    if (todayMin >= limit) {
                        val name = if (foregroundPkg == BlockingRepository.INSTAGRAM_PKG)
                            "Instagram" else "Facebook"
                        showBlock(foregroundPkg, "Daily limit of ${limit}m reached for $name", todayMin)
                        return@launch
                    }
                }

                // 3. Time lock
                if (settings.timeLockEnabled && TimeUtils.isCurrentlyInBlockedRange(
                        settings.timeLockStartHour, settings.timeLockStartMinute,
                        settings.timeLockEndHour, settings.timeLockEndMinute
                    )
                ) {
                    showBlock(
                        foregroundPkg,
                        "Blocked between ${TimeUtils.formatTime(settings.timeLockStartHour, settings.timeLockStartMinute)}" +
                            " and ${TimeUtils.formatTime(settings.timeLockEndHour, settings.timeLockEndMinute)}",
                        todayMin
                    )
                    return@launch
                }

            } catch (e: Exception) {
                Log.e(TAG, "checkAndBlock error", e)
            }
        }
    }

    private fun showBlock(pkg: String, reason: String, todayMin: Long) {
        mainHandler.post {
            if (overlayManager.isShowing()) return@post
            overlayManager.showBlockOverlay(
                packageName  = pkg,
                reason       = reason,
                todayMinutes = todayMin,
                onGoHome     = {
                    // No accessibility service — use an explicit home intent
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(homeIntent)
                },
                onSnooze = null
            )
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Quell monitoring active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quell Active")
            .setContentText("Monitoring Instagram & Facebook usage")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}
