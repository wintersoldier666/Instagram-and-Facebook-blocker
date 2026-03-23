package com.quell.app.overlay

import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.quell.app.R
import com.quell.app.data.repository.BlockingRepository
import com.quell.app.util.QuotesProvider
import com.quell.app.util.TimeUtils

/**
 * Manages the full-screen blocking overlay and the daily check-in popup.
 *
 * THREADING: [showBlockOverlay], [showDailyPopup], and [dismissOverlay] must be called
 * from the MAIN thread. The check-and-add is atomic on the main looper.
 */
class BlockingOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Quell)
    private val inflater = LayoutInflater.from(themedContext)

    private var overlayView: View? = null
    private var snoozeEndTime: Long = 0L

    // Foreground watcher — polls every second to dismiss overlay if user has left Instagram/Facebook.
    // Needed because the accessibility service is now restricted to tracked packages only,
    // so it no longer receives events from other apps (home launcher, banking apps, etc.).
    private val foregroundWatchRunnable = object : Runnable {
        override fun run() {
            if (overlayView == null) return  // overlay gone, stop polling
            if (!isTrackedAppForeground()) {
                Log.d("QuellOverlay", "Tracked app no longer foreground — dismissing overlay")
                removeCurrent()
                return  // don't reschedule
            }
            mainHandler.postDelayed(this, 1_000)
        }
    }

    val isSnoozed: Boolean get() = System.currentTimeMillis() < snoozeEndTime

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun showBlockOverlay(
        packageName: String,
        reason: String,
        todayMinutes: Long,
        onGoHome: () -> Unit,
        onSnooze: (() -> Unit)? = null,
        // Pass false when the caller (e.g. polling service) handles foreground tracking itself.
        // The watcher polls every second and would fight the service's own poll loop,
        // causing the overlay to flash on/off.
        watchForeground: Boolean = true
    ) {
        assertMainThread()
        if (overlayView != null) return

        val view = inflater.inflate(R.layout.overlay_blocking, null)
        val appName = if (packageName.contains("instagram")) "Instagram" else "Facebook"

        view.findViewById<TextView>(R.id.tv_app_name).text = appName
        view.findViewById<TextView>(R.id.tv_reason).text = reason
        view.findViewById<TextView>(R.id.tv_screentime).text =
            "You've used $appName for ${TimeUtils.formatDuration(todayMinutes)} today"
        view.findViewById<TextView>(R.id.tv_quote).text = QuotesProvider.getNext()

        view.findViewById<Button>(R.id.btn_go_home).setOnClickListener {
            dismissOverlay()
            onGoHome()
        }

        val btnSnooze = view.findViewById<Button>(R.id.btn_snooze)
        if (onSnooze != null) {
            btnSnooze.visibility = View.VISIBLE
            btnSnooze.setOnClickListener {
                snoozeEndTime = System.currentTimeMillis() + 5 * 60 * 1_000L
                dismissOverlay()
                onSnooze()
            }
        } else {
            btnSnooze.visibility = View.GONE
        }

        addOverlay(view, Gravity.TOP or Gravity.START)
        if (watchForeground) startForegroundWatcher()
    }

    fun showDailyPopup(
        packageName: String,
        todayMinutes: Long,
        onContinue: () -> Unit,
        onBlockForToday: () -> Unit
    ) {
        assertMainThread()
        if (overlayView != null) return

        val view = inflater.inflate(R.layout.dialog_usage_popup, null)
        val appName = if (packageName.contains("instagram")) "Instagram" else "Facebook"

        view.findViewById<TextView>(R.id.tv_popup_app_name).text = appName
        view.findViewById<TextView>(R.id.tv_popup_screentime).text =
            if (todayMinutes > 0)
                "You've already used $appName for ${TimeUtils.formatDuration(todayMinutes)} today."
            else
                "You haven't used $appName yet today. Stay mindful!"
        view.findViewById<TextView>(R.id.tv_popup_quote).text = QuotesProvider.getNext()

        view.findViewById<Button>(R.id.btn_popup_continue).setOnClickListener {
            dismissOverlay(); onContinue()
        }
        view.findViewById<Button>(R.id.btn_popup_block).setOnClickListener {
            dismissOverlay(); onBlockForToday()
        }

        addOverlay(view, Gravity.CENTER)
        startForegroundWatcher()
    }

    /** Safe to call from any thread. */
    fun dismissOverlay() {
        if (Looper.myLooper() == Looper.getMainLooper()) removeCurrent()
        else mainHandler.post { removeCurrent() }
    }

    fun isShowing(): Boolean = overlayView != null

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun addOverlay(view: View, gravity: Int) {
        if (!Settings.canDrawOverlays(context)) {
            Log.e("QuellOverlay", "SYSTEM_ALERT_WINDOW not granted")
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            Log.e("QuellOverlay", "addView failed", e)
        }
    }

    private fun removeCurrent() {
        stopForegroundWatcher()
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun startForegroundWatcher() {
        mainHandler.removeCallbacks(foregroundWatchRunnable)
        mainHandler.postDelayed(foregroundWatchRunnable, 1_000)
    }

    private fun stopForegroundWatcher() {
        mainHandler.removeCallbacks(foregroundWatchRunnable)
    }

    /**
     * Returns true if Instagram or Facebook is the current foreground app.
     * Delegates to UsageStatsHelper which uses UsageEvents for reliable detection.
     */
    private fun isTrackedAppForeground(): Boolean {
        return try {
            BlockingRepository.TRACKED_PACKAGES.any {
                com.quell.app.util.UsageStatsHelper.isAppInForeground(context, it)
            }
        } catch (e: Exception) {
            true  // assume still foreground on error to avoid false dismissal
        }
    }

    private fun assertMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e("QuellOverlay", "Called off main thread!", Throwable())
        }
    }
}
