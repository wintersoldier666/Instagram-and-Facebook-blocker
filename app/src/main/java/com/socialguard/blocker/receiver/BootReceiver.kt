package com.quell.app.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.quell.app.MainActivity
import com.quell.app.R
import com.quell.app.service.MonitoringForegroundService
import com.quell.app.util.PermissionHelper

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (PermissionHelper.isAccessibilityServiceEnabled(context)) {
                    startMonitoringService(context)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // App was updated. The accessibility service process was killed by Android.
                // On most devices it won't rebind automatically — the user needs to toggle
                // it off and back on in Accessibility Settings. Notify them.
                showUpdateNotification(context)
                if (PermissionHelper.isAccessibilityServiceEnabled(context)) {
                    startMonitoringService(context)
                }
            }
        }
    }

    private fun startMonitoringService(context: Context) {
        try {
            val serviceIntent = Intent(context, MonitoringForegroundService::class.java)
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showUpdateNotification(context: Context) {
        val channelId = "quell_update"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "App Updates", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Quell updated — action required")
            .setContentText("Go to Settings → Accessibility → Quell → turn OFF then ON to restart blocking.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Quell was updated. Android requires you to restart the Accessibility Service for the new version to take effect.\n\nGo to: Settings → Accessibility → Quell → toggle OFF, then ON."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(42, notification)
    }
}
