package com.manh.panelsassistive

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.service.voice.VoiceInteractionService
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

data class PanelItem(
    val type: String,
    val value: String,
    val label: String,
    val position: Int = -1,
    /** Column span; fractional values (2.4) are allowed and drawn exactly. */
    val spanX: Float = 1f,
    /** Row span; fractional values (3.5) are allowed and drawn exactly. */
    val spanY: Float = 1f,
    val instanceId: String = "$type:$value:${UUID.randomUUID()}"
) {
    val id: String get() = instanceId

    /**
     * Grid cells reserved for this item. A 2×3.5 item still occupies four
     * rows: neighbours keep clear of the whole cells, so the remaining half
     * row stays empty below the item instead of being overlapped.
     */
    val occupiedColumns: Int get() = ceil(spanX).toInt().coerceAtLeast(1)
    val occupiedRows: Int get() = ceil(spanY).toInt().coerceAtLeast(1)
}

data class ExpansionShortcut(
    val type: String,
    val value: String,
    val label: String
)

/** What the panel was created for; only the starting geometry differs. */
object PanelKind {
    const val WIDGET = "widget"
    const val APP = "app"
}

data class WidgetPanelPage(
    val id: String,
    val name: String,
    val items: List<PanelItem> = emptyList(),
    val columns: Int = 10,
    val rows: Int = 10,
    val cellWidthDp: Int = 30,
    val rowHeightDp: Int = 30,
    val areaColumns: Int = 10,
    val areaRows: Int = 10,
    val areaPaddingDp: Int = 0,
    val backgroundOpacity: Int = 78,
    val backgroundColor: Int = 0xFF152438.toInt(),
    val kind: String = PanelKind.WIDGET
)

internal object PanelViewportMetrics {
    private const val CELL_GAP_DP = 6
    private const val CELL_MARGIN_DP = 3
    private const val GRID_INSET_DP = 4

    fun widthDp(columns: Int, cellWidthDp: Int) =
        columns.coerceAtLeast(1) * (cellWidthDp.coerceAtLeast(1) + CELL_GAP_DP) + GRID_INSET_DP

    fun heightDp(rows: Int, rowHeightDp: Int) =
        rows.coerceAtLeast(1) * (rowHeightDp.coerceAtLeast(1) + CELL_GAP_DP) + GRID_INSET_DP

    fun panelWidthDp(page: WidgetPanelPage) =
        widthDp(page.areaColumns, page.cellWidthDp) + page.areaPaddingDp.coerceIn(0, 100) * 2

    fun panelHeightDp(page: WidgetPanelPage) =
        heightDp(page.areaRows, page.rowHeightDp) + page.areaPaddingDp.coerceIn(0, 100) * 2

    /**
     * Single source of truth for the pixel geometry of the panel grids.
     *
     * Every slot is `sizePx + gapPx` wide, where `gapPx` is exactly twice the
     * 3dp margin each child gets. A cell spanning n slots therefore has to be
     * `sizePx * n + gapPx * (n - 1)` wide; using `dp(cell * n)` instead left
     * spanned widgets several pixels narrower than the slots they cover and
     * made them drift from the edit guides whenever the cell size changed.
     */
    fun sizePx(context: Context, sizeDp: Int): Int =
        (sizeDp.coerceAtLeast(1) * context.resources.displayMetrics.density).toInt()

    fun marginPx(context: Context): Int =
        (CELL_MARGIN_DP * context.resources.displayMetrics.density).toInt()

    fun gapPx(context: Context): Int = marginPx(context) * 2

    /** 2dp padding that GridLayout grids are created with. */
    fun insetPx(context: Context): Int =
        (2 * context.resources.displayMetrics.density).toInt()

    fun spanPx(context: Context, sizeDp: Int, span: Int): Int {
        val slots = span.coerceAtLeast(1)
        return sizePx(context, sizeDp) * slots + gapPx(context) * (slots - 1)
    }

    /**
     * Fractional span (for example 2.4 columns): cells are drawn exactly and
     * the last cell is only partially covered, so its trailing gap is
     * proportional too.
     */
    fun spanPx(context: Context, sizeDp: Int, span: Float): Int {
        val slots = span.coerceAtLeast(1f)
        return (sizePx(context, sizeDp) * slots + gapPx(context) * (slots - 1f))
            .roundToInt()
            .coerceAtLeast(1)
    }

    /**
     * The spanned slot width converted back to dp with the same rounding used
     * to build the view. RemoteViews sizes are compared against this value, so
     * reporting `sizeDp * span + 6 * (span - 1)` overestimated wide widgets and
     * cropped their right/bottom edge. At one cell the two are identical.
     */
    fun spanDp(context: Context, sizeDp: Int, span: Int): Int =
        (spanPx(context, sizeDp, span) / context.resources.displayMetrics.density).toInt()

    fun spanDp(context: Context, sizeDp: Int, span: Float): Int =
        (spanPx(context, sizeDp, span) / context.resources.displayMetrics.density).toInt()
}

object AppPrefs {
    private const val FILE_NAME = "panels_assistive"
    private const val KEY_FLOAT_BALL_ENABLED = "float_ball_enabled"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_EXPANSION_SHORTCUTS = "expansion_shortcuts"
    private const val MAX_FLOAT_BALL_ICON_BYTES = 16 * 1024 * 1024
    private const val KEY_FLOAT_BALL_SIZE = "float_ball_size"
    private const val KEY_FLOAT_BALL_X = "float_ball_x"
    private const val KEY_FLOAT_BALL_Y = "float_ball_y"
    private const val KEY_FLOAT_BALL_SNAP = "float_ball_snap"
    private const val KEY_PORTRAIT_PANELS = "portrait_panels"
    private const val KEY_LANDSCAPE_PANELS = "landscape_panels"
    private const val KEY_CURRENT_PORTRAIT_PANEL = "current_portrait_panel"
    private const val KEY_CURRENT_LANDSCAPE_PANEL = "current_landscape_panel"
    private const val KEY_PANEL_EDGE_PADDING_MIGRATED = "panel_edge_padding_migrated"
    private const val KEY_PANEL_BACKGROUNDS_MIGRATED = "panel_backgrounds_migrated"
    private const val KEY_LANDSCAPE_LAYOUT_ENABLED = "landscape_layout_enabled"
    private const val KEY_WIDGET_POPUP_ENABLED = "widget_popup_enabled"
    private const val KEY_POPUP_BLUR = "popup_blur"
    private const val KEY_POPUP_BLUR_RADIUS = "popup_blur_radius"
    private const val KEY_POPUP_BLUR_SCOPE = "popup_blur_scope"
    private const val KEY_WIDGET_PANEL_BLUR = "widget_panel_blur"
    private const val KEY_WIDGET_PANEL_BLUR_RADIUS = "widget_panel_blur_radius"
    private const val KEY_WIDGET_PANEL_BLUR_SCOPE = "widget_panel_blur_scope"
    private const val KEY_QUICK_TILE_PRIMARY = "quick_tile_primary"
    private const val KEY_QUICK_TILE_SECONDARY = "quick_tile_secondary"
    private const val KEY_THEME = "app_theme"
    private const val KEY_THEME_ACCENT = "app_theme_accent"
    private const val KEY_SHARED_PANEL_COLOR = "shared_panel_color"
    private const val KEY_SHARED_PANEL_OPACITY = "shared_panel_opacity"
    private const val KEY_SHARED_PANEL_CORNER = "shared_panel_corner"
    private const val KEY_POPUP_PANEL_COLOR = "popup_panel_color"
    private const val KEY_POPUP_PANEL_OPACITY = "popup_panel_opacity"
    private const val KEY_POPUP_PANEL_CORNER = "popup_panel_corner"
    private const val KEY_PANEL_BORDER = "panel_border_enabled"
    private const val KEY_TAB_TRANSITION = "tab_transition"
    private const val KEY_PANEL_FREE_MODE = "panel_free_mode"
    private const val KEY_FREEFORM_APPS = "freeform_apps"
    private const val KEY_FREEFORM_POLICY = "freeform_launch_policy"
    private const val KEY_FREEFORM_MODE = "freeform_oem_mode"
    private const val KEY_FREEFORM_FOLLOW = "freeform_follow_trigger"
    private const val KEY_FREEFORM_PORTRAIT = "freeform_layout_portrait"
    private const val KEY_FREEFORM_LANDSCAPE = "freeform_layout_landscape"
    private const val KEY_ICON_PACK_ENABLED = "icon_pack_enabled"
    private const val KEY_ICON_PACK_PACKAGE = "icon_pack_package"
    private const val KEY_ICON_MASK_ENABLED = "icon_mask_enabled"
    private const val KEY_ICON_OVERRIDES = "icon_overrides"
    private const val KEY_WIDGET_ACCENT = "widget_accent_color"
    private const val KEY_WIDGET_SHORTCUT = "widget_shortcut"
    private const val KEY_WIDGET_CONFIRM = "widget_confirm_before_add"
    private const val KEY_MUSIC_WIDGET_SOURCE = "music_widget_source"
    private const val KEY_MUSIC_WIDGET_PACKAGE = "music_widget_package"

