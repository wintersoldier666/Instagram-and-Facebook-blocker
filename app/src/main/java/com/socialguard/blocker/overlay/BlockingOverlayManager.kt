package com.socialguard.blocker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.socialguard.blocker.R
import com.socialguard.blocker.util.QuotesProvider
import com.socialguard.blocker.util.TimeUtils

class BlockingOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var snoozeEndTime: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    val isSnoozed: Boolean get() = System.currentTimeMillis() < snoozeEndTime

    /**
     * Show full-screen blocking overlay.
     * @param packageName  the blocked app's package
     * @param reason       short reason string shown to user
     * @param todayMinutes today's usage in minutes
     * @param onGoHome     callback invoked when user taps "Go Back"
     * @param onSnooze     callback invoked when user taps "5 more minutes" (null = hide snooze button)
     */
    fun showBlockOverlay(
        packageName: String,
        reason: String,
        todayMinutes: Long,
        onGoHome: () -> Unit,
        onSnooze: (() -> Unit)? = null
    ) {
        if (overlayView != null) return   // Already showing

        mainHandler.post {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.overlay_blocking, null)

            val appName = if (packageName.contains("instagram")) "Instagram" else "Facebook"

            view.findViewById<TextView>(R.id.tvAppName).text = appName
            view.findViewById<TextView>(R.id.tvReason).text = reason
            view.findViewById<TextView>(R.id.tvScreentime).text =
                "You've used $appName for ${TimeUtils.formatDuration(todayMinutes)} today"
            view.findViewById<TextView>(R.id.tvQuote).text = QuotesProvider.getNext()

            view.findViewById<Button>(R.id.btnGoHome).setOnClickListener {
                dismissOverlay()
                onGoHome()
            }

            val btnSnooze = view.findViewById<Button>(R.id.btnSnooze)
            if (onSnooze != null) {
                btnSnooze.visibility = View.VISIBLE
                btnSnooze.setOnClickListener {
                    snoozeEndTime = System.currentTimeMillis() + 5 * 60 * 1000L
                    dismissOverlay()
                    onSnooze()
                }
            } else {
                btnSnooze.visibility = View.GONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Remove FLAG_NOT_FOCUSABLE so buttons are clickable
                flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            try {
                windowManager.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Show the daily usage popup — appears once per day when the user opens a tracked app.
     */
    fun showDailyPopup(
        packageName: String,
        todayMinutes: Long,
        onContinue: () -> Unit,
        onBlockForToday: () -> Unit
    ) {
        if (overlayView != null) return

        mainHandler.post {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.dialog_usage_popup, null)

            val appName = if (packageName.contains("instagram")) "Instagram" else "Facebook"

            view.findViewById<TextView>(R.id.tvPopupAppName).text = appName
            view.findViewById<TextView>(R.id.tvPopupScreentime).text =
                if (todayMinutes > 0)
                    "You've already used $appName for ${TimeUtils.formatDuration(todayMinutes)} today."
                else
                    "You haven't used $appName yet today. Stay mindful!"
            view.findViewById<TextView>(R.id.tvPopupQuote).text = QuotesProvider.getNext()

            view.findViewById<Button>(R.id.btnPopupContinue).setOnClickListener {
                dismissOverlay()
                onContinue()
            }
            view.findViewById<Button>(R.id.btnPopupBlock).setOnClickListener {
                dismissOverlay()
                onBlockForToday()
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            try {
                windowManager.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun dismissOverlay() {
        mainHandler.post {
            overlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                overlayView = null
            }
        }
    }

    fun isShowing(): Boolean = overlayView != null
}
