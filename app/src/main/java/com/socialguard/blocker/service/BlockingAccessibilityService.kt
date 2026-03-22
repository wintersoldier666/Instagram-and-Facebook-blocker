package com.quell.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.quell.app.data.model.BlockingSettings
import com.quell.app.data.repository.BlockingRepository
import com.quell.app.overlay.BlockingOverlayManager
import com.quell.app.util.TimeUtils
import com.quell.app.util.UsageStatsHelper
import kotlinx.coroutines.*

class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var repository: BlockingRepository
    private lateinit var overlayManager: BlockingOverlayManager

    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine exception", t) }
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    // Session tracking — @Volatile so reads from the main-thread timer Runnable
    // see writes made by IO coroutines.
    @Volatile private var currentSessionId: Long = -1L
    @Volatile private var currentSessionStartMs: Long = 0L
    @Volatile private var currentForegroundPkg: String = ""
    @Volatile private var sessionTimerRunnable: Runnable? = null

    @Volatile private var cachedSettings: BlockingSettings? = null
    private var settingsLastFetch: Long = 0L
    private val settingsCacheTtlMs = 5_000L

    companion object {
        private const val TAG = "QuellService"

        // Nav-tab hints — used with TYPE_VIEW_SELECTED events (precise, no tree scan needed)
        private val REELS_HINTS       = setOf("reels")
        private val EXPLORE_HINTS     = setOf("search", "explore")
        private val WATCH_HINTS       = setOf("watch", "videos")
        private val MARKETPLACE_HINTS = setOf("marketplace")

        // Non-tab screen hints — used with tree traversal on content-change events
        private val DM_HINTS      = setOf("direct", "messages", "instagram direct")
        private val GAMING_HINTS  = setOf("gaming", "facebook gaming")

        var instance: BlockingAccessibilityService? = null
            private set
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repository = BlockingRepository.getInstance(applicationContext)
        overlayManager = BlockingOverlayManager(applicationContext)
        startMonitoringService()
        Log.i(TAG, "Accessibility service connected")
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

    // -------------------------------------------------------------------------
    // Event routing
    // -------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {

            // Window changed — handle master blocks, time limits, session tracking.
            // Also dismiss any lingering overlay if the user has left a tracked app.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(pkg)
            }

            // Tab selected — precise signal for nav-tab section blocking (Reels, Watch, etc.).
            // TYPE_VIEW_SELECTED fires exactly when a tab becomes selected; no tree scan needed.
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                if (pkg !in BlockingRepository.TRACKED_PACKAGES) return
                val desc = event.contentDescription?.toString()?.lowercase() ?: ""
                val text = event.text.firstOrNull()?.toString()?.lowercase() ?: ""
                val label = if (desc.isNotEmpty()) desc else text
                if (label.isNotEmpty()) handleTabSelected(pkg, label)
            }

            // Content / click — for non-tab screen detection (DMs, Gaming).
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (pkg !in BlockingRepository.TRACKED_PACKAGES) return
                val rootNode = try { rootInActiveWindow }
                    catch (e: Exception) { null } ?: return
                serviceScope.launch {
                    val settings = getSettings() ?: run {
                        runCatching { rootNode.recycle() }; return@launch
                    }
                    checkNonTabSections(pkg, settings, rootNode)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Window state handling (master block, time limits, session)
    // -------------------------------------------------------------------------

    private fun handleWindowStateChanged(pkg: String) {
        val wasTracked = currentForegroundPkg in BlockingRepository.TRACKED_PACKAGES
        val isTracked  = pkg in BlockingRepository.TRACKED_PACKAGES

        if (wasTracked && currentForegroundPkg != pkg) {
            // User left a tracked app — end session and dismiss any overlay immediately.
            // Without this, the Instagram/Facebook overlay stays visible over every other
            // app (banking apps, launcher, etc.) because TYPE_APPLICATION_OVERLAY floats
            // above all windows.
            endCurrentSession()
            mainHandler.post { overlayManager.dismissOverlay() }
        }

        currentForegroundPkg = pkg

        if (!isTracked) return

        serviceScope.launch {
            try {
                val settings   = getSettings() ?: return@launch
                val todayMin   = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)

                // 1. Master block toggle
                val masterBlocked = when {
                    pkg == BlockingRepository.INSTAGRAM_PKG && settings.blockInstagram -> true
                    pkg in setOf(BlockingRepository.FACEBOOK_PKG, BlockingRepository.FACEBOOK_LITE_PKG)
                        && settings.blockFacebook -> true
                    else -> false
                }
                if (masterBlocked) {
                    triggerBlock(pkg, "This app is blocked by Quell", todayMin, allowSnooze = false)
                    return@launch
                }

                // 2. Daily limit
                if (settings.dailyLimitEnabled) {
                    val limit = if (pkg == BlockingRepository.INSTAGRAM_PKG)
                        settings.dailyLimitMinutesInstagram else settings.dailyLimitMinutesFacebook
                    if (todayMin >= limit) {
                        val name = if (pkg == BlockingRepository.INSTAGRAM_PKG) "Instagram" else "Facebook"
                        triggerBlock(pkg, "Daily limit of ${limit}m reached for $name",
                            todayMin, allowSnooze = false)
                        return@launch
                    }
                }

                // 3. Time lock
                if (settings.timeLockEnabled && TimeUtils.isCurrentlyInBlockedRange(
                        settings.timeLockStartHour, settings.timeLockStartMinute,
                        settings.timeLockEndHour, settings.timeLockEndMinute)) {
                    val start = TimeUtils.formatTime(settings.timeLockStartHour, settings.timeLockStartMinute)
                    val end   = TimeUtils.formatTime(settings.timeLockEndHour, settings.timeLockEndMinute)
                    triggerBlock(pkg, "Blocked between $start and $end", todayMin, allowSnooze = false)
                    return@launch
                }

                // 4. Per-session limit — check elapsed time on each window event as backup
                if (settings.sessionLimitEnabled && currentSessionId >= 0) {
                    val elapsed = TimeUtils.elapsedMinutes(currentSessionStartMs)
                    if (elapsed >= settings.sessionLimitMinutes) {
                        triggerBlock(pkg,
                            "You've reached your ${settings.sessionLimitMinutes}-minute session limit",
                            todayMin, allowSnooze = true)
                        return@launch
                    }
                }

                // 5. Snooze active — skip popup and session start
                if (overlayManager.isSnoozed) {
                    if (currentSessionId < 0) startNewSession(pkg)
                    return@launch
                }

                // 6. Daily usage popup (once per day)
                repository.refreshDailyPopupFlags()
                val showPopup = settings.showUsagePopup && when {
                    pkg == BlockingRepository.INSTAGRAM_PKG && !settings.popupShownTodayInstagram -> true
                    pkg in setOf(BlockingRepository.FACEBOOK_PKG, BlockingRepository.FACEBOOK_LITE_PKG)
                        && !settings.popupShownTodayFacebook -> true
                    else -> false
                }
                if (showPopup) {
                    repository.markPopupShown(pkg)
                    mainHandler.post {
                        overlayManager.showDailyPopup(
                            packageName  = pkg,
                            todayMinutes = todayMin,
                            onContinue   = { serviceScope.launch { startNewSession(pkg) } },
                            onBlockForToday = {
                                serviceScope.launch {
                                    val s = getSettings() ?: return@launch
                                    repository.saveSettings(
                                        if (pkg == BlockingRepository.INSTAGRAM_PKG)
                                            s.copy(blockInstagram = true)
                                        else s.copy(blockFacebook = true)
                                    )
                                    cachedSettings = null
                                    mainHandler.post { performGlobalAction(GLOBAL_ACTION_HOME) }
                                }
                            }
                        )
                    }
                    return@launch
                }

                // 7. Start / continue session
                if (currentSessionId < 0 || currentForegroundPkg != pkg) {
                    startNewSession(pkg)
                }

                // 8. Schedule session-limit timer — only once per session
                if (settings.sessionLimitEnabled && sessionTimerRunnable == null) {
                    scheduleSessionLimitCheck(pkg, settings.sessionLimitMinutes)
                }

            } catch (e: Exception) {
                Log.e(TAG, "handleWindowStateChanged coroutine error", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tab-selection blocking (TYPE_VIEW_SELECTED — precise, no tree scan)
    // -------------------------------------------------------------------------

    /**
     * Called when a view becomes selected (e.g. a bottom-nav tab is tapped).
     * [label] is the lowercase contentDescription or text of the selected view.
     *
     * This is far more reliable than scanning the whole accessibility tree because:
     *  - It fires only when the user actively navigates to a tab.
     *  - It doesn't require isSelected to be set correctly by the app.
     *  - It avoids false positives from nav icons that are always in the tree.
     */
    private fun handleTabSelected(pkg: String, label: String) {
        serviceScope.launch {
            try {
                val settings = getSettings() ?: return@launch
                val todayMin = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)

                when (pkg) {
                    BlockingRepository.INSTAGRAM_PKG -> {
                        if (settings.blockInstagramReels && REELS_HINTS.any { label.contains(it) }) {
                            triggerBlock(pkg, "Instagram Reels is blocked", todayMin, allowSnooze = false)
                            return@launch
                        }
                        if (settings.blockInstagramExplore && EXPLORE_HINTS.any { label.contains(it) }) {
                            triggerBlock(pkg, "Instagram Explore is blocked", todayMin, allowSnooze = false)
                            return@launch
                        }
                    }
                    BlockingRepository.FACEBOOK_PKG,
                    BlockingRepository.FACEBOOK_LITE_PKG -> {
                        if (settings.blockFacebookWatch && WATCH_HINTS.any { label.contains(it) }) {
                            triggerBlock(pkg, "Facebook Watch is blocked", todayMin, allowSnooze = false)
                            return@launch
                        }
                        if (settings.blockFacebookMarketplace && MARKETPLACE_HINTS.any { label.contains(it) }) {
                            triggerBlock(pkg, "Facebook Marketplace is blocked", todayMin, allowSnooze = false)
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleTabSelected error", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Non-tab section blocking (DMs, Gaming — no nav tab, need tree scan)
    // -------------------------------------------------------------------------

    private suspend fun checkNonTabSections(
        pkg: String,
        settings: BlockingSettings,
        rootNode: AccessibilityNodeInfo
    ) {
        try {
            val todayMin by lazy { runBlocking { UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg) } }

            when (pkg) {
                BlockingRepository.INSTAGRAM_PKG -> {
                    if (settings.blockInstagramDMs && treeContainsHints(rootNode, DM_HINTS)) {
                        triggerBlock(pkg, "Instagram Direct Messages are blocked", todayMin, false)
                    }
                }
                BlockingRepository.FACEBOOK_PKG,
                BlockingRepository.FACEBOOK_LITE_PKG -> {
                    if (settings.blockFacebookGaming && treeContainsHints(rootNode, GAMING_HINTS)) {
                        triggerBlock(pkg, "Facebook Gaming is blocked", todayMin, false)
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Returns true if any node in the tree has text/contentDescription matching a hint.
     * Does not require any selection state — used only for non-tab screens (DMs, Gaming)
     * whose labels won't appear in nav tabs, so false positives are unlikely.
     */
    private fun treeContainsHints(root: AccessibilityNodeInfo, hints: Set<String>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var found = false
        try {
            while (queue.isNotEmpty() && !found) {
                val node = queue.removeFirst()
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                if (hints.any { desc.contains(it) || text.contains(it) }) found = true
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                if (node !== root) node.recycle()
            }
        } finally {
            queue.forEach { if (it !== root) runCatching { it.recycle() } }
        }
        return found
    }

    // -------------------------------------------------------------------------
    // Trigger / overlay
    // -------------------------------------------------------------------------

    private fun triggerBlock(pkg: String, reason: String, todayMinutes: Long, allowSnooze: Boolean) {
        mainHandler.post {
            if (overlayManager.isShowing()) return@post
            performGlobalAction(GLOBAL_ACTION_HOME)
            overlayManager.showBlockOverlay(
                packageName  = pkg,
                reason       = reason,
                todayMinutes = todayMinutes,
                onGoHome     = { performGlobalAction(GLOBAL_ACTION_HOME) },
                onSnooze     = if (allowSnooze) ({
                    serviceScope.launch { startNewSession(pkg) }
                }) else null
            )
        }
    }

    // -------------------------------------------------------------------------
    // Session tracking
    // -------------------------------------------------------------------------

    private suspend fun startNewSession(pkg: String) {
        endCurrentSession()
        currentSessionId      = repository.startSession(pkg)
        currentSessionStartMs = System.currentTimeMillis()
        currentForegroundPkg  = pkg
    }

    private fun endCurrentSession() {
        cancelSessionTimer()
        if (currentSessionId >= 0) {
            val id      = currentSessionId
            val startMs = currentSessionStartMs
            val pkg     = currentForegroundPkg
            serviceScope.launch { repository.endSessionDirect(id, pkg, startMs) }
            currentSessionId      = -1L
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
                    triggerBlock(pkg,
                        "You've reached your ${settings.sessionLimitMinutes}-minute session limit",
                        todayMin, allowSnooze = true)
                }
            }
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun cancelSessionTimer() {
        sessionTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionTimerRunnable = null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun getSettings(): BlockingSettings? {
        val now = System.currentTimeMillis()
        if (cachedSettings == null || now - settingsLastFetch > settingsCacheTtlMs) {
            cachedSettings    = repository.getSettings()
            settingsLastFetch = now
        }
        return cachedSettings
    }

    fun invalidateSettingsCache() { cachedSettings = null }

    private fun startMonitoringService() {
        try {
            applicationContext.startForegroundService(
                Intent(applicationContext, MonitoringForegroundService::class.java)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring service", e)
        }
    }
}
