package com.quell.app.util

import android.content.Context

object BlockingModePrefs {
    private const val PREFS_NAME = "quell_mode"
    private const val KEY_MODE = "blocking_mode"

    /** Full accessibility service — section blocking (Reels, Watch, DMs, etc.) available.
     *  Banking apps will detect the service. */
    const val MODE_ACCESSIBILITY = "accessibility"

    /** UsageStats polling, no accessibility service.
     *  Banking safe. App-level blocking only (master block / time lock / daily limit). */
    const val MODE_USAGE_STATS = "usage_stats"

    /** Local VPN DNS blocking + overlay, no accessibility service.
     *  Banking safe. App-level blocking only. Shows VPN key icon in status bar. */
    const val MODE_VPN = "vpn"

    fun getMode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_ACCESSIBILITY) ?: MODE_ACCESSIBILITY

    fun setMode(context: Context, mode: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
}