    /** Sentinel in the shared music key: tapping any music widget opens no app. */
    const val WIDGET_NO_APP = "__none__"
    private const val KEY_EXPANSION_SLIDERS = "expansion_sliders"
    private const val KEY_PANEL_LABELS = "panel_labels"
    private const val KEY_CLOSE_PANEL_AFTER_TAP = "close_panel_after_tap"
    private const val KEY_FLOAT_BALL_ICON_URI = "float_ball_icon_uri"
    private const val KEY_FLOAT_BALL_ICON_MODE = "float_ball_icon_mode"
    private const val KEY_FLOAT_BALL_STATIC_URIS = "float_ball_static_uris"
    private const val KEY_FLOAT_BALL_GIF_URI = "float_ball_gif_uri"
    private const val KEY_FLOAT_BALL_GIF_PLAY = "float_ball_gif_play"
    private const val KEY_FLOAT_BALL_GIF_PAUSE_IDLE = "float_ball_gif_pause_idle"
    private const val KEY_FLOAT_BALL_GIF_IDLE_SECONDS = "float_ball_gif_idle_seconds"
    private const val KEY_FLOAT_BALL_SLIDESHOW = "float_ball_slideshow"
    private const val KEY_FLOAT_BALL_SLIDESHOW_INTERVAL = "float_ball_slideshow_interval"
    private const val KEY_FLOAT_BALL_SLIDESHOW_PAUSE_SCREEN_OFF = "float_ball_slideshow_pause_screen_off"
    private const val KEY_FLOAT_BALL_CROP_STATIC_SCALE = "float_ball_crop_static_scale"
    private const val KEY_FLOAT_BALL_CROP_STATIC_X = "float_ball_crop_static_x"
    private const val KEY_FLOAT_BALL_CROP_STATIC_Y = "float_ball_crop_static_y"
    private const val KEY_FLOAT_BALL_CROP_STATIC_CORNER = "float_ball_crop_static_corner"
    private const val KEY_FLOAT_BALL_CROP_GIF_SCALE = "float_ball_crop_gif_scale"
    private const val KEY_FLOAT_BALL_CROP_GIF_X = "float_ball_crop_gif_x"
    private const val KEY_FLOAT_BALL_CROP_GIF_Y = "float_ball_crop_gif_y"
    private const val KEY_FLOAT_BALL_CROP_GIF_CORNER = "float_ball_crop_gif_corner"
    private const val KEY_FLOAT_BALL_OPACITY = "float_ball_opacity"
    private const val KEY_FLOAT_BALL_CORNER = "float_ball_corner"
    private const val KEY_FLOAT_BALL_EDGE_HIDE_ENABLED = "float_ball_edge_hide_enabled"
    private const val KEY_FLOAT_BALL_EDGE_HIDE_PORTRAIT = "float_ball_edge_hide_portrait"
    private const val KEY_FLOAT_BALL_EDGE_HIDE_LANDSCAPE = "float_ball_edge_hide_landscape"
    private const val KEY_FLOAT_BALL_EDGE_VALUE_PORTRAIT = "float_ball_edge_value_portrait"
    private const val KEY_FLOAT_BALL_EDGE_VALUE_LANDSCAPE = "float_ball_edge_value_landscape"
    private const val KEY_FLOAT_BALL_AUTO_MIRROR = "float_ball_auto_mirror"
    private const val KEY_FLOAT_BALL_EDIT_ORIENTATION = "float_ball_edit_orientation"
    private const val KEY_FLOAT_BALL_FIXED_SIDE = "float_ball_fixed_side"
    private const val KEY_FLOAT_BALL_PORTRAIT_RIGHT_OFFSET = "float_ball_portrait_right_offset"
    private const val KEY_FLOAT_BALL_PORTRAIT_LEFT_OFFSET = "float_ball_portrait_left_offset"
    private const val KEY_FLOAT_BALL_LANDSCAPE_RIGHT_OFFSET = "float_ball_landscape_right_offset"
    private const val KEY_FLOAT_BALL_LANDSCAPE_LEFT_OFFSET = "float_ball_landscape_left_offset"
    private const val KEY_DIM_WHEN_IDLE = "dim_when_idle"
    private const val KEY_IDLE_OPACITY = "idle_opacity"
    private const val KEY_IDLE_DELAY_SECONDS = "idle_delay_seconds"
    private const val KEY_FREEZE_ANIMATION_WHEN_IDLE = "freeze_animation_when_idle"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
    private const val KEY_RECORDING_CONTROL_LABELS = "recording_control_labels"
    private const val KEY_RECORDING_PHONE_SOURCE = "recording_phone_source"
    private const val KEY_RECORDING_EXTERNAL_SOURCE = "recording_external_source"
    private const val KEY_ASSISTANT_REPLACEMENT_ENABLED = "assistant_replacement_enabled"
    private const val KEY_ASSISTANT_REPLACEMENT_ACTION = "assistant_replacement_action"
    private const val KEY_FLASHLIGHT_ENABLED = "flashlight_enabled"
    private const val KEY_PENDING_OVERLAY_ENABLE = "pending_overlay_enable"

