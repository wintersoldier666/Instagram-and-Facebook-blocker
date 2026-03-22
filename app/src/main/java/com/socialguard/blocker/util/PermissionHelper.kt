package com.quell.app.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object PermissionHelper {

    /**
     * Returns true if the Accessibility Service is enabled.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName
        }
    }

    /**
     * Returns true if the app can draw overlays.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Returns true if the app has Usage Stats permission.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return UsageStatsHelper.hasUsageStatsPermission(context)
    }

    /**
     * Returns true if all required permissions are granted.
     */
    fun allPermissionsGranted(context: Context): Boolean {
        return isAccessibilityServiceEnabled(context) &&
                canDrawOverlays(context) &&
                hasUsageStatsPermission(context)
    }

    /** Intent to open Accessibility Settings */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** Intent to request overlay permission */
    fun overlayPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** Intent to open Usage Stats settings */
    fun usageStatsSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** Intent to open notification settings (Android 8+) */
    fun notificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }
}
