package com.quell.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.quell.app.R
import com.quell.app.util.QuotesProvider
import com.quell.app.util.TimeUtils

/**
 * Manages the full-screen blocking overlay and the daily check-in popup.
 *
 * THREADING: [showBlockOverlay], [showDailyPopup], and [dismissOverlay] must always be called
 * from the MAIN thread. The check-and-add is then atomic within the single-threaded main looper,
 * eliminating the TOCTOU race that a nested mainHandler.post would create.
 */
class BlockingOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Only written/read on the main thread — no lock needed.
    private var overlayView: View? = null
    private var snoozeEndTime: Long = 0L

    val isSnoozed: Boolean get() = System.currentTimeMillis() < snoozeEndTime

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Show full-screen blocking overlay. Must be called on the MAIN thread.
     */
    fun showBlockOverlay(
        packageName: String,
        reason: String,
        todayMinutes: Long,
        onGoHome: () -> Unit,
        onSnooze: (() -> Unit)? = null
    ) {
        assertMainThread()
        if (overlayView != null) return   // already showing — atomic on main thread

        val inflater = LayoutInflater.from(context)
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
    }

    /**
     * Show the daily check-in popup. Must be called on the MAIN thread.
     */
    fun showDailyPopup(
        packageName: String,
        todayMinutes: Long,
        onContinue: () -> Unit,
        onBlockForToday: () -> Unit
    ) {
        assertMainThread()
        if (overlayView != null) return

        val inflater = LayoutInflater.from(context)
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
            dismissOverlay()
            onContinue()
        }
        view.findViewById<Button>(R.id.btn_popup_block).setOnClickListener {
            dismissOverlay()
            onBlockForToday()
        }

        addOverlay(view, Gravity.CENTER)
    }

    /**
     * Remove the overlay if one is showing. Safe to call from any thread.
     */
    fun dismissOverlay() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeCurrent()
        } else {
            mainHandler.post { removeCurrent() }
        }
    }

    fun isShowing(): Boolean = overlayView != null

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun addOverlay(view: View, gravity: Int) {
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
            e.printStackTrace()
        }
    }

    private fun removeCurrent() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "showBlockOverlay/showDailyPopup must be called on the main thread"
        }
    }
}