    private fun store(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    const val BLUR_SCOPE_SCREEN = "screen"
    const val BLUR_SCOPE_PANEL = "panel"

    val panelPalette = intArrayOf(
        0xFF152438.toInt(), 0xFF182A3A.toInt(), 0xFF1E3042.toInt(),
        0xFF202735.toInt(), 0xFF172E36.toInt(), 0xFF292238.toInt(),
        0xFF26313B.toInt()
    )

    fun floatBallEnabled(context: Context) =
        store(context).getBoolean(KEY_FLOAT_BALL_ENABLED, false)

    fun setFloatBallEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLOAT_BALL_ENABLED, enabled).apply()
    }

    fun startOnBoot(context: Context) =
        store(context).getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    const val EXPANSION_SHORTCUT_SLOTS = 8

    fun expansionShortcuts(context: Context): List<ExpansionShortcut?> {
        val slots = EXPANSION_SHORTCUT_SLOTS
        val saved = store(context).getString(KEY_EXPANSION_SHORTCUTS, null) ?: return List(slots) { null }
        return try {
            val array = JSONArray(saved)
            List(slots) { index ->
                if (index >= array.length() || array.isNull(index)) {
                    null
                } else {
                    array.getJSONObject(index).let { item ->
                        ExpansionShortcut(
                            item.getString("type"),
                            item.getString("value"),
                            item.getString("label")
                        )
                    }
                }
            }
        } catch (error: JSONException) {
            Log.e("PanelsAssistive", "Unable to read expansion shortcuts", error)
            List(slots) { null }
        }
    }

    fun setExpansionShortcut(context: Context, index: Int, shortcut: ExpansionShortcut?) {
        require(index in 0 until EXPANSION_SHORTCUT_SLOTS)
        val items = expansionShortcuts(context).toMutableList()
        items[index] = shortcut
        val array = JSONArray()
        items.forEach { item ->
            array.put(item?.let {
                JSONObject()
                    .put("type", it.type)
                    .put("value", it.value)
                    .put("label", it.label)
            })
        }
        store(context).edit().putString(KEY_EXPANSION_SHORTCUTS, array.toString()).apply()
    }

    /** Ordered keys shown as vertical bars in the expansion panel; media sits last by default. */
    val EXPANSION_SLIDER_KEYS = listOf("brightness", "ring", "notification", "alarm", "media")
    private val DEFAULT_EXPANSION_SLIDERS = EXPANSION_SLIDER_KEYS

    fun expansionSliders(context: Context): List<String> {
        val saved = store(context).getString(KEY_EXPANSION_SLIDERS, null) ?: return DEFAULT_EXPANSION_SLIDERS
        val keys = saved.split(',').filter { it in EXPANSION_SLIDER_KEYS }.distinct()
        return keys.ifEmpty { DEFAULT_EXPANSION_SLIDERS }
    }

    fun setExpansionSliders(context: Context, keys: List<String>) {
        val valid = keys.filter { it in EXPANSION_SLIDER_KEYS }.distinct()
        store(context).edit()
            .putString(KEY_EXPANSION_SLIDERS, valid.ifEmpty { DEFAULT_EXPANSION_SLIDERS }.joinToString(","))
            .apply()
    }

    fun exportSettings(context: Context): String {
        val values = JSONObject()
        store(context).all.forEach { (key, value) ->
            val encoded = JSONObject()
            when (value) {
                is Boolean -> encoded.put("type", "boolean").put("value", value)
                is Int -> encoded.put("type", "int").put("value", value)
                is Long -> encoded.put("type", "long").put("value", value)
                is Float -> encoded.put("type", "float").put("value", value.toDouble())
                is String -> encoded.put("type", "string").put("value", value)
                is Set<*> -> {
                    // Skip exotic sets instead of failing the whole backup.
                    if (!value.all { it is String }) return@forEach
                    encoded.put("type", "string_set").put("value", JSONArray(value))
                }
                else -> return@forEach
            }
            values.put(key, encoded)
        }
        val result = JSONObject()
            .put("format", "panels-assistive-settings")
            .put("version", 1)
            .put("preferences", values)
        runCatching {
            floatBallIconUri(context)?.let { savedUri ->
                val uri = android.net.Uri.parse(savedUri)
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FLOAT_BALL_ICON_BYTES) {
                            throw java.io.IOException("Float Ball icon exceeds the 16 MB export limit.")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } ?: throw java.io.IOException("Unable to read the selected Float Ball icon.")
                result.put(
                    "floatBallIcon",
                    JSONObject()
                        .put("mimeType", context.contentResolver.getType(uri) ?: "image/*")
                        .put(
                            "base64",
                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        )
                )
            }
        }
        return result.toString(2)
    }

    fun importSettings(context: Context, serialized: String) {
        val root = JSONObject(serialized)
        require(root.optString("format") == "panels-assistive-settings" && root.optInt("version") == 1) {
            "Unsupported settings file."
        }
        val values = root.optJSONObject("preferences") ?: throw JSONException("Missing preferences.")
        val iconMimeType = root.optJSONObject("floatBallIcon")?.optString("mimeType", "image/*")
        val iconBytes = runCatching {
            root.optJSONObject("floatBallIcon")?.let { icon ->
                val encoded = icon.getString("base64")
                if (encoded.isBlank()) throw JSONException("Empty Float Ball icon.")
                if (encoded.length > ((MAX_FLOAT_BALL_ICON_BYTES + 2) / 3) * 4) {
                    throw java.io.IOException("Float Ball icon exceeds the 16 MB import limit.")
                }
                android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).also {
                    if (it.isEmpty() || it.size > MAX_FLOAT_BALL_ICON_BYTES) {
                        throw java.io.IOException("Float Ball icon is empty or exceeds the 16 MB import limit.")
                    }
                }
            }
        }.getOrNull()
        val editor = store(context).edit().clear()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val encoded = values.getJSONObject(key)
            val value = encoded.get("value")
            when (encoded.getString("type")) {
                "boolean" -> editor.putBoolean(key, encoded.getBoolean("value"))
                "int" -> editor.putInt(key, encoded.getInt("value"))
                "long" -> editor.putLong(key, encoded.getLong("value"))
                "float" -> editor.putFloat(key, encoded.getDouble("value").toFloat())
                "string" -> editor.putString(key, encoded.getString("value"))
                "string_set" -> {
                    val array = value as? JSONArray
                    if (array != null) {
                        val items = (0 until array.length()).map { array.getString(it) }.toSet()
                        editor.putStringSet(key, items)
                    }
                }
            }
        }
        iconBytes?.let { bytes ->
            val name = "float-ball-icon-${UUID.randomUUID()}"
            val file = java.io.File(context.filesDir, name)
            val temporaryFile = java.io.File(context.filesDir, "$name.tmp")
            try {
                temporaryFile.outputStream().use { it.write(bytes) }
                if (!temporaryFile.renameTo(file)) {
                    throw java.io.IOException("Unable to store the imported Float Ball icon.")
                }
            } finally {
                if (temporaryFile.exists()) temporaryFile.delete()
            }
            val importedUri = android.net.Uri.fromFile(file).toString()
            editor.putString(KEY_FLOAT_BALL_ICON_URI, importedUri)
            if (iconMimeType.equals("image/gif", ignoreCase = true)) {
                editor.putString(KEY_FLOAT_BALL_ICON_MODE, "gif")
                editor.putString(KEY_FLOAT_BALL_GIF_URI, importedUri)
                editor.putBoolean(KEY_FLOAT_BALL_GIF_PLAY, false)
            } else {
                editor.putString(KEY_FLOAT_BALL_ICON_MODE, "static")
                editor.putString(
                    KEY_FLOAT_BALL_STATIC_URIS,
                    JSONArray().put(importedUri).toString()
                )
                editor.putBoolean(KEY_FLOAT_BALL_SLIDESHOW, false)
            }
        }
        editor.apply()
    }

    fun floatBallSize(context: Context) =
        store(context).getInt(KEY_FLOAT_BALL_SIZE, 52).coerceIn(20, 80)

    fun setFloatBallSize(context: Context, size: Int) {
        store(context).edit().putInt(KEY_FLOAT_BALL_SIZE, size.coerceIn(20, 80)).apply()
    }

    fun position(context: Context): Pair<Float, Float>? {
        val prefs = store(context)
        if (!prefs.contains(KEY_FLOAT_BALL_X) || !prefs.contains(KEY_FLOAT_BALL_Y)) return null
        return Pair(prefs.getFloat(KEY_FLOAT_BALL_X, 1f), prefs.getFloat(KEY_FLOAT_BALL_Y, 0.5f))
    }

    fun setPosition(context: Context, x: Float, y: Float) {
        store(context).edit()
            .putFloat(KEY_FLOAT_BALL_X, x.coerceIn(0f, 1f))
            .putFloat(KEY_FLOAT_BALL_Y, y.coerceIn(0f, 1f))
            .apply()
    }

    fun snapToEdge(context: Context) =
        store(context).getBoolean(KEY_FLOAT_BALL_SNAP, true)

    fun setSnapToEdge(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLOAT_BALL_SNAP, enabled).apply()
    }

    fun action(context: Context, gesture: Gesture, default: String): String =
        store(context).getString(gesture.preferenceKey, default) ?: default

    fun setAction(context: Context, gesture: Gesture, action: String) {
        store(context).edit().putString(gesture.preferenceKey, action).apply()
    }

    private fun migratePanelEdgePadding(context: Context) {
        val prefs = store(context)
        if (prefs.getBoolean(KEY_PANEL_EDGE_PADDING_MIGRATED, false)) return

        val editor = prefs.edit()
        listOf(KEY_PORTRAIT_PANELS, KEY_LANDSCAPE_PANELS).forEach { key ->
            val serialized = prefs.getString(key, null) ?: return@forEach
            try {
                val pages = JSONArray(serialized)
                var changed = false
                for (index in 0 until pages.length()) {
                    val page = pages.optJSONObject(index) ?: continue
                    if (page.optInt("areaPaddingDp", 10) == 10) {
                        page.put("areaPaddingDp", 0)
                        changed = true
                    }
                }
                if (changed) editor.putString(key, pages.toString())
            } catch (error: JSONException) {
                Log.e(TAG, "Unable to migrate panel edge padding for $key", error)
            }
        }
        editor.putBoolean(KEY_PANEL_EDGE_PADDING_MIGRATED, true).apply()
    }

    /**
     * Before per-panel backgrounds every panel shared the popup surface colour.
     * Seeding each saved page with that current look keeps existing panels
     * unchanged; from then on each page owns its background.
     */
    private fun migratePanelBackgrounds(context: Context) {
        val prefs = store(context)
        if (prefs.getBoolean(KEY_PANEL_BACKGROUNDS_MIGRATED, false)) return
        val color = popupPanelColor(context)
        val opacity = popupPanelOpacity(context)
        val editor = prefs.edit()
        listOf(KEY_PORTRAIT_PANELS, KEY_LANDSCAPE_PANELS).forEach { key ->
            val serialized = prefs.getString(key, null) ?: return@forEach
            try {
                val pages = JSONArray(serialized)
                for (index in 0 until pages.length()) {
                    val page = pages.optJSONObject(index) ?: continue
                    page.put("backgroundColor", color)
                    page.put("backgroundOpacity", opacity)
                }
                editor.putString(key, pages.toString())
            } catch (error: JSONException) {
                Log.e(TAG, "Unable to migrate panel backgrounds for $key", error)
            }
        }
        editor.putBoolean(KEY_PANEL_BACKGROUNDS_MIGRATED, true).apply()
    }

    fun maxGridColumns(landscape: Boolean): Int = if (landscape) 20 else 10

    fun maxGridRows(landscape: Boolean): Int = if (landscape) 10 else 20

    fun panelPages(context: Context, landscape: Boolean = false): List<WidgetPanelPage> {
        migratePanelEdgePadding(context)
        migratePanelBackgrounds(context)
        val key = if (landscape) KEY_LANDSCAPE_PANELS else KEY_PORTRAIT_PANELS
        val serialized = store(context).getString(key, null) ?: return defaultPanel(context, landscape)
        return try {
            val array = JSONArray(serialized)
            val pages = buildList {
                for (index in 0 until array.length()) {
                    val jsonPage = array.getJSONObject(index)
                    val itemsJson = jsonPage.optJSONArray("items") ?: JSONArray()
                    val items = buildList {
                        for (itemIndex in 0 until itemsJson.length()) {
                            val jsonItem = itemsJson.getJSONObject(itemIndex)
                            add(
                                PanelItem(
                                    jsonItem.optString("type"),
                                    jsonItem.optString("value"),
                                    jsonItem.optString("label"),
                                    jsonItem.optInt("position", itemIndex).coerceAtLeast(-1),
                                    jsonItem.optDouble("spanX", 1.0).toFloat().coerceIn(1f, 20f),
                                    jsonItem.optDouble("spanY", 1.0).toFloat().coerceIn(1f, 10f),
                                    jsonItem.optString(
                                        "id",
                                        "${jsonItem.optString("type")}:${jsonItem.optString("value")}:$itemIndex"
                                    )
                                )
                            )
                        }
                    }
                    val cellSize = jsonPage.optInt(
                        "cellWidthDp",
                        jsonPage.optInt("rowHeightDp", 30)
                    ).coerceIn(20, 120)
                    add(
                        WidgetPanelPage(
                            jsonPage.optString("id", UUID.randomUUID().toString()),
                            jsonPage.optString("name", context.getString(R.string.default_panel_name)),
                            items,
                            jsonPage.optInt("columns", 10)
                                .coerceIn(1, maxGridColumns(landscape)),
                            jsonPage.optInt("rows", 10)
                                .coerceIn(1, maxGridRows(landscape)),
                            cellSize,
                            cellSize,
                            jsonPage.optInt("areaColumns", jsonPage.optInt("columns", 10))
                                .coerceIn(1, 30),
                            jsonPage.optInt("areaRows", jsonPage.optInt("rows", 10))
                                .coerceIn(1, 30),
                            jsonPage.optInt("areaPaddingDp", 0).coerceIn(0, 100),
                            jsonPage.optInt("backgroundOpacity", 78).coerceIn(0, 100),
                            jsonPage.optInt("backgroundColor", 0xFF152438.toInt()),
                            jsonPage.optString("kind", PanelKind.WIDGET)
                        )
                    )
                }
            }
            pages.ifEmpty { defaultPanel(context, landscape) }
        } catch (error: JSONException) {
            Log.e(TAG, "Saved widget panel data is invalid", error)
            defaultPanel(context, landscape)
        }
    }

    fun savePanelPages(
        context: Context,
        landscape: Boolean,
        pages: List<WidgetPanelPage>,
        selectedId: String? = null
    ) {
        val safePages = pages.ifEmpty { defaultPanel(context, landscape) }
        val json = JSONArray()
        safePages.forEach { page ->
            val items = JSONArray()
            page.items.forEach { item ->
                items.put(
                    JSONObject()
                        .put("type", item.type)
                        .put("value", item.value)
                        .put("label", item.label)
                        .put("position", item.position)
                        .put("spanX", item.spanX.coerceIn(1f, 20f).toDouble())
                        .put("spanY", item.spanY.coerceIn(1f, 10f).toDouble())
                        .put("id", item.id)
                )
            }
            json.put(
                JSONObject()
                    .put("id", page.id)
                    .put("name", page.name)
                    .put("columns", page.columns.coerceIn(1, maxGridColumns(landscape)))
                    .put("rows", page.rows.coerceIn(1, maxGridRows(landscape)))
                    .put("cellWidthDp", page.cellWidthDp.coerceIn(20, 120))
                    .put("rowHeightDp", page.cellWidthDp.coerceIn(20, 120))
                    .put("areaColumns", page.areaColumns.coerceIn(1, 30))
                    .put("areaRows", page.areaRows.coerceIn(1, 30))
                    .put("areaPaddingDp", page.areaPaddingDp.coerceIn(0, 100))
                    .put("backgroundOpacity", page.backgroundOpacity.coerceIn(0, 100))
                    .put("backgroundColor", page.backgroundColor)
                    .put("kind", if (page.kind == PanelKind.APP) PanelKind.APP else PanelKind.WIDGET)
                    .put("items", items)
            )
        }
        val currentKey = if (landscape) KEY_CURRENT_LANDSCAPE_PANEL else KEY_CURRENT_PORTRAIT_PANEL
        val prefs = store(context)
        val currentId = selectedId
            ?: prefs.getString(currentKey, null)?.takeIf { id -> safePages.any { it.id == id } }
            ?: safePages.first().id
        prefs.edit()
            .putString(if (landscape) KEY_LANDSCAPE_PANELS else KEY_PORTRAIT_PANELS, json.toString())
            .putString(currentKey, currentId)
            .apply()
    }

    fun currentPanelId(context: Context, landscape: Boolean = false): String {
        val key = if (landscape) KEY_CURRENT_LANDSCAPE_PANEL else KEY_CURRENT_PORTRAIT_PANEL
        val pages = panelPages(context, landscape)
        return store(context).getString(key, null)?.takeIf { id -> pages.any { it.id == id } }
            ?: pages.first().id
    }

    fun setCurrentPanel(context: Context, landscape: Boolean, id: String) {
        if (panelPages(context, landscape).any { it.id == id }) {
            val key = if (landscape) KEY_CURRENT_LANDSCAPE_PANEL else KEY_CURRENT_PORTRAIT_PANEL
            store(context).edit().putString(key, id).apply()
        }
    }

    fun addPanel(
        context: Context,
        landscape: Boolean,
        name: String,
        kind: String = PanelKind.WIDGET
    ): WidgetPanelPage {
        val pages = panelPages(context, landscape)
        // Every new panel starts with its own background: walk the palette so a
        // fresh panel never looks identical to the one created before it.
        val background = panelPalette[pages.size % panelPalette.size]
        val opacity = popupPanelOpacity(context)
        val page = if (kind == PanelKind.APP) {
            WidgetPanelPage(
                id = UUID.randomUUID().toString(),
                name = name,
                kind = PanelKind.APP,
                columns = 5,
                rows = 5,
                cellWidthDp = 70,
                rowHeightDp = 70,
                areaColumns = 5,
                areaRows = 5,
                areaPaddingDp = 0,
                backgroundOpacity = opacity,
                backgroundColor = background
            )
        } else {
            WidgetPanelPage(
                id = UUID.randomUUID().toString(),
                name = name,
                kind = PanelKind.WIDGET,
                backgroundOpacity = opacity,
                backgroundColor = background
            )
        }
        savePanelPages(context, landscape, pages + page, page.id)
        return page
    }

    fun updatePanel(context: Context, landscape: Boolean, page: WidgetPanelPage) {
        val pages = panelPages(context, landscape).map { if (it.id == page.id) page else it }
        savePanelPages(context, landscape, pages)
    }

    fun removePanel(context: Context, landscape: Boolean, id: String) {
        val pages = panelPages(context, landscape).filterNot { it.id == id }
        savePanelPages(context, landscape, pages)
    }

    fun movePanel(context: Context, landscape: Boolean, fromIndex: Int, toIndex: Int) {
        val pages = panelPages(context, landscape).toMutableList()
        if (fromIndex !in pages.indices || toIndex !in pages.indices || fromIndex == toIndex) return
        val page = pages.removeAt(fromIndex)
        pages.add(toIndex, page)
        savePanelPages(context, landscape, pages)
    }

    fun addPanelItem(
        context: Context,
        landscape: Boolean,
        panelId: String,
        item: PanelItem,
        allowOverlap: Boolean = false
    ) {
        val pages = panelPages(context, landscape)
        val targetId = pages.firstOrNull { it.id == panelId }?.id ?: pages.first().id
        val target = pages.first { it.id == targetId }
        val columns = target.columns.coerceIn(1, maxGridColumns(landscape))
        val maxRows = maxGridRows(landscape)
        val occupied = target.items.flatMap { existing ->
            val start = existing.position.coerceAtLeast(0)
            val row = start / columns
            val column = start % columns
            (0 until existing.occupiedRows.coerceAtMost(maxRows)).flatMap { dy ->
                (0 until existing.occupiedColumns.coerceAtMost(columns - column)).map { dx ->
                    (row + dy) * columns + column + dx
                }
            }
        }.toSet()
        val startPosition = item.position.coerceAtLeast(0)
        fun fits(position: Int): Boolean {
            val column = position % columns
            val spanColumns = item.occupiedColumns.coerceAtMost(columns - column)
            val row = position / columns
            return (0 until item.occupiedRows.coerceAtMost(maxRows)).all { dy ->
                (0 until spanColumns).all { dx -> (row + dy) * columns + column + dx !in occupied }
            }
        }
        val position = if (allowOverlap) {
            startPosition
        } else {
            generateSequence(startPosition) { it + 1 }
                .take(columns * target.rows.coerceIn(1, maxRows) * 10)
                .firstOrNull(::fits) ?: startPosition
        }
        val column = position % columns
        val spanX = item.spanX.coerceIn(1f, (columns - column).toFloat())
        savePanelPages(
            context,
            landscape,
            pages.map { page ->
                if (page.id == targetId) {
                    page.copy(items = page.items + item.copy(
                        position = position,
                        spanX = spanX,
                        spanY = item.spanY.coerceIn(1f, maxRows.toFloat())
                    ))
                } else page
            },
            targetId
        )
    }

    fun removePanelItem(context: Context, landscape: Boolean, panelId: String, itemId: String) {
        val pages = panelPages(context, landscape).map { page ->
            if (page.id == panelId) page.copy(items = page.items.filterNot { it.id == itemId }) else page
        }
        savePanelPages(context, landscape, pages)
    }

    fun widgetIds(context: Context, landscape: Boolean = false): Set<String> =
        panelPages(context, landscape).flatMap { page ->
            page.items.filter { it.type == PanelItemType.WIDGET }.map { it.value }
        }.toSet()

    fun removeWidget(context: Context, appWidgetId: Int) {
        listOf(false, true).forEach { landscape ->
            val pages = panelPages(context, landscape).map { page ->
                page.copy(items = page.items.filterNot {
                    it.type == PanelItemType.WIDGET && it.value == appWidgetId.toString()
                })
            }
            savePanelPages(context, landscape, pages)
        }
    }

    fun landscapeWidgetLayoutEnabled(context: Context) =
        store(context).getBoolean(KEY_LANDSCAPE_LAYOUT_ENABLED, false)

    fun setLandscapeWidgetLayoutEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_LANDSCAPE_LAYOUT_ENABLED, enabled).apply()
    }

    fun widgetPopupEnabled(context: Context) =
        store(context).getBoolean(KEY_WIDGET_POPUP_ENABLED, true)

    fun setWidgetPopupEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_WIDGET_POPUP_ENABLED, enabled).apply()
    }

    fun popupBlurEnabled(context: Context) =
        store(context).getBoolean(KEY_POPUP_BLUR, false)

    fun setPopupBlurEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_POPUP_BLUR, enabled).apply()
    }

    fun popupBlurRadius(context: Context) =
        store(context).getInt(KEY_POPUP_BLUR_RADIUS, 24).coerceIn(0, 80)

    fun setPopupBlurRadius(context: Context, radius: Int) {
        store(context).edit().putInt(KEY_POPUP_BLUR_RADIUS, radius.coerceIn(0, 80)).apply()
    }

    fun popupBlurScope(context: Context) =
        store(context).getString(KEY_POPUP_BLUR_SCOPE, BLUR_SCOPE_SCREEN) ?: BLUR_SCOPE_SCREEN

    fun setPopupBlurScope(context: Context, scope: String) {
        require(scope == BLUR_SCOPE_SCREEN || scope == BLUR_SCOPE_PANEL)
        store(context).edit().putString(KEY_POPUP_BLUR_SCOPE, scope).apply()
    }

    /** Widget popup has its own blur so it can be tuned independently of the shared panels. */
    fun widgetPanelBlurEnabled(context: Context) =
        store(context).getBoolean(KEY_WIDGET_PANEL_BLUR, false)

    fun setWidgetPanelBlurEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_WIDGET_PANEL_BLUR, enabled).apply()
    }

    fun widgetPanelBlurRadius(context: Context) =
        store(context).getInt(KEY_WIDGET_PANEL_BLUR_RADIUS, 24).coerceIn(0, 80)

    fun setWidgetPanelBlurRadius(context: Context, radius: Int) {
        store(context).edit().putInt(KEY_WIDGET_PANEL_BLUR_RADIUS, radius.coerceIn(0, 80)).apply()
    }

    fun widgetPanelBlurScope(context: Context) =
        store(context).getString(KEY_WIDGET_PANEL_BLUR_SCOPE, BLUR_SCOPE_PANEL) ?: BLUR_SCOPE_PANEL

    fun setWidgetPanelBlurScope(context: Context, scope: String) {
        require(scope == BLUR_SCOPE_SCREEN || scope == BLUR_SCOPE_PANEL)
        store(context).edit().putString(KEY_WIDGET_PANEL_BLUR_SCOPE, scope).apply()
    }

    // Quick settings tile: tap runs the first action, long-press (via the
    // QS tile preferences activity) runs the second one.
    fun quickTilePrimaryAction(context: Context) =
        store(context).getString(KEY_QUICK_TILE_PRIMARY, ActionDispatcher.WIDGET_POPUP)
            ?: ActionDispatcher.WIDGET_POPUP

    fun setQuickTilePrimaryAction(context: Context, action: String) {
        store(context).edit().putString(KEY_QUICK_TILE_PRIMARY, action).apply()
    }

    fun quickTileSecondaryAction(context: Context) =
        store(context).getString(KEY_QUICK_TILE_SECONDARY, ActionDispatcher.FLASHLIGHT)
            ?: ActionDispatcher.FLASHLIGHT

    fun setQuickTileSecondaryAction(context: Context, action: String) {
        store(context).edit().putString(KEY_QUICK_TILE_SECONDARY, action).apply()
    }

    fun themeKey(context: Context) =
        store(context).getString(KEY_THEME, AppThemes.DEFAULT_KEY) ?: AppThemes.DEFAULT_KEY

    fun setThemeKey(context: Context, key: String) {
        store(context).edit().putString(KEY_THEME, key).apply()
    }

    fun themeAccent(context: Context): Int =
        store(context).getInt(KEY_THEME_ACCENT, 0xFF7CBEFF.toInt())

    fun setThemeAccent(context: Context, color: Int) {
        store(context).edit()
            .putString(KEY_THEME, AppThemes.CUSTOM_KEY)
            .putInt(KEY_THEME_ACCENT, color)
            .apply()
    }

    fun sharedPanelColor(context: Context) = store(context).getInt(KEY_SHARED_PANEL_COLOR, android.graphics.Color.rgb(24, 41, 61))
    fun setSharedPanelColor(context: Context, color: Int) { store(context).edit().putInt(KEY_SHARED_PANEL_COLOR, color).apply() }
    fun sharedPanelOpacity(context: Context) = store(context).getInt(KEY_SHARED_PANEL_OPACITY, 82).coerceIn(0, 100)
    fun setSharedPanelOpacity(context: Context, value: Int) { store(context).edit().putInt(KEY_SHARED_PANEL_OPACITY, value.coerceIn(0, 100)).apply() }
    fun sharedPanelCorner(context: Context) = store(context).getInt(KEY_SHARED_PANEL_CORNER, 26).coerceIn(0, 48)
    fun setSharedPanelCorner(context: Context, value: Int) { store(context).edit().putInt(KEY_SHARED_PANEL_CORNER, value.coerceIn(0, 48)).apply() }

    // Widget popup keeps its own surface so it can differ from the shared panels;
    // until customized it inherits the shared values.
    fun popupPanelColor(context: Context) =
        store(context).getInt(KEY_POPUP_PANEL_COLOR, sharedPanelColor(context))
    fun setPopupPanelColor(context: Context, color: Int) {
        store(context).edit().putInt(KEY_POPUP_PANEL_COLOR, color).apply()
    }
    fun popupPanelOpacity(context: Context) =
        store(context).getInt(KEY_POPUP_PANEL_OPACITY, sharedPanelOpacity(context)).coerceIn(0, 100)
    fun setPopupPanelOpacity(context: Context, value: Int) {
        store(context).edit().putInt(KEY_POPUP_PANEL_OPACITY, value.coerceIn(0, 100)).apply()
    }
    fun popupPanelCorner(context: Context) =
        store(context).getInt(KEY_POPUP_PANEL_CORNER, sharedPanelCorner(context)).coerceIn(0, 48)
    fun setPopupPanelCorner(context: Context, value: Int) {
        store(context).edit().putInt(KEY_POPUP_PANEL_CORNER, value.coerceIn(0, 48)).apply()
    }

    // One shared switch for the light 1dp rim drawn around every overlay panel.
    fun panelBorderEnabled(context: Context) = store(context).getBoolean(KEY_PANEL_BORDER, true)
    fun setPanelBorderEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_PANEL_BORDER, enabled).apply()
    }

    fun tabTransition(context: Context): String {
        val value = store(context).getString(KEY_TAB_TRANSITION, "slide") ?: "slide"
        return if (value in setOf("none", "fade", "slide", "flip")) value else "slide"
    }
    fun setTabTransition(context: Context, value: String) {
        require(value in setOf("none", "fade", "slide", "flip"))
        store(context).edit().putString(KEY_TAB_TRANSITION, value).apply()
    }

    fun showPanelLabels(context: Context) =
        store(context).getBoolean(KEY_PANEL_LABELS, false)

    fun setShowPanelLabels(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_PANEL_LABELS, enabled).apply()
    }

    fun closePanelAfterTap(context: Context) =
        store(context).getBoolean(KEY_CLOSE_PANEL_AFTER_TAP, true)

    fun setClosePanelAfterTap(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_CLOSE_PANEL_AFTER_TAP, enabled).apply()
    }

    /** Free mode lets panel items overlap each other; manual resize/move stays unchanged. */
    fun panelFreeMode(context: Context) =
        store(context).getBoolean(KEY_PANEL_FREE_MODE, false)

    fun setPanelFreeMode(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_PANEL_FREE_MODE, enabled).apply()
    }

    /** Launch panel app items in a floating (freeform) window instead of full screen. */
    fun freeformApps(context: Context) =
        store(context).getBoolean(KEY_FREEFORM_APPS, false)

    fun setFreeformApps(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FREEFORM_APPS, enabled).apply()
    }

    /** One orientation's freeform geometry; every value is a percent of the screen. */
    data class FreeformLayout(
        val width: Int,
        val height: Int,
        val left: Int,
        val top: Int
    )

    /** 0 = always fullscreen … 3 = freeform, long-press fullscreen (see FloatingApps.Policy). */
    fun freeformPolicy(context: Context) =
        store(context).getInt(KEY_FREEFORM_POLICY, 1)

    fun setFreeformPolicy(context: Context, id: Int) {
        store(context).edit().putInt(KEY_FREEFORM_POLICY, id).apply()
    }

    /** Chosen OEM windowing mode; -1 auto-detects from the manufacturer. */
    fun freeformMode(context: Context) =
        store(context).getInt(KEY_FREEFORM_MODE, -1)

    fun setFreeformMode(context: Context, id: Int) {
        store(context).edit().putInt(KEY_FREEFORM_MODE, id).apply()
    }

    fun freeformFollowTrigger(context: Context) =
        store(context).getBoolean(KEY_FREEFORM_FOLLOW, false)

    fun setFreeformFollowTrigger(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FREEFORM_FOLLOW, enabled).apply()
    }

    fun freeformLayout(context: Context, landscape: Boolean): FreeformLayout {
        val fallback = if (landscape) {
            FreeformLayout(width = 68, height = 40, left = 16, top = 25)
        } else {
            FreeformLayout(width = 80, height = 55, left = 10, top = 20)
        }
        val raw = store(context).getString(
            if (landscape) KEY_FREEFORM_LANDSCAPE else KEY_FREEFORM_PORTRAIT,
            null
        ) ?: return fallback
        val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return fallback
        return FreeformLayout(
            width = parts[0].coerceIn(35, 95),
            height = parts[1].coerceIn(35, 90),
            left = parts[2].coerceIn(0, 65),
            top = parts[3].coerceIn(0, 65)
        )
    }

    fun setFreeformLayout(context: Context, landscape: Boolean, layout: FreeformLayout) {
        store(context).edit().putString(
            if (landscape) KEY_FREEFORM_LANDSCAPE else KEY_FREEFORM_PORTRAIT,
            "${layout.width},${layout.height},${layout.left},${layout.top}"
        ).apply()
    }

    fun iconPackEnabled(context: Context) =
        store(context).getBoolean(KEY_ICON_PACK_ENABLED, false)

    fun setIconPackEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_ICON_PACK_ENABLED, enabled).apply()
    }

    fun iconPackPackage(context: Context): String =
        store(context).getString(KEY_ICON_PACK_PACKAGE, null).orEmpty()

    fun setIconPackPackage(context: Context, packageName: String) {
        store(context).edit().putString(KEY_ICON_PACK_PACKAGE, packageName).apply()
    }

    /** Draw iconback/iconmask/iconupon shapes for apps the pack has no icon for. */
    fun iconMaskEnabled(context: Context) =
        store(context).getBoolean(KEY_ICON_MASK_ENABLED, true)

    fun setIconMaskEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_ICON_MASK_ENABLED, enabled).apply()
    }

    /**
     * Presets for the widget accent. Still saturated enough to read over the
     * frosted white card, but the first (default) swatch is a lighter, softer
     * blue that matches the light tone of the colour picker plane.
     */
    val WIDGET_ACCENT_PALETTE = intArrayOf(
        0xFF4E96E8.toInt(),
        0xFF00A884.toInt(),
        0xFFFF5A36.toInt(),
        0xFFE0266B.toInt(),
        0xFF7B4DFF.toInt(),
        0xFFF0A020.toInt()
    )

    /**
     * Accent used by the home-screen widgets. Widgets used to inherit the
     * theme accent, which made a pale theme leave the Play disc washed out;
     * they now have their own saturated palette, independent of "Giao diện".
     */
    fun widgetAccent(context: Context): Int =
        store(context).getInt(KEY_WIDGET_ACCENT, WIDGET_ACCENT_PALETTE[0])

    fun setWidgetAccent(context: Context, color: Int) {
        store(context).edit().putInt(KEY_WIDGET_ACCENT, color).apply()
    }

    /** App opened when a widget card is tapped; empty keeps the Panels activity. */
    fun widgetShortcut(context: Context): String =
        store(context).getString(KEY_WIDGET_SHORTCUT, null).orEmpty()

    fun setWidgetShortcut(context: Context, value: String?) {
        store(context).edit().putString(KEY_WIDGET_SHORTCUT, value.orEmpty()).apply()
    }

    /**
     * Ask before a widget is pinned. Turning this off ("Không hiện lần sau")
     * makes later pins create and allow the widget immediately; Advanced
     * settings can switch the question back on.
     */
    fun widgetConfirmEnabled(context: Context) =
        store(context).getBoolean(KEY_WIDGET_CONFIRM, true)

    fun setWidgetConfirmEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_WIDGET_CONFIRM, enabled).apply()
    }

    /** Music card: true opens the playing app, false opens the assigned shortcut. */
    fun musicWidgetOpensSource(context: Context) =
        store(context).getBoolean(KEY_MUSIC_WIDGET_SOURCE, true)

    fun setMusicWidgetOpensSource(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_MUSIC_WIDGET_SOURCE, enabled).apply()
    }

    /**
     * One music app shared by every music widget, so pinning several widgets
     * still means a single assignment. [WIDGET_NO_APP] keeps the cards
     * informational without launching anything.
     */
    fun musicWidgetPackage(context: Context): String {
        val value = store(context).getString(KEY_MUSIC_WIDGET_PACKAGE, null).orEmpty()
        return if (value == WIDGET_NO_APP) "" else value
    }

    fun musicWidgetNoApp(context: Context): Boolean =
        store(context).getString(KEY_MUSIC_WIDGET_PACKAGE, null) == WIDGET_NO_APP

    fun setMusicWidgetPackage(context: Context, packageName: String?) {
        store(context).edit().putString(KEY_MUSIC_WIDGET_PACKAGE, packageName.orEmpty()).apply()
    }

    private fun iconOverrideStore(context: Context) =
        store(context).getString(KEY_ICON_OVERRIDES, null)?.let {
            try { JSONObject(it) } catch (_: Exception) { JSONObject() }
        } ?: JSONObject()

    fun iconOverride(context: Context, key: String): String? {
        val saved = iconOverrideStore(context).optString(key, "")
        return saved.ifEmpty { null }
    }

    fun setIconOverride(context: Context, key: String, value: String?) {
        val json = iconOverrideStore(context)
        try {
            if (value == null) json.remove(key) else json.put(key, value)
        } catch (_: Exception) {
            return
        }
        store(context).edit().putString(KEY_ICON_OVERRIDES, json.toString()).apply()
    }

    fun floatBallIconUri(context: Context): String? =
        store(context).getString(KEY_FLOAT_BALL_ICON_URI, null)

    fun setFloatBallIconUri(context: Context, uri: String?) {
        store(context).edit().putString(KEY_FLOAT_BALL_ICON_URI, uri).apply()
    }

    enum class FloatBallIconMode { STATIC, GIF }

    fun floatBallIconMode(context: Context): FloatBallIconMode {
        return when (store(context).getString(KEY_FLOAT_BALL_ICON_MODE, null)) {
            "gif" -> FloatBallIconMode.GIF
            else -> FloatBallIconMode.STATIC
        }
    }

    fun setFloatBallIconMode(context: Context, mode: FloatBallIconMode) {
        store(context).edit().putString(
            KEY_FLOAT_BALL_ICON_MODE,
            if (mode == FloatBallIconMode.GIF) "gif" else "static"
        ).apply()
    }

    fun floatBallStaticUris(context: Context): List<String> {
        val saved = store(context).getString(KEY_FLOAT_BALL_STATIC_URIS, null)
        if (!saved.isNullOrBlank()) {
            return try {
                JSONArray(saved).let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optString(index).takeIf { it.isNotBlank() }
                    }
                }
            } catch (_: JSONException) {
                emptyList()
            }
        }
        val legacy = floatBallIconUri(context)
        return legacy?.let { listOf(it) } ?: emptyList()
    }

    fun setFloatBallStaticUris(context: Context, uris: List<String>) {
        val array = JSONArray()
        uris.distinct().take(20).forEach(array::put)
        store(context).edit()
            .putString(KEY_FLOAT_BALL_STATIC_URIS, array.toString())
            .putString(KEY_FLOAT_BALL_ICON_MODE, "static")
            .putString(KEY_FLOAT_BALL_ICON_URI, uris.firstOrNull())
            .apply()
    }

    fun floatBallGifUri(context: Context): String? =
        store(context).getString(KEY_FLOAT_BALL_GIF_URI, null)

    fun setFloatBallGifUri(context: Context, uri: String?) {
        store(context).edit()
            .putString(KEY_FLOAT_BALL_GIF_URI, uri)
            .putString(KEY_FLOAT_BALL_ICON_MODE, if (uri == null) "static" else "gif")
            .apply()
    }

    data class FloatBallCrop(val scale: Float, val x: Float, val y: Float, val corner: Int)

    fun floatBallCrop(context: Context, mode: FloatBallIconMode): FloatBallCrop {
        val p = store(context)
        val gif = mode == FloatBallIconMode.GIF
        return FloatBallCrop(
            p.getFloat(if (gif) KEY_FLOAT_BALL_CROP_GIF_SCALE else KEY_FLOAT_BALL_CROP_STATIC_SCALE, 1f).coerceIn(1f, 4f),
            p.getFloat(if (gif) KEY_FLOAT_BALL_CROP_GIF_X else KEY_FLOAT_BALL_CROP_STATIC_X, 0f).coerceIn(-1.5f, 1.5f),
            p.getFloat(if (gif) KEY_FLOAT_BALL_CROP_GIF_Y else KEY_FLOAT_BALL_CROP_STATIC_Y, 0f).coerceIn(-1.5f, 1.5f),
            p.getInt(if (gif) KEY_FLOAT_BALL_CROP_GIF_CORNER else KEY_FLOAT_BALL_CROP_STATIC_CORNER, 70).coerceIn(0, 100)
        )
    }

    fun setFloatBallCrop(context: Context, mode: FloatBallIconMode, scale: Float, x: Float, y: Float, corner: Int) {
        val gif = mode == FloatBallIconMode.GIF
        store(context).edit()
            .putFloat(if (gif) KEY_FLOAT_BALL_CROP_GIF_SCALE else KEY_FLOAT_BALL_CROP_STATIC_SCALE, scale.coerceIn(1f, 4f))
            .putFloat(if (gif) KEY_FLOAT_BALL_CROP_GIF_X else KEY_FLOAT_BALL_CROP_STATIC_X, x.coerceIn(-1.5f, 1.5f))
            .putFloat(if (gif) KEY_FLOAT_BALL_CROP_GIF_Y else KEY_FLOAT_BALL_CROP_STATIC_Y, y.coerceIn(-1.5f, 1.5f))
            .putInt(if (gif) KEY_FLOAT_BALL_CROP_GIF_CORNER else KEY_FLOAT_BALL_CROP_STATIC_CORNER, corner.coerceIn(0, 100))
            .apply()
    }

    fun clearFloatBallIcon(context: Context) {
        store(context).edit()
            .remove(KEY_FLOAT_BALL_ICON_URI)
            .remove(KEY_FLOAT_BALL_STATIC_URIS)
            .remove(KEY_FLOAT_BALL_GIF_URI)
            .remove(KEY_FLOAT_BALL_CROP_STATIC_SCALE)
            .remove(KEY_FLOAT_BALL_CROP_STATIC_X)
            .remove(KEY_FLOAT_BALL_CROP_STATIC_Y)
            .remove(KEY_FLOAT_BALL_CROP_STATIC_CORNER)
            .remove(KEY_FLOAT_BALL_CROP_GIF_SCALE)
            .remove(KEY_FLOAT_BALL_CROP_GIF_X)
            .remove(KEY_FLOAT_BALL_CROP_GIF_Y)
            .remove(KEY_FLOAT_BALL_CROP_GIF_CORNER)
            .putString(KEY_FLOAT_BALL_ICON_MODE, "static")
            .putBoolean(KEY_FLOAT_BALL_GIF_PLAY, false)
            .putBoolean(KEY_FLOAT_BALL_SLIDESHOW, false)
            .apply()
    }

    fun floatBallGifPlayEnabled(context: Context) =
        store(context).getBoolean(KEY_FLOAT_BALL_GIF_PLAY, false)

    fun setFloatBallGifPlayEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLOAT_BALL_GIF_PLAY, enabled).apply()
    }

    fun floatBallGifPauseWhenIdle(context: Context) =
        store(context).getBoolean(KEY_FLOAT_BALL_GIF_PAUSE_IDLE, true)

    fun setFloatBallGifPauseWhenIdle(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLOAT_BALL_GIF_PAUSE_IDLE, enabled).apply()
    }

    fun floatBallGifIdleSeconds(context: Context) =
        store(context).getInt(KEY_FLOAT_BALL_GIF_IDLE_SECONDS, 10).coerceIn(5, 30)

    fun setFloatBallGifIdleSeconds(context: Context, seconds: Int) {
        store(context).edit().putInt(
            KEY_FLOAT_BALL_GIF_IDLE_SECONDS,
            seconds.coerceIn(5, 30)
        ).apply()
    }

    fun floatBallSlideshowEnabled(context: Context) =
        store(context).getBoolean(KEY_FLOAT_BALL_SLIDESHOW, false)

    fun setFloatBallSlideshowEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLOAT_BALL_SLIDESHOW, enabled).apply()
    }

    fun floatBallSlideshowIntervalSeconds(context: Context) =
        store(context).getInt(KEY_FLOAT_BALL_SLIDESHOW_INTERVAL, 10).coerceIn(5, 300)

    fun setFloatBallSlideshowIntervalSeconds(context: Context, seconds: Int) {
        store(context).edit().putInt(
            KEY_FLOAT_BALL_SLIDESHOW_INTERVAL,
            seconds.coerceIn(5, 300)
        ).apply()
    }

    fun floatBallSlideshowPauseWhenScreenOff(context: Context) =
        store(context).getBoolean(KEY_FLOAT_BALL_SLIDESHOW_PAUSE_SCREEN_OFF, true)

    fun setFloatBallSlideshowPauseWhenScreenOff(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLOAT_BALL_SLIDESHOW_PAUSE_SCREEN_OFF, enabled).apply()
    }

    fun floatBallOpacity(context: Context) =
        store(context).getInt(KEY_FLOAT_BALL_OPACITY, 100).coerceIn(15, 100)

    fun setFloatBallOpacity(context: Context, opacity: Int) {
        store(context).edit().putInt(KEY_FLOAT_BALL_OPACITY, opacity.coerceIn(15, 100)).apply()
    }

    // Fresh installs start as a full circle; lowering the slider rounds the
    // corners into a squircle.
    fun floatBallCorner(context: Context) =
        store(context).getInt(KEY_FLOAT_BALL_CORNER, 100).coerceIn(0, 100)

    fun setFloatBallCorner(context: Context, percent: Int) {
        store(context).edit().putInt(KEY_FLOAT_BALL_CORNER, percent.coerceIn(0, 100)).apply()
    }

    fun floatBallEdgeHideEnabled(context: Context) =
        store(context).getBoolean(KEY_FLOAT_BALL_EDGE_HIDE_ENABLED, false)

    fun setFloatBallEdgeHideEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLOAT_BALL_EDGE_HIDE_ENABLED, enabled).apply()
    }

    // Edge hiding is applied per screen orientation; until toggled individually
    // it follows the master switch so existing users keep their behaviour.
    fun floatBallEdgeHideForOrientation(context: Context, landscape: Boolean): Boolean {
        val key = if (landscape) KEY_FLOAT_BALL_EDGE_HIDE_LANDSCAPE else KEY_FLOAT_BALL_EDGE_HIDE_PORTRAIT
        return store(context).getBoolean(key, floatBallEdgeHideEnabled(context))
    }

    fun setFloatBallEdgeHideForOrientation(context: Context, landscape: Boolean, enabled: Boolean) {
        val key = if (landscape) KEY_FLOAT_BALL_EDGE_HIDE_LANDSCAPE else KEY_FLOAT_BALL_EDGE_HIDE_PORTRAIT
        store(context).edit().putBoolean(key, enabled).apply()
    }

    /** -100 = hide on the left, +100 = hide on the right, 0 = stay centred. */
    fun floatBallEdgeValue(context: Context, landscape: Boolean): Int {
        val key = if (landscape) KEY_FLOAT_BALL_EDGE_VALUE_LANDSCAPE else KEY_FLOAT_BALL_EDGE_VALUE_PORTRAIT
        return store(context).getInt(key, 50).coerceIn(-100, 100)
    }

    fun setFloatBallEdgeValue(context: Context, landscape: Boolean, value: Int) {
        val key = if (landscape) KEY_FLOAT_BALL_EDGE_VALUE_LANDSCAPE else KEY_FLOAT_BALL_EDGE_VALUE_PORTRAIT
        store(context).edit().putInt(key, value.coerceIn(-100, 100)).apply()
    }

    fun floatBallAutoMirror(context: Context) =
        store(context).getBoolean(KEY_FLOAT_BALL_AUTO_MIRROR, false)

    fun setFloatBallAutoMirror(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLOAT_BALL_AUTO_MIRROR, enabled).apply()
    }

    fun floatBallFixedSide(context: Context): String =
        store(context).getString(KEY_FLOAT_BALL_FIXED_SIDE, "right") ?: "right"

    fun setFloatBallFixedSide(context: Context, side: String) {
        store(context).edit()
            .putString(KEY_FLOAT_BALL_FIXED_SIDE, if (side == "left") "left" else "right")
            .apply()
    }

    fun floatBallEditOrientation(context: Context): String =
        store(context).getString(KEY_FLOAT_BALL_EDIT_ORIENTATION, "both")
            ?: "both"

    fun setFloatBallEditOrientation(context: Context, orientation: String) {
        store(context).edit()
            .putString(
                KEY_FLOAT_BALL_EDIT_ORIENTATION,
                when (orientation) {
                    "portrait" -> "portrait"
                    "landscape" -> "landscape"
                    else -> "both"
                }
            )
            .apply()
    }

    fun floatBallEdgeOffset(context: Context, landscape: Boolean, left: Boolean): Int {
        val key = when {
            landscape && left -> KEY_FLOAT_BALL_LANDSCAPE_LEFT_OFFSET
            landscape -> KEY_FLOAT_BALL_LANDSCAPE_RIGHT_OFFSET
            left -> KEY_FLOAT_BALL_PORTRAIT_LEFT_OFFSET
            else -> KEY_FLOAT_BALL_PORTRAIT_RIGHT_OFFSET
        }
        return store(context).getInt(key, 0).coerceIn(0, 80)
    }

    fun setFloatBallEdgeOffset(context: Context, landscape: Boolean, left: Boolean, percent: Int) {
        val key = when {
            landscape && left -> KEY_FLOAT_BALL_LANDSCAPE_LEFT_OFFSET
            landscape -> KEY_FLOAT_BALL_LANDSCAPE_RIGHT_OFFSET
            left -> KEY_FLOAT_BALL_PORTRAIT_LEFT_OFFSET
            else -> KEY_FLOAT_BALL_PORTRAIT_RIGHT_OFFSET
        }
        store(context).edit().putInt(key, percent.coerceIn(0, 80)).apply()
    }

    fun dimWhenIdle(context: Context) =
        store(context).getBoolean(KEY_DIM_WHEN_IDLE, false)

    fun setDimWhenIdle(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_DIM_WHEN_IDLE, enabled).apply()
    }

    fun idleOpacity(context: Context) =
        store(context).getInt(KEY_IDLE_OPACITY, 25).coerceIn(5, 80)

    fun setIdleOpacity(context: Context, opacity: Int) {
        store(context).edit().putInt(KEY_IDLE_OPACITY, opacity.coerceIn(5, 80)).apply()
    }

    fun idleDelaySeconds(context: Context) =
        store(context).getInt(KEY_IDLE_DELAY_SECONDS, 5).coerceIn(1, 60)

    fun setIdleDelaySeconds(context: Context, seconds: Int) {
        store(context).edit().putInt(KEY_IDLE_DELAY_SECONDS, seconds.coerceIn(1, 60)).apply()
    }

    fun freezeAnimationWhenIdle(context: Context) =
        store(context).getBoolean(KEY_FREEZE_ANIMATION_WHEN_IDLE, false)

    fun setFreezeAnimationWhenIdle(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FREEZE_ANIMATION_WHEN_IDLE, enabled).apply()
    }

    fun hapticsEnabled(context: Context) =
        store(context).getBoolean(KEY_HAPTICS_ENABLED, false)

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
    }

    fun hapticStrength(context: Context) =
        store(context).getInt(KEY_HAPTIC_STRENGTH, 50).coerceIn(1, 100)

    fun setHapticStrength(context: Context, strength: Int) {
        store(context).edit().putInt(KEY_HAPTIC_STRENGTH, strength.coerceIn(1, 100)).apply()
    }

    fun recordingControlLabels(context: Context) =
        store(context).getBoolean(KEY_RECORDING_CONTROL_LABELS, false)

    fun setRecordingControlLabels(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_RECORDING_CONTROL_LABELS, enabled).apply()
    }

    fun recordingPhoneSourceEnabled(context: Context) =
        store(context).getBoolean(KEY_RECORDING_PHONE_SOURCE, true)

    fun setRecordingPhoneSourceEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_RECORDING_PHONE_SOURCE, enabled).apply()
    }

    fun recordingExternalSourceEnabled(context: Context) =
        store(context).getBoolean(KEY_RECORDING_EXTERNAL_SOURCE, true)

    fun setRecordingExternalSourceEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_RECORDING_EXTERNAL_SOURCE, enabled).apply()
    }

    fun assistantReplacementEnabled(context: Context) =
        store(context).getBoolean(KEY_ASSISTANT_REPLACEMENT_ENABLED, false)

    fun setAssistantReplacementEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_ASSISTANT_REPLACEMENT_ENABLED, enabled).apply()
    }

    fun assistantReplacementAction(context: Context) =
        store(context).getString(KEY_ASSISTANT_REPLACEMENT_ACTION, ActionDispatcher.WIDGET_POPUP)
            ?: ActionDispatcher.WIDGET_POPUP

    fun setAssistantReplacementAction(context: Context, action: String) {
        store(context).edit().putString(KEY_ASSISTANT_REPLACEMENT_ACTION, action).apply()
    }

    fun flashlightEnabled(context: Context) =
        store(context).getBoolean(KEY_FLASHLIGHT_ENABLED, false)

    fun setFlashlightEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_FLASHLIGHT_ENABLED, enabled).apply()
    }

    fun setPendingOverlayEnable(context: Context, pending: Boolean) {
        store(context).edit().putBoolean(KEY_PENDING_OVERLAY_ENABLE, pending).apply()
    }

    fun takePendingOverlayEnable(context: Context): Boolean {
        val prefs = store(context)
        val pending = prefs.getBoolean(KEY_PENDING_OVERLAY_ENABLE, false)
        if (pending && android.provider.Settings.canDrawOverlays(context)) {
            prefs.edit().remove(KEY_PENDING_OVERLAY_ENABLE).apply()
        } else {
            return false
        }
        return pending
    }

    private fun defaultPanel(context: Context, landscape: Boolean) = listOf(
        WidgetPanelPage(
            id = if (landscape) "landscape-default" else "portrait-default",
            name = context.getString(R.string.default_panel_name),
            backgroundOpacity = popupPanelOpacity(context),
            backgroundColor = popupPanelColor(context)
        )
    )

    private const val TAG = "AppPrefs"
}

