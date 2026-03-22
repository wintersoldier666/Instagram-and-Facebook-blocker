package com.quell.app.service

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.quell.app.util.PermissionHelper

/**
 * Quick Settings tile — lets the user disable/re-enable Quell's accessibility service
 * with a single tap from the notification shade.
 *
 * Banking apps detect ANY active accessibility service and refuse to open.
 * This tile is the fastest path to disabling Quell, using the app, then
 * re-enabling it afterwards.
 *
 * To add: pull down notification shade → long-press any tile → drag "Quell" tile
 * into your Quick Settings panel.
 */
class QuellTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        // Open Accessibility Settings directly — the user toggles Quell off/on there.
        // (Apps cannot programmatically disable their own accessibility service.)
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndCollapse(intent)
    }

    private fun updateTile() {
        val active = PermissionHelper.isAccessibilityServiceEnabled(applicationContext)
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Quell"
            contentDescription = if (active) "Quell blocking active" else "Quell blocking off"
            updateTile()
        }
    }
}
