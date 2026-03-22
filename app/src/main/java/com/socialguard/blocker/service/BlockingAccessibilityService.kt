package com.socialguard.blocker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.socialguard.blocker.data.model.BlockingSettings
import com.socialguard.blocker.data.repository.BlockingRepository
import com.socialguard.blocker.overlay.BlockingOverlayManager
import com.socialguard.blocker.util.PermissionHelper
import com.socialguard.blocker.util.TimeUtils
import com.socialguard.blocker.util.UsageStatsHelper
import kotlinx.coroutines.*

class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var repository: BlockingRepository
    private lateinit var overlayManager: BlockingOverlayManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Session tracking
    private var currentSessionId: Long = -1L
    private var currentSessionStartMs: Long = 0L
    private var currentForegroundPkg: String = ""
    private var sessionTimerRunnable: Runnable? = null

    // Cached settings (refreshed on each window event)
    @Volatile private var cachedSettings: BlockingSettings? = null
    private var settingsLastFetch: Long = 0L
    private val settingsCacheTtlMs = 5_000L   // refresh every 5s

    companion object {
        // Instagram content keywords for in-app blocking
        private val INSTAGRAM_REELS_HINTS = setOf("Reels", "reels")
        private val INSTAGRAM_EXPLORE_HINTS = setOf("Search and explore", "Search and Explore", "Explore")
        private val INSTAGRAM_DM_HINTS = setOf("Direct", "Messages", "Instagram Direct")

        // Facebook content keywords
        private val FACEBOOK_MARKETPLACE_HINTS = setOf("Marketplace", "marketplace")
        private val FACEBOOK_WATCH_HINTS = setOf("Watch", "Videos", "Facebook Watch")
        private val FACEBOOK_GAMING_HINTS = setOf("Gaming", "Facebook Gaming")

        var instance: BlockingAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repository = BlockingRepository.getInstance(applicationContext)
        overlayManager = BlockingOverlayManager(applicationContext)

        serviceInfo = serviceInfo.also { info ->
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.notificationTimeout = 100
        }

        // Start foreground monitoring service
        startMonitoringService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (pkg in BlockingRepository.TRACKED_PACKAGES) {
                    serviceScope.launch {
                        val settings = getSettings() ?: return@launch
                        checkInAppBlocking(pkg, settings, event)
                    }
                }
            }
        }
    }

    private fun handleWindowStateChanged(pkg: String) {
        val wasTracked = currentForegroundPkg in BlockingRepository.TRACKED_PACKAGES
        val isTracked = pkg in BlockingRepository.TRACKED_PACKAGES

        if (wasTracked && currentForegroundPkg != pkg) {
            // User left a tracked app — end session
            endCurrentSession()
        }

        if (!isTracked) {
            currentForegroundPkg = pkg
            return
        }

        currentForegroundPkg = pkg

        serviceScope.launch {
            val settings = getSettings() ?: return@launch
            val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)

            // 1. Check master block toggle
            val isMasterBlocked = when {
                pkg == BlockingRepository.INSTAGRAM_PKG && settings.blockInstagram -> true
                (pkg == BlockingRepository.FACEBOOK_PKG || pkg == BlockingRepository.FACEBOOK_LITE_PKG) && settings.blockFacebook -> true
                else -> false
            }

            if (isMasterBlocked) {
                triggerBlock(pkg, "This app is blocked by SocialGuard", todayMinutes, allowSnooze = false)
                return@launch
            }

            // 2. Check time lock (scheduled block)
            if (settings.timeLockEnabled && TimeUtils.isCurrentlyInBlockedRange(
                    settings.timeLockStartHour, settings.timeLockStartMinute,
                    settings.timeLockEndHour, settings.timeLockEndMinute
                )) {
                val startStr = TimeUtils.formatTime(settings.timeLockStartHour, settings.timeLockStartMinute)
                val endStr = TimeUtils.formatTime(settings.timeLockEndHour, settings.timeLockEndMinute)
                triggerBlock(pkg, "Blocked between $startStr and $endStr", todayMinutes, allowSnooze = false)
                return@launch
            }

            // 3. Check session snooze (user tapped "5 more minutes")
            if (overlayManager.isSnoozed) {
                startNewSession(pkg)
                return@launch
            }

            // 4. Check per-session limit (if session already running and exceeded)
            if (settings.sessionLimitEnabled && currentSessionId >= 0 && currentForegroundPkg == pkg) {
                val elapsedMin = TimeUtils.elapsedMinutes(currentSessionStartMs)
                if (elapsedMin >= settings.sessionLimitMinutes) {
                    triggerBlock(
                        pkg,
                        "You've reached your ${settings.sessionLimitMinutes}-minute session limit",
                        todayMinutes,
                        allowSnooze = true
                    )
                    return@launch
                }
            }

            // 5. Show daily usage popup (once per day)
            val shouldShowPopup = settings.showUsagePopup && when {
                pkg == BlockingRepository.INSTAGRAM_PKG && !settings.popupShownTodayInstagram -> true
                (pkg == BlockingRepository.FACEBOOK_PKG || pkg == BlockingRepository.FACEBOOK_LITE_PKG) && !settings.popupShownTodayFacebook -> true
                else -> false
            }

            // Reset popup flags if date changed
            repository.refreshDailyPopupFlags()

            if (shouldShowPopup) {
                repository.markPopupShown(pkg)
                mainHandler.post {
                    overlayManager.showDailyPopup(
                        packageName = pkg,
                        todayMinutes = todayMinutes,
                        onContinue = {
                            serviceScope.launch { startNewSession(pkg) }
                        },
                        onBlockForToday = {
                            serviceScope.launch {
                                val currentSettings = getSettings() ?: return@launch
                                if (pkg == BlockingRepository.INSTAGRAM_PKG) {
                                    repository.saveSettings(currentSettings.copy(blockInstagram = true))
                                } else {
                                    repository.saveSettings(currentSettings.copy(blockFacebook = true))
                                }
                                cachedSettings = null
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }
                        }
                    )
                }
                return@launch
            }

            // 6. Start new session tracking
            if (currentSessionId < 0 || currentForegroundPkg != pkg) {
                startNewSession(pkg)
            }

            // 7. Schedule session limit check
            if (settings.sessionLimitEnabled) {
                scheduleSessionLimitCheck(pkg, settings.sessionLimitMinutes)
            }
        }
    }

    /**
     * Checks in-app navigation for content that should be blocked (Reels, Marketplace, etc.)
     */
    private suspend fun checkInAppBlocking(
        pkg: String,
        settings: BlockingSettings,
        event: AccessibilityEvent
    ) {
        val rootNode = rootInActiveWindow ?: return

        if (pkg == BlockingRepository.INSTAGRAM_PKG) {
            if (settings.blockInstagramReels && isNodeMatchingHints(rootNode, INSTAGRAM_REELS_HINTS)) {
                val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                triggerBlock(pkg, "Instagram Reels is blocked", todayMinutes, allowSnooze = false)
                return
            }
            if (settings.blockInstagramExplore && isNodeMatchingHints(rootNode, INSTAGRAM_EXPLORE_HINTS)) {
                val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                triggerBlock(pkg, "Instagram Explore is blocked", todayMinutes, allowSnooze = false)
                return
            }
            if (settings.blockInstagramDMs && isNodeMatchingHints(rootNode, INSTAGRAM_DM_HINTS)) {
                val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                triggerBlock(pkg, "Instagram Direct Messages are blocked", todayMinutes, allowSnooze = false)
                return
            }
        } else if (pkg == BlockingRepository.FACEBOOK_PKG || pkg == BlockingRepository.FACEBOOK_LITE_PKG) {
            if (settings.blockFacebookMarketplace && isNodeMatchingHints(rootNode, FACEBOOK_MARKETPLACE_HINTS)) {
                val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                triggerBlock(pkg, "Facebook Marketplace is blocked", todayMinutes, allowSnooze = false)
                return
            }
            if (settings.blockFacebookWatch && isNodeMatchingHints(rootNode, FACEBOOK_WATCH_HINTS)) {
                val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                triggerBlock(pkg, "Facebook Watch is blocked", todayMinutes, allowSnooze = false)
                return
            }
            if (settings.blockFacebookGaming && isNodeMatchingHints(rootNode, FACEBOOK_GAMING_HINTS)) {
                val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                triggerBlock(pkg, "Facebook Gaming is blocked", todayMinutes, allowSnooze = false)
                return
            }
        }

        rootNode.recycle()
    }

    /**
     * Traverses accessibility node tree looking for any node whose contentDescription or text
     * matches any of the provided hints. Focuses on bottom navigation bar nodes.
     */
    private fun isNodeMatchingHints(root: AccessibilityNodeInfo, hints: Set<String>): Boolean {
        // Check for clicked/focused/selected nodes with matching descriptions
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var found = false

        outer@ while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""

            if (hints.any { hint ->
                    desc.contains(hint, ignoreCase = true) || text.contains(hint, ignoreCase = true)
                } && (node.isSelected || node.isChecked || node.isFocused || node.isClickable)) {
                found = true
                break@outer
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return found
    }

    private fun triggerBlock(
        pkg: String,
        reason: String,
        todayMinutes: Long,
        allowSnooze: Boolean
    ) {
        mainHandler.post {
            if (!overlayManager.isShowing()) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                overlayManager.showBlockOverlay(
                    packageName = pkg,
                    reason = reason,
                    todayMinutes = todayMinutes,
                    onGoHome = {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    },
                    onSnooze = if (allowSnooze) ({
                        // snooze handled inside BlockingOverlayManager
                        serviceScope.launch { startNewSession(pkg) }
                    }) else null
                )
            }
        }
    }

    private suspend fun startNewSession(pkg: String) {
        endCurrentSession()
        currentSessionId = repository.startSession(pkg)
        currentSessionStartMs = System.currentTimeMillis()
        currentForegroundPkg = pkg
    }

    private fun endCurrentSession() {
        cancelSessionTimer()
        if (currentSessionId >= 0) {
            val id = currentSessionId
            val startMs = currentSessionStartMs
            val pkg = currentForegroundPkg
            serviceScope.launch {
                repository.endSessionDirect(id, pkg, startMs)
            }
            currentSessionId = -1L
            currentSessionStartMs = 0L
        }
    }

    private fun scheduleSessionLimitCheck(pkg: String, limitMinutes: Int) {
        cancelSessionTimer()
        val delayMs = limitMinutes * 60_000L
        sessionTimerRunnable = Runnable {
            if (currentForegroundPkg == pkg && currentSessionId >= 0) {
                serviceScope.launch {
                    val settings = getSettings() ?: return@launch
                    val todayMin = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                    triggerBlock(
                        pkg,
                        "You've reached your ${settings.sessionLimitMinutes}-minute session limit",
                        todayMin,
                        allowSnooze = true
                    )
                }
            }
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun cancelSessionTimer() {
        sessionTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionTimerRunnable = null
    }

    private suspend fun getSettings(): BlockingSettings? {
        val now = System.currentTimeMillis()
        if (cachedSettings == null || now - settingsLastFetch > settingsCacheTtlMs) {
            cachedSettings = repository.getSettings()
            settingsLastFetch = now
        }
        return cachedSettings
    }

    fun invalidateSettingsCache() {
        cachedSettings = null
    }

    private fun startMonitoringService() {
        try {
            val intent = Intent(applicationContext, MonitoringForegroundService::class.java)
            applicationContext.startForegroundService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {
        endCurrentSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        endCurrentSession()
        overlayManager.dismissOverlay()
        serviceScope.cancel()
    }
}
