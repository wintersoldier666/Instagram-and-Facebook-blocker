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
import com.quell.app.util.BlockingModePrefs
import com.quell.app.util.PermissionHelper
import com.quell.app.util.TimeUtils
import com.quell.app.util.UsageStatsHelper
import kotlinx.coroutines.*

/**
 * Persistent foreground service that:
 *
 * 1. In ACCESSIBILITY mode — just shows the persistent notification to keep the process
 *    alive and let BlockingAccessibilityService do all the blocking work.
 *
 * 2. In USAGE_STATS mode — polls UsageStatsManager every second. When a blocked app
 *    enters the foreground it shows the overlay. The poll loop also handles dismissal
 *    when the user leaves the app (no foreground watcher needed, which was the cause
 *    of the 1-second flicker).
 *
 * 3. In VPN mode — same polling + overlay as USAGE_STATS, while LocalVpnService handles
 *    DNS-level blocking in parallel. The overlay gives clear feedback; the DNS block
 *    ensures the app can't connect even if the overlay is dismissed somehow.
 */
class MonitoringForegroundService : Service() {

    companion object {
        private const val TAG = "QuellMonitor"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "quell_monitor"
        private const val CHANNEL_NAME = "Quell Monitor"
        private const val POLL_INTERVAL_MS = 1_500L  // 1.5 s — responsive but not battery-draining
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine error", t) }
    )

    private lateinit var repository: BlockingRepository
    private lateinit var overlayManager: BlockingOverlayManager

    private var pollRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        repository = BlockingRepository.getInstance(applicationContext)
        overlayManager = BlockingOverlayManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        restartPollingIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        serviceScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Polling management
    // -------------------------------------------------------------------------

    /**
     * Called every time onStartCommand fires (including when MainActivity calls
     * startForegroundService after a mode switch). Decides whether to poll.
     */
    private fun restartPollingIfNeeded() {
        val mode = BlockingModePrefs.getMode(applicationContext)
        val accessibilityOn = PermissionHelper.isAccessibilityServiceEnabled(applicationContext)

        // Don't poll in accessibility mode — the accessibility service handles everything.
        val shouldPoll = when (mode) {
            BlockingModePrefs.MODE_ACCESSIBILITY -> false
            else -> !accessibilityOn  // safety: if accessibility somehow re-enabled, stop polling
        } && UsageStatsHelper.hasUsageStatsPermission(applicationContext)
           && PermissionHelper.canDrawOverlays(applicationContext)

        if (!shouldPoll) {
            if (pollRunnable != null) {
                Log.i(TAG, "Stopping poll (mode=$mode, accessibility=$accessibilityOn)")
                stopPolling()
            }
            return
        }

        // Restart the poll regardless (idempotent — stops old one first).
        stopPolling()
        Log.i(TAG, "Starting poll (mode=$mode)")
        pollRunnable = object : Runnable {
            override fun run() {
                // Re-check on each tick so a mode change takes effect within 1 poll cycle.
                val currentMode = BlockingModePrefs.getMode(applicationContext)
                val accOn = PermissionHelper.isAccessibilityServiceEnabled(applicationContext)
                if (currentMode == BlockingModePrefs.MODE_ACCESSIBILITY || accOn) {
                    Log.i(TAG, "Poll stopping: mode=$currentMode acc=$accOn")
                    pollRunnable = null
                    overlayManager.dismissOverlay()
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

    // -------------------------------------------------------------------------
    // Blocking logic
    // -------------------------------------------------------------------------

    private fun checkAndBlock() {
        serviceScope.launch {
            try {
                // Find which tracked app (if any) is currently in the foreground
                val foregroundPkg = BlockingRepository.TRACKED_PACKAGES
                    .firstOrNull { UsageStatsHelper.isAppInForeground(applicationContext, it) }

                if (foregroundPkg == null) {
                    // No tracked app in foreground — dismiss any lingering overlay
                    if (overlayManager.isShowing()) {
                        mainHandler.post { overlayManager.dismissOverlay() }
                    }
                    return@launch
                }

                val settings = repository.getSettings()
                val todayMin = UsageStatsHelper.getTodayUsageMinutes(applicationContext, foregroundPkg)

                // Determine if the app should be blocked now
                val blockReason: String? = when {
                    // 1. Master block
                    foregroundPkg == BlockingRepository.INSTAGRAM_PKG && settings.blockInstagram ->
                        "Instagram is blocked"
                    foregroundPkg in setOf(
                        BlockingRepository.FACEBOOK_PKG, BlockingRepository.FACEBOOK_LITE_PKG
                    ) && settings.blockFacebook ->
                        "Facebook is blocked"

                    // 2. Daily limit
                    settings.dailyLimitEnabled -> run {
                        val limit = if (foregroundPkg == BlockingRepository.INSTAGRAM_PKG)
                            settings.dailyLimitMinutesInstagram else settings.dailyLimitMinutesFacebook
                        if (todayMin >= limit) {
                            val name = if (foregroundPkg == BlockingRepository.INSTAGRAM_PKG)
                                "Instagram" else "Facebook"
                            "Daily ${limit}m limit reached for $name"
                        } else null
                    }

                    // 3. Time lock
                    settings.timeLockEnabled && TimeUtils.isCurrentlyInBlockedRange(
                        settings.timeLockStartHour, settings.timeLockStartMinute,
                        settings.timeLockEndHour, settings.timeLockEndMinute
                    ) -> "Blocked by time lock (${
                        TimeUtils.formatTime(settings.timeLockStartHour, settings.timeLockStartMinute)
                    }–${
                        TimeUtils.formatTime(settings.timeLockEndHour, settings.timeLockEndMinute)
                    })"

                    else -> null
                }

                if (blockReason != null) {
                    if (!overlayManager.isShowing()) {
                        mainHandler.post {
                            // watchForeground = false: we handle dismiss in this poll loop,
                            // so the built-in watcher is not needed and would cause flicker.
                            overlayManager.showBlockOverlay(
                                packageName    = foregroundPkg,
                                reason         = blockReason,
                                todayMinutes   = todayMin,
                                onGoHome       = {
                                    startActivity(
                                        Intent(Intent.ACTION_MAIN).apply {
                                            addCategory(Intent.CATEGORY_HOME)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    )
                                },
                                onSnooze       = null,
                                watchForeground = false
                            )
                        }
                    }
                } else {
                    // App is in foreground but not blocked — dismiss overlay if it was showing
                    // (e.g. time lock expired while overlay was up)
                    if (overlayManager.isShowing()) {
                        mainHandler.post { overlayManager.dismissOverlay() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkAndBlock error", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
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
