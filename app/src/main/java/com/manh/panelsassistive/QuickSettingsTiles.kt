package com.manh.panelsassistive

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
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
        requestAddTile(context, serviceClass, label, Icon.createWithResource(context, iconResource))
    }

    /** Adds the custom tile that mirrors the first "Thao tác và cử chỉ" quick action. */
    fun requestAddQuickAction(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.add_tile_from_quick_settings, Toast.LENGTH_LONG).show()
            return
        }
        val action = AppPrefs.quickTilePrimaryAction(context)
        requestAddTile(
            context,
            QuickActionTileService::class.java,
            actionLabel(context, action) ?: context.getString(R.string.app_name),
            actionIcon(context, action)
        )
    }

    private fun requestAddTile(
        context: Context,
        serviceClass: Class<*>,
        label: String,
        icon: Icon
    ) {
        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
        if (statusBarManager == null) {
            Toast.makeText(context, R.string.tile_request_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        statusBarManager.requestAddTileService(
            ComponentName(context, serviceClass),
            label,
            icon,
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
        TileService.requestListeningState(
            context,
            ComponentName(context, QuickActionTileService::class.java)
        )
    }

    fun actionIcon(context: Context, action: String, sizePx: Int = 96): Icon {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = LucideGlyphs.typeface(context)
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
            textSize = sizePx * 0.82f
        }
        val glyph = LucideGlyphs.glyph(context, ActionGlyphView.iconFor(ActionDispatcher.selectedActionType(action)))
        if (glyph.isNotEmpty()) {
            val metrics = paint.fontMetrics
            Canvas(bitmap).drawText(
                glyph,
                sizePx / 2f,
                sizePx / 2f - (metrics.ascent + metrics.descent) / 2f,
                paint
            )
        }
        return Icon.createWithBitmap(bitmap)
    }

    fun actionLabel(context: Context, action: String): String? =
        ActionDispatcher.selectedActionLabel(action)
            ?: ActionDispatcher.quickActions(context)
                .firstOrNull { it.id == ActionDispatcher.selectedActionType(action) }
                ?.label

    fun isActionActive(context: Context, action: String): Boolean =
        when (ActionDispatcher.selectedActionType(action)) {
            ActionDispatcher.FLASHLIGHT -> AppPrefs.flashlightEnabled(context)
            ActionDispatcher.TOGGLE_FLOAT_BALL -> AppPrefs.floatBallEnabled(context) && FloatBallService.running
            ActionDispatcher.BACKGROUND_RECORDING -> BackgroundRecordingService.isActive
            else -> true
        }

    fun actionNeedsOverlay(action: String): Boolean =
        ActionDispatcher.selectedActionType(action) in setOf(
            ActionDispatcher.WIDGET_POPUP,
            ActionDispatcher.EXPANSION_PANEL,
            ActionDispatcher.BRIGHTNESS_SLIDER,
            ActionDispatcher.MEDIA_VOLUME,
            ActionDispatcher.TOGGLE_FLOAT_BALL
        )
}

/** Quick settings tile: tap runs action 1 (its icon is the tile icon), long-press runs action 2. */
class QuickActionTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val action = AppPrefs.quickTilePrimaryAction(this)
        if (action == ActionDispatcher.NONE) {
            collapseTo(GestureSettingsActivity::class.java)
            return
        }
        if (QuickSettingsTiles.actionNeedsOverlay(action) && !Settings.canDrawOverlays(this)) {
            collapseTo(MainActivity::class.java)
            return
        }
        ActionDispatcher.dispatch(this, action)
        updateTileState()
    }

    private fun updateTileState() {
        val action = AppPrefs.quickTilePrimaryAction(this)
        qsTile?.apply {
            icon = QuickSettingsTiles.actionIcon(this@QuickActionTileService, action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                label = QuickSettingsTiles.actionLabel(this@QuickActionTileService, action)
                    ?: getString(R.string.app_name)
            }
            state = if (QuickSettingsTiles.isActionActive(this@QuickActionTileService, action)) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    private fun collapseTo(activity: Class<*>) {
        val intent = Intent(this, activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (activity == MainActivity::class.java) {
            intent.putExtra(MainActivity.EXTRA_REQUEST_OVERLAY_PERMISSION, true)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                102,
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

/**
 * Opened by the system when the quick action tile is long-pressed
 * (QS_TILE_PREFERENCES). Runs action 2 and disappears without animation.
 */
class QuickTileActionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launcher shortcuts also land here: the hidden task runs the action and
        // closes, so the settings page never comes to the front. A missing
        // overlay permission is handled by FloatBallService with the system
        // screen, so the app itself still never opens.
        val shortcut = intent.getStringExtra(MainActivity.EXTRA_SHORTCUT_ACTION)
        if (shortcut != null) {
            ActionDispatcher.dispatch(this, shortcut)
            finish()
            return
        }
        val action = AppPrefs.quickTileSecondaryAction(this)
        if (action != ActionDispatcher.NONE) {
            if (QuickSettingsTiles.actionNeedsOverlay(action) && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_REQUEST_OVERLAY_PERMISSION, true)
                )
            } else {
                ActionDispatcher.dispatch(this, action)
            }
            finish()
            return
        }
        startActivity(Intent(this, GestureSettingsActivity::class.java))
        finish()
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