object PanelItemType {
    const val WIDGET = "widget"
    const val APP = "app"
    const val SHORTCUT = "shortcut"
    const val ACTION = "action"
}

enum class Gesture(val preferenceKey: String) {
    SINGLE_TAP("single_action"),
    DOUBLE_TAP("double_action"),
    TRIPLE_TAP("triple_action"),
    LONG_PRESS("long_action"),
    SWIPE_UP("swipe_up_action")
}

data class ActionChoice(
    val id: String,
    val label: String,
    val requiresAccessibility: Boolean = false
)

object ActionDispatcher {
    const val WIDGET_POPUP = "widget_popup"
    const val EXPANSION_PANEL = "expansion_panel"
    const val BRIGHTNESS_SLIDER = "brightness_slider"
    const val MEDIA_VOLUME = "media_volume"
    const val FLASHLIGHT = "flashlight"
    const val TOGGLE_FLOAT_BALL = "toggle_float_ball"
    const val NAV_BACK = "navigation_back"
    const val NAV_RECENTS = "navigation_recents"
    const val NAV_HOME = "navigation_home"
    const val CALL_ASSISTANT = "call_assistant"
    const val OPEN_ASSISTANT_SETTINGS = "open_assistant_settings"
    const val BACKGROUND_RECORDING = "background_recording"
    const val NOTIFICATION_PANEL = "notification_panel"
    const val ACCESSIBILITY_SETTINGS = "accessibility_settings"
    const val OPEN_APP = "open_app"
    const val OPEN_SELECTED_APP = "open_selected_app"
    const val OPEN_SELECTED_SHORTCUT = "open_selected_shortcut"
    const val AUTO_ROTATION = "auto_rotation"
    const val CIRCLE_SEARCH = "circle_search"
    const val NONE = "none"

