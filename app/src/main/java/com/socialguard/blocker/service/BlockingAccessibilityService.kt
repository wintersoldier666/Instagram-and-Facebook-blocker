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
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine error", t) }
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var currentSessionId: Long = -1L
    @Volatile private var currentSessionStartMs: Long = 0L
    @Volatile private var currentSessionPkg: String = ""   // pkg the active session belongs to
    @Volatile private var sessionTimerRunnable: Runnable? = null

    @Volatile private var cachedSettings: BlockingSettings? = null
    private var settingsLastFetch: Long = 0L
    private val settingsCacheTtlMs = 5_000L

    // Throttle content-changed tree scans (DMs / Gaming) to at most once per 2 seconds.
    private var lastTreeScanMs: Long = 0L

    companion object {
        private const val TAG = "QuellService"

        // Nav tab labels as they appear in contentDescription / text (lowercase).
        // These come from event.source on TYPE_VIEW_CLICKED — no tree traversal needed.
        private val REELS_LABELS       = setOf("reels")
        private val EXPLORE_LABELS     = setOf("search", "explore", "search and explore")
        private val WATCH_LABELS       = setOf("watch", "video", "videos", "facebook watch")
        private val MARKETPLACE_LABELS = setOf("marketplace")

        // Non-tab screen hints for tree traversal (DMs / Gaming).
        private val DM_HINTS     = setOf("direct", "instagram direct")
        private val GAMING_HINTS = setOf("gaming", "facebook gaming")

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
        Log.i(TAG, "Service connected")
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
        if (pkg !in BlockingRepository.TRACKED_PACKAGES) return  // XML restricts this too, belt+braces

        when (event.eventType) {

            // App opened / screen changed — run master checks (block toggle, time limits, session)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                serviceScope.launch { handleAppOpened(pkg) }
            }

            // User tapped something — check if it's a blocked nav tab via event.source.
            // event.source gives the exact clicked view's contentDescription/text directly,
            // so no tree traversal is needed. This is the most reliable way to detect
            // Instagram/Facebook tab navigation across all app versions.
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                val source = event.source ?: return
                val desc = source.contentDescription?.toString()?.lowercase()?.trim() ?: ""
                val text = source.text?.toString()?.lowercase()?.trim() ?: ""
                source.recycle()
                val label = if (desc.isNotEmpty()) desc else text
                if (label.isNotEmpty()) {
                    serviceScope.launch { handleNavLabel(pkg, label) }
                }
            }

            // Content changed — tree scan for non-tab screens (DMs, Gaming), throttled.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastTreeScanMs < 2_000) return
                lastTreeScanMs = now
                val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
                serviceScope.launch {
                    val settings = getSettings() ?: run { runCatching { root.recycle() }; return@launch }
                    checkNonTabSections(pkg, settings, root)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Master checks — run on every window state change
    // -------------------------------------------------------------------------

    private suspend fun handleAppOpened(pkg: String) {
        try {
            val settings = getSettings() ?: return
            val todayMin = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)

            // 1. Master block
            val masterBlocked = when {
                pkg == BlockingRepository.INSTAGRAM_PKG && settings.blockInstagram -> true
                pkg in setOf(BlockingRepository.FACEBOOK_PKG, BlockingRepository.FACEBOOK_LITE_PKG)
                    && settings.blockFacebook -> true
                else -> false
            }
            if (masterBlocked) {
                triggerBlock(pkg, "This app is blocked by Quell", todayMin, snooze = false)
                return
            }

            // 2. Daily time limit
            if (settings.dailyLimitEnabled) {
                val limit = if (pkg == BlockingRepository.INSTAGRAM_PKG)
                    settings.dailyLimitMinutesInstagram else settings.dailyLimitMinutesFacebook
                if (todayMin >= limit) {
                    val name = if (pkg == BlockingRepository.INSTAGRAM_PKG) "Instagram" else "Facebook"
                    triggerBlock(pkg, "Daily limit of ${limit}m reached for $name", todayMin, snooze = false)
                    return
                }
            }

            // 3. Time lock
            if (settings.timeLockEnabled && TimeUtils.isCurrentlyInBlockedRange(
                    settings.timeLockStartHour, settings.timeLockStartMinute,
                    settings.timeLockEndHour, settings.timeLockEndMinute)) {
                triggerBlock(pkg, "Blocked between ${TimeUtils.formatTime(settings.timeLockStartHour,
                    settings.timeLockStartMinute)} and ${TimeUtils.formatTime(settings.timeLockEndHour,
                    settings.timeLockEndMinute)}", todayMin, snooze = false)
                return
            }

            // 4. Session limit — check elapsed time; also backed by a scheduled timer
            if (settings.sessionLimitEnabled && currentSessionId >= 0 && currentSessionPkg == pkg) {
                val elapsed = TimeUtils.elapsedMinutes(currentSessionStartMs)
                if (elapsed >= settings.sessionLimitMinutes && !overlayManager.isSnoozed) {
                    triggerBlock(pkg,
                        "You've reached your ${settings.sessionLimitMinutes}-minute session limit",
                        todayMin, snooze = true)
                    return
                }
            }

            // 5. Daily usage popup
            repository.refreshDailyPopupFlags()
            val showPopup = settings.showUsagePopup && when {
                pkg == BlockingRepository.INSTAGRAM_PKG && !settings.popupShownTodayInstagram -> true
                pkg in setOf(BlockingRepository.FACEBOOK_PKG, BlockingRepository.FACEBOOK_LITE_PKG)
                    && !settings.popupShownTodayFacebook -> true
                else -> false
            }
            if (showPopup && !overlayManager.isSnoozed) {
                repository.markPopupShown(pkg)
                mainHandler.post {
                    overlayManager.showDailyPopup(
                        packageName     = pkg,
                        todayMinutes    = todayMin,
                        onContinue      = { serviceScope.launch { ensureSession(pkg, settings) } },
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
                return
            }

            // 6. Start/continue session and schedule limit timer
            ensureSession(pkg, settings)

        } catch (e: Exception) {
            Log.e(TAG, "handleAppOpened error", e)
        }
    }

    // -------------------------------------------------------------------------
    // Nav tab click detection — TYPE_VIEW_CLICKED / TYPE_VIEW_SELECTED
    // -------------------------------------------------------------------------

    /**
     * Called with the lowercase label of the clicked/selected view.
     * Instagram and Facebook nav tabs have contentDescriptions like "Reels", "Watch",
     * "Marketplace", etc. Using event.source directly is more reliable than tree scanning
     * because it reads exactly what the user tapped, regardless of app version.
     */
    private suspend fun handleNavLabel(pkg: String, label: String) {
        try {
            val settings = getSettings() ?: return
            val todayMin by lazy { runBlocking { UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg) } }

            when (pkg) {
                BlockingRepository.INSTAGRAM_PKG -> {
                    if (settings.blockInstagramReels && REELS_LABELS.any { label == it || label.startsWith(it) }) {
                        triggerBlock(pkg, "Instagram Reels is blocked", todayMin, snooze = false); return
                    }
                    if (settings.blockInstagramExplore && EXPLORE_LABELS.any { label == it || label.contains(it) }) {
                        triggerBlock(pkg, "Instagram Explore is blocked", todayMin, snooze = false); return
                    }
                }
                BlockingRepository.FACEBOOK_PKG,
                BlockingRepository.FACEBOOK_LITE_PKG -> {
                    if (settings.blockFacebookWatch && WATCH_LABELS.any { label == it || label.startsWith(it) }) {
                        triggerBlock(pkg, "Facebook Watch is blocked", todayMin, snooze = false); return
                    }
                    if (settings.blockFacebookMarketplace && MARKETPLACE_LABELS.any { label == it }) {
                        triggerBlock(pkg, "Facebook Marketplace is blocked", todayMin, snooze = false); return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleNavLabel error", e)
        }
    }

    // -------------------------------------------------------------------------
    // Non-tab screen detection — tree scan (DMs, Gaming only)
    // -------------------------------------------------------------------------

    private suspend fun checkNonTabSections(
        pkg: String,
        settings: BlockingSettings,
        root: AccessibilityNodeInfo
    ) {
        try {
            val todayMin by lazy { runBlocking { UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg) } }
            when (pkg) {
                BlockingRepository.INSTAGRAM_PKG -> {
                    if (settings.blockInstagramDMs && treeContains(root, DM_HINTS))
                        triggerBlock(pkg, "Instagram Direct Messages are blocked", todayMin, false)
                }
                BlockingRepository.FACEBOOK_PKG,
                BlockingRepository.FACEBOOK_LITE_PKG -> {
                    if (settings.blockFacebookGaming && treeContains(root, GAMING_HINTS))
                        triggerBlock(pkg, "Facebook Gaming is blocked", todayMin, false)
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun treeContains(root: AccessibilityNodeInfo, hints: Set<String>): Boolean {
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
    // Overlay trigger
    // -------------------------------------------------------------------------

    private fun triggerBlock(pkg: String, reason: String, todayMin: Long, snooze: Boolean) {
        mainHandler.post {
            if (overlayManager.isShowing()) return@post
            performGlobalAction(GLOBAL_ACTION_HOME)
            overlayManager.showBlockOverlay(
                packageName  = pkg,
                reason       = reason,
                todayMinutes = todayMin,
                onGoHome     = { performGlobalAction(GLOBAL_ACTION_HOME) },
                onSnooze     = if (snooze) ({
                    serviceScope.launch { ensureSession(pkg, null) }
                }) else null
            )
        }
    }

    // -------------------------------------------------------------------------
    // Session tracking
    // -------------------------------------------------------------------------

    /**
     * Ensures a session is running for [pkg]. If a session already exists for this pkg,
     * does nothing (preserving the original start time and timer).
     * If the pkg changed (user switched tracked apps) the old session is ended first.
     */
    private suspend fun ensureSession(pkg: String, settings: BlockingSettings?) {
        if (currentSessionId >= 0 && currentSessionPkg == pkg) {
            // Session already running for this app — just make sure the timer is set
            val s = settings ?: getSettings() ?: return
            if (s.sessionLimitEnabled && sessionTimerRunnable == null) {
                scheduleSessionLimitTimer(pkg, s.sessionLimitMinutes)
            }
            return
        }
        // Either no session running, or a different app's session was active
        endCurrentSession()
        currentSessionId      = repository.startSession(pkg)
        currentSessionStartMs = System.currentTimeMillis()
        currentSessionPkg     = pkg
        val s = settings ?: getSettings() ?: return
        if (s.sessionLimitEnabled && sessionTimerRunnable == null) {
            scheduleSessionLimitTimer(pkg, s.sessionLimitMinutes)
        }
    }

    private fun endCurrentSession() {
        cancelSessionTimer()
        if (currentSessionId >= 0) {
            val id = currentSessionId; val startMs = currentSessionStartMs; val pkg = currentSessionPkg
            serviceScope.launch { repository.endSessionDirect(id, pkg, startMs) }
            currentSessionId = -1L; currentSessionStartMs = 0L; currentSessionPkg = ""
        }
    }

    private fun scheduleSessionLimitTimer(pkg: String, limitMinutes: Int) {
        cancelSessionTimer()
        val remaining = (limitMinutes * 60_000L) - (System.currentTimeMillis() - currentSessionStartMs)
        if (remaining <= 0) return  // already over — will be caught on next window event
        sessionTimerRunnable = Runnable {
            if (currentSessionPkg == pkg && currentSessionId >= 0 && !overlayManager.isSnoozed) {
                serviceScope.launch {
                    val todayMin = UsageStatsHelper.getTodayUsageMinutes(applicationContext, pkg)
                    val settings = getSettings() ?: return@launch
                    triggerBlock(pkg,
                        "You've reached your ${settings.sessionLimitMinutes}-minute session limit",
                        todayMin, snooze = true)
                }
            }
        }.also { mainHandler.postDelayed(it, remaining.coerceAtLeast(1000)) }
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
