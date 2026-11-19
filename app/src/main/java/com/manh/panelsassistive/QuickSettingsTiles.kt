package com.manh.panelsassistive

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

object QuickSettingsTiles {
    fun requestAdd(context: Context, accessibilityTile: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.add_tile_from_quick_settings, Toast.LENGTH_LONG).show()
            return
        }
        val serviceClass = if (accessibilityTile) {
            AccessibilitySettingsTileService::class.java
        } else {
            PanelsAssistiveTileService::class.java
        }
        val label = context.getString(
            if (accessibilityTile) R.string.accessibility_tile_label else R.string.panels_tile_label
        )
        val iconResource = if (accessibilityTile) {
            R.drawable.ic_accessibility_settings_tile
        } else {
            R.drawable.ic_panels_assistive_tile
        }
        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
        if (statusBarManager == null) {
            Toast.makeText(context, R.string.tile_request_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        statusBarManager.requestAddTileService(
            ComponentName(context, serviceClass),
            label,
            Icon.createWithResource(context, iconResource),
            context.mainExecutor
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.tile_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.tile_already_added
                else -> R.string.tile_not_added
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun updateListeningServices(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        TileService.requestListeningState(
            context,
            ComponentName(context, PanelsAssistiveTileService::class.java)
        )
        TileService.requestListeningState(
            context,
            ComponentName(context, AccessibilitySettingsTileService::class.java)
        )
    }
}

class PanelsAssistiveTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REQUEST_OVERLAY_PERMISSION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            collapseTo(intent)
            return
        }
        if (AppPrefs.floatBallEnabled(this) && FloatBallService.running) {
            FloatBallService.stop(this)
        } else {
            FloatBallService.start(this)
        }
        qsTile?.apply {
            state = if (AppPrefs.floatBallEnabled(this@PanelsAssistiveTileService)) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    private fun updateTileState() {
        qsTile?.apply {
            icon = Icon.createWithResource(this@PanelsAssistiveTileService, R.drawable.ic_panels_assistive_tile)
            state = if (FloatBallService.running && AppPrefs.floatBallEnabled(this@PanelsAssistiveTileService)) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    private fun collapseTo(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                100,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

class AccessibilitySettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            icon = Icon.createWithResource(
                this@AccessibilitySettingsTileService,
                R.drawable.ic_accessibility_settings_tile
            )
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                101,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