    fun quickActions(context: Context, includeAssistant: Boolean = true): List<ActionChoice> = buildList {
        add(ActionChoice(FLASHLIGHT, context.getString(R.string.action_flashlight)))
        add(ActionChoice(BACKGROUND_RECORDING, context.getString(R.string.action_background_recording)))
        add(ActionChoice(WIDGET_POPUP, context.getString(R.string.action_widget_popup)))
        add(ActionChoice(AUTO_ROTATION, context.getString(R.string.action_auto_rotation)))
        add(ActionChoice(CIRCLE_SEARCH, context.getString(R.string.action_circle_search)))
        add(ActionChoice(NOTIFICATION_PANEL, context.getString(R.string.action_notification_panel), true))
        add(ActionChoice(NAV_BACK, context.getString(R.string.action_nav_back), true))
        add(ActionChoice(NAV_HOME, context.getString(R.string.action_nav_home), true))
        add(ActionChoice(NAV_RECENTS, context.getString(R.string.action_nav_recents), true))
        if (includeAssistant) {
            add(ActionChoice(CALL_ASSISTANT, context.getString(R.string.action_call_assistant)))
            add(ActionChoice(
                OPEN_ASSISTANT_SETTINGS,
                context.getString(R.string.action_open_assistant_settings)
            ))
        }
    }

