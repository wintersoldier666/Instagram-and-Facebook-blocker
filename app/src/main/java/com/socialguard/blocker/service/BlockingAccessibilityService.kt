package com.quell.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.quell.app.data.model.BlockingSettings
import com.quell.app.data.repository.BlockingRepository
import com.quell.app.overlay.BlockingOverlayManager
import com.quell.app.util.PermissionHelper
import com.quell.app.util.TimeUtils
import com.quell.app.util.UsageStatsHelper
import android.util.Log
import kotlinx.coroutines.*

class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var repository: BlockingRepository
    private lateinit var overlayManager: BlockingOverlayManager

    /**
     * CoroutineExceptionHandler is required. Without it, ANY uncaught exception thrown inside
     * a coroutine launched from this scope propagates to the process-level
     * UncaughtExceptionHandler and crashes the accessibility service.
     */
    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
                CoroutineExceptionHandler { _, throwable ->
                    Log.e("QuellService", "Unhandled coroutine exception", throwable)
                }
    )
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

        // Start foreground monitoring service
        startMonitoringService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(pkg)
                // Also run in-app section check immediately on navigation events so that
                // section blocking (Reels, Watch, etc.) fires the instant the user taps
                // the tab — don't wait for the first content-change event on the new screen.
                if (pkg in BlockingRepository.TRACKED_PACKAGES) {
                    val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
                    serviceScope.launch {
                        val settings = getSettings() ?: run { runCatching { rootNode.recycle() }; return@launch }
                        checkInAppBlocking(pkg, settings, rootNode)
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (pkg in BlockingRepository.TRACKED_PACKAGES) {
                    // Capture root node on the service thread before launching the coroutine.
                    // AccessibilityEvent objects are pooled and recycled after this callback
                    // returns, so they must not be accessed from a background coroutine.
                    val rootNode = try {
                        rootInActiveWindow
                    } catch (e: Exception) {
                        Log.w("QuellService", "rootInActiveWindow threw", e)
                        null
                    } ?: return
                    serviceScope.launch {
                        val settings = getSettings() ?: run {
                            runCatching { rootNode.recycle() }
                            return@launch
                        }
                        checkInAppBlocking(pkg, settings, rootNode)
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
            try {
            val settings = getSettings() ?: return@launch
            val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)

            // 1. Check master block toggle
            val isMasterBlocked = when {
                pkg == BlockingRepository.INSTAGRAM_PKG && settings.blockInstagram -> true
                (pkg == BlockingRepository.FACEBOOK_PKG || pkg == BlockingRepository.FACEBOOK_LITE_PKG) && settings.blockFacebook -> true
                else -> false
            }

            if (isMasterBlocked) {
                triggerBlock(pkg, "This app is blocked by Quell", todayMinutes, allowSnooze = false)
                return@launch
            }

            // 2. Check daily time limit
            if (settings.dailyLimitEnabled) {
                val limit = when {
                    pkg == BlockingRepository.INSTAGRAM_PKG -> settings.dailyLimitMinutesInstagram
                    else -> settings.dailyLimitMinutesFacebook
                }
                if (todayMinutes >= limit) {
                    val appName = if (pkg == BlockingRepository.INSTAGRAM_PKG) "Instagram" else "Facebook"
                    triggerBlock(
                        pkg,
                        "Daily limit of ${limit}m reached for $appName",
                        todayMinutes,
                        allowSnooze = false
                    )
                    return@launch
                }
            }

            // 3. Check time lock (scheduled block)
            if (settings.timeLockEnabled && TimeUtils.isCurrentlyInBlockedRange(
                    settings.timeLockStartHour, settings.timeLockStartMinute,
                    settings.timeLockEndHour, settings.timeLockEndMinute
                )) {
                val startStr = TimeUtils.formatTime(settings.timeLockStartHour, settings.timeLockStartMinute)
                val endStr = TimeUtils.formatTime(settings.timeLockEndHour, settings.timeLockEndMinute)
                triggerBlock(pkg, "Blocked between $startStr and $endStr", todayMinutes, allowSnooze = false)
                return@launch
            }

            // 4. Check session snooze (user tapped "5 more minutes")
            if (overlayManager.isSnoozed) {
                startNewSession(pkg)
                return@launch
            }

            // 5. Check per-session limit (if session already running and exceeded)
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

            // 6. Show daily usage popup (once per day)
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
                                // performGlobalAction must run on main thread
                                mainHandler.post { performGlobalAction(GLOBAL_ACTION_HOME) }
                            }
                        }
                    )
                }
                return@launch
            }

            // 7. Start new session tracking
            if (currentSessionId < 0 || currentForegroundPkg != pkg) {
                startNewSession(pkg)
            }

            // 8. Schedule session limit check — only if not already running.
            // scheduleSessionLimitCheck cancels the previous timer before setting a new one,
            // so calling it on every window-state change (e.g. navigation within the app)
            // would reset the clock each time. We guard with sessionTimerRunnable == null
            // so the timer is only set once when the session starts.
            if (settings.sessionLimitEnabled && sessionTimerRunnable == null) {
                scheduleSessionLimitCheck(pkg, settings.sessionLimitMinutes)
            }
            } catch (e: Exception) {
                Log.e("QuellService", "Error in handleWindowStateChanged coroutine", e)
            }
        }
    }

    /**
     * Checks in-app navigation for content that should be blocked (Reels, Marketplace, etc.).
     * [rootNode] is captured on the service thread before this coroutine launches; caller must
     * NOT use it after this call since we recycle it here.
     */
    private suspend fun checkInAppBlocking(
        pkg: String,
        settings: BlockingSettings,
        rootNode: AccessibilityNodeInfo
    ) {
        try {
            if (pkg == BlockingRepository.INSTAGRAM_PKG) {
                // Reels and Explore are bottom-nav tabs → requireSelected=true prevents
                // false matches from the nav bar icons that are always in the view tree
                if (settings.blockInstagramReels && isNodeMatchingHints(rootNode, INSTAGRAM_REELS_HINTS, requireSelected = true)) {
                    val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                    triggerBlock(pkg, "Instagram Reels is blocked", todayMinutes, allowSnooze = false)
                    return
                }
                if (settings.blockInstagramExplore && isNodeMatchingHints(rootNode, INSTAGRAM_EXPLORE_HINTS, requireSelected = true)) {
                    val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                    triggerBlock(pkg, "Instagram Explore is blocked", todayMinutes, allowSnooze = false)
                    return
                }
                // DMs have no nav tab — match on screen content alone
                if (settings.blockInstagramDMs && isNodeMatchingHints(rootNode, INSTAGRAM_DM_HINTS, requireSelected = false)) {
                    val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                    triggerBlock(pkg, "Instagram Direct Messages are blocked", todayMinutes, allowSnooze = false)
                    return
                }
            } else if (pkg == BlockingRepository.FACEBOOK_PKG || pkg == BlockingRepository.FACEBOOK_LITE_PKG) {
                // Marketplace and Watch are bottom-nav tabs → requireSelected=true
                if (settings.blockFacebookMarketplace && isNodeMatchingHints(rootNode, FACEBOOK_MARKETPLACE_HINTS, requireSelected = true)) {
                    val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                    triggerBlock(pkg, "Facebook Marketplace is blocked", todayMinutes, allowSnooze = false)
                    return
                }
                if (settings.blockFacebookWatch && isNodeMatchingHints(rootNode, FACEBOOK_WATCH_HINTS, requireSelected = true)) {
                    val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                    triggerBlock(pkg, "Facebook Watch is blocked", todayMinutes, allowSnooze = false)
                    return
                }
                // Gaming is in a side menu, not a dedicated nav tab → requireSelected=false
                if (settings.blockFacebookGaming && isNodeMatchingHints(rootNode, FACEBOOK_GAMING_HINTS, requireSelected = false)) {
                    val todayMinutes = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                    triggerBlock(pkg, "Facebook Gaming is blocked", todayMinutes, allowSnooze = false)
                    return
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Traverses the accessibility node tree looking for any node whose contentDescription or text
     * matches any of the provided hints.
     *
     * [requireSelected] = true (default): the matching node must be selected or checked.
     *   Use this for tab-based sections (Reels, Explore, Watch, Marketplace, Gaming) because
     *   nav-bar tab icons are ALWAYS present in the tree and always clickable — requiring
     *   isSelected ensures we only match when the user is actually on that tab.
     *
     * [requireSelected] = false: match on text/description alone.
     *   Use this for screen-based sections (DMs, Gaming menus) that have no nav-bar tab.
     *
     * Every child obtained via getChild() is recycled exactly once — either inline after
     * processing or in the finally block for nodes still in the queue on early exit.
     * The [root] node is NOT recycled here; the caller is responsible for it.
     */
    private fun isNodeMatchingHints(
        root: AccessibilityNodeInfo,
        hints: Set<String>,
        requireSelected: Boolean = true
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var found = false

        try {
            while (queue.isNotEmpty() && !found) {
                val node = queue.removeFirst()
                val desc = node.contentDescription?.toString() ?: ""
                val text = node.text?.toString() ?: ""

                val textMatches = hints.any { hint ->
                    desc.contains(hint, ignoreCase = true) || text.contains(hint, ignoreCase = true)
                }
                val stateOk = !requireSelected || node.isSelected || node.isChecked

                if (textMatches && stateOk) {
                    found = true
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }

                // Recycle the node immediately after processing (root is recycled by the caller)
                if (node !== root) node.recycle()
            }
        } finally {
            // Recycle any nodes left in the queue on early exit (never processed, never recycled)
            queue.forEach { if (it !== root) runCatching { it.recycle() } }
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
        if (::repository.isInitialized) endCurrentSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (::repository.isInitialized) endCurrentSession()
        if (::overlayManager.isInitialized) overlayManager.dismissOverlay()
        serviceScope.cancel()
    }
}
