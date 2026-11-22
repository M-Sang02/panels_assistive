package com.manh.panelsassistive

import android.content.Context
import android.graphics.Color

/** Action icons now use the Lucide outline icon library instead of hand-drawn legacy glyphs. */
class ActionGlyphView(
    context: Context,
    action: String,
    color: Int = Color.WHITE
) : LucideGlyphView(context, iconFor(action), color) {
    companion object {
        /** Mirrors the icons used by the matching rows in the settings screens. */
        fun iconFor(action: String): String = when (action) {
            ActionDispatcher.FLASHLIGHT -> "flashlight"
            ActionDispatcher.BACKGROUND_RECORDING -> "audio-lines"
            ActionDispatcher.WIDGET_POPUP -> "square-stack"
            ActionDispatcher.EXPANSION_PANEL -> "sliders-horizontal"
            ActionDispatcher.BRIGHTNESS_SLIDER -> "sun"
            ActionDispatcher.MEDIA_VOLUME -> "volume-2"
            ActionDispatcher.AUTO_ROTATION -> "smartphone"
            ActionDispatcher.CIRCLE_SEARCH -> "scan-search"
            ActionDispatcher.NOTIFICATION_PANEL -> "bell"
            ActionDispatcher.NAV_BACK -> "arrow-left"
            ActionDispatcher.NAV_HOME -> "house"
            ActionDispatcher.NAV_RECENTS -> "square-library"
            ActionDispatcher.CALL_ASSISTANT -> "mic"
            ActionDispatcher.TOGGLE_FLOAT_BALL -> "circle-dot"
            ActionDispatcher.ACCESSIBILITY_SETTINGS -> "accessibility"
            ActionDispatcher.OPEN_APP -> "settings-2"
            ActionDispatcher.OPEN_SELECTED_APP -> "external-link"
            ActionDispatcher.OPEN_SELECTED_SHORTCUT -> "arrow-up-right"
            else -> "circle-help"
        }
    }
}