    /**
     * Every action that can run on its own (no app/shortcut picker needed).
     * Used by the expansion-panel action list, which shows the same choices as
     * the gesture pickers.
     */
    fun selectableActions(context: Context, includeAssistant: Boolean = true): List<ActionChoice> =
        quickActions(context, includeAssistant) + listOf(
            ActionChoice(EXPANSION_PANEL, context.getString(R.string.action_expansion_panel)),
            ActionChoice(BRIGHTNESS_SLIDER, context.getString(R.string.action_brightness_slider)),
            ActionChoice(MEDIA_VOLUME, context.getString(R.string.action_media_volume)),
            ActionChoice(TOGGLE_FLOAT_BALL, context.getString(R.string.action_toggle_float_ball)),
            ActionChoice(ACCESSIBILITY_SETTINGS, context.getString(R.string.action_accessibility_settings)),
            ActionChoice(OPEN_APP, context.getString(R.string.action_open_settings))
        )

    fun openAssistantSettings(context: Context) {
        val actions = listOf(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
        )
        for (action in actions) {
            try {
                context.startActivity(
                    Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (error: android.content.ActivityNotFoundException) {
                Log.w("ActionDispatcher", "Assistant settings action is unavailable: $action", error)
            } catch (error: SecurityException) {
                Log.w("ActionDispatcher", "Assistant settings action is not accessible: $action", error)
            }
        }
        Toast.makeText(context, R.string.assistant_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    fun selectedActionType(action: String): String = action.substringBefore(':')

    fun selectedActionLabel(action: String): String? {
        val parts = action.split(':', limit = 3)
        if (parts.size != 3 ||
            parts[0] !in setOf(OPEN_SELECTED_APP, OPEN_SELECTED_SHORTCUT)
        ) return null
        return Uri.decode(parts[2]).takeIf { it.isNotBlank() }
    }

    fun encodeSelectedAction(type: String, value: String, label: String): String =
        "$type:${Uri.encode(value)}:${Uri.encode(label)}"

    private fun dispatchSelectedApp(context: Context, action: String) {
        try {
            val value = Uri.decode(action.split(':', limit = 3).getOrNull(1).orEmpty())
            val component = ComponentName.unflattenFromString(value)
                ?: throw IllegalArgumentException("Invalid selected app component")
            FloatingApps.open(context, component)
        } catch (error: Exception) {
            Log.e("PanelsAssistive", "Unable to open the selected app", error)
            Toast.makeText(context, R.string.panel_item_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchSelectedShortcut(context: Context, action: String) {
        try {
            val value = Uri.decode(action.split(':', limit = 3).getOrNull(1).orEmpty())
            context.startActivity(
                Intent.parseUri(value, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: Exception) {
            Log.e("PanelsAssistive", "Unable to open the selected shortcut", error)
            Toast.makeText(context, R.string.panel_item_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun dispatch(context: Context, action: String) {
        performHapticFeedback(context)
        if (action.startsWith("$OPEN_SELECTED_APP:")) {
            dispatchSelectedApp(context, action)
            return
        }
        if (action.startsWith("$OPEN_SELECTED_SHORTCUT:")) {
            dispatchSelectedShortcut(context, action)
            return
        }
        when (action) {
            NONE -> Unit
            WIDGET_POPUP -> {
                if (AppPrefs.widgetPopupEnabled(context)) {
                    FloatBallService.start(context, FloatBallService.ACTION_SHOW_POPUP)
                } else {
                    Toast.makeText(context, R.string.widget_popup_disabled, Toast.LENGTH_SHORT).show()
                }
            }
            EXPANSION_PANEL ->
                FloatBallService.start(context, FloatBallService.ACTION_SHOW_EXPANSION_PANEL)
            BRIGHTNESS_SLIDER ->
                FloatBallService.start(context, FloatBallService.ACTION_SHOW_BRIGHTNESS_SLIDER)
            MEDIA_VOLUME -> {
                val audioManager = context.getSystemService(AudioManager::class.java)
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_SAME,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            FLASHLIGHT -> FloatBallService.toggleFlashlight(context)
            AUTO_ROTATION -> {
                if (!Settings.System.canWrite(context)) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    try {
                        val enabled = Settings.System.getInt(
                            context.contentResolver,
                            Settings.System.ACCELEROMETER_ROTATION,
                            0
                        ) == 1
                        val saved = Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.ACCELEROMETER_ROTATION,
                            if (enabled) 0 else 1
                        )
                        if (!saved) {
                            Toast.makeText(
                                context,
                                R.string.auto_rotation_permission_required,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (error: SecurityException) {
                        Log.e("PanelsAssistive", "Unable to toggle auto-rotation", error)
                        Toast.makeText(context, R.string.auto_rotation_permission_required, Toast.LENGTH_LONG).show()
                    }
                }
            }
            CIRCLE_SEARCH -> MiCts.trigger(context)
            TOGGLE_FLOAT_BALL -> {
                if (FloatBallService.running && AppPrefs.floatBallEnabled(context)) {
                    FloatBallService.stop(context)
                } else {
                    FloatBallService.start(context)
                }
            }
            NAV_BACK -> performAccessibilityAction(context, AccessibilityService.GLOBAL_ACTION_BACK)
            NAV_RECENTS -> performAccessibilityAction(context, AccessibilityService.GLOBAL_ACTION_RECENTS)
            NAV_HOME -> performAccessibilityAction(context, AccessibilityService.GLOBAL_ACTION_HOME)
            CALL_ASSISTANT -> context.startActivity(
                Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            OPEN_ASSISTANT_SETTINGS -> openAssistantSettings(context)
            BACKGROUND_RECORDING -> BackgroundRecordingService.requestStart(context)
            NOTIFICATION_PANEL -> {
                performAccessibilityAction(context, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            }
            ACCESSIBILITY_SETTINGS -> openAccessibilitySettings(context)
            OPEN_APP -> context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun performAccessibilityAction(context: Context, globalAction: Int) {
        if (!AccessibilityActionService.performGlobalAction(globalAction)) {
            Toast.makeText(
                context,
                context.getString(R.string.enable_accessibility_for_action),
                Toast.LENGTH_LONG
            ).show()
            openAccessibilitySettings(context)
        }
    }

    fun performHapticFeedback(context: Context) {
        if (!AppPrefs.hapticsEnabled(context)) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        val strength = AppPrefs.hapticStrength(context)
        val duration = 20L + strength / 5L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    duration,
                    (strength * 255 / 100).coerceIn(1, 255)
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}

class AccessibilityActionService : AccessibilityService() {
    override fun onServiceConnected() {
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeService: AccessibilityActionService? = null

        fun performGlobalAction(action: Int): Boolean =
            activeService?.performGlobalAction(action) == true

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, AccessibilityActionService::class.java)
                .flattenToString()
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }

}

class PanelsVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Useful when the ROM silently swaps the assistant back to Google.
        Log.i("PanelsAssistant", "VoiceInteractionService bound and ready")
    }
}

class PanelsRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}

class PanelsVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        PanelsVoiceInteractionSession(this)
}

private class PanelsVoiceInteractionSession(private val appContext: Context) :
    VoiceInteractionSession(appContext) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Handler(Looper.getMainLooper()).post {
            // A throwing action (for example a foreground-service start that the
            // system refuses) must never crash the session process: a crashed
            // assistant makes the system fall back to Google on later gestures.
            try {
                if (AppPrefs.assistantReplacementEnabled(appContext)) {
                    ActionDispatcher.dispatch(
                        appContext,
                        AppPrefs.assistantReplacementAction(appContext)
                    )
                }
            } catch (error: Exception) {
                Log.e("PanelsAssistant", "Assistant action failed", error)
            } finally {
                hide()
            }
        }
    }
}
