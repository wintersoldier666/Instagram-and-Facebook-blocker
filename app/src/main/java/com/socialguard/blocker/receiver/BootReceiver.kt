package com.quell.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.quell.app.service.MonitoringForegroundService
import com.quell.app.util.PermissionHelper

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (PermissionHelper.isAccessibilityServiceEnabled(context)) {
                try {
                    val serviceIntent = Intent(context, MonitoringForegroundService::class.java)
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
