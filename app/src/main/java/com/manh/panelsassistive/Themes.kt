package com.manh.panelsassistive

import android.app.Application
import android.content.Context
import android.graphics.Color

/** One palette drives the app screens, floating panels and the home-screen widgets. */
data class AppTheme(
    val key: String,
    val label: String,
    val accent: Int,
    val accentStrong: Int,
    val pageTop: Int,
    val pageMid: Int,
    val pageBottom: Int,
    val panel: Int,
    val panelOpacity: Int = 80,
    val corner: Int = 26
)

object AppThemes {
    const val DEFAULT_KEY = "aurora"
    const val CUSTOM_KEY = "custom"

    /** Accent swatches offered before the free HSV picker. */
    val ACCENT_PALETTE = intArrayOf(
        0xFF7CBEFF.toInt(), 0xFF6EE7C8.toInt(), 0xFFFFC46B.toInt(),
        0xFFFF8E7A.toInt(), 0xFFC39BFF.toInt(), 0xFFF48FB1.toInt(),
        0xFF9CCC65.toInt(), 0xFF4DD0E1.toInt()
    )

    val ALL = listOf(
        AppTheme(
            key = "aurora",
            label = "Aurora",
            accent = 0xFF7CBEFF.toInt(),
            accentStrong = 0xFF4E96E8.toInt(),
            pageTop = 0xFF091422.toInt(),
            pageMid = 0xFF0D1E30.toInt(),
            pageBottom = 0xFF07101D.toInt(),
            panel = 0xFF18293D.toInt()
        ),
        AppTheme(
            key = "coral",
            label = "San hô",
            accent = 0xFFFF8E7A.toInt(),
            accentStrong = 0xFFDD6350.toInt(),
            pageTop = 0xFF140A0F.toInt(),
            pageMid = 0xFF291219.toInt(),
            pageBottom = 0xFF0C0507.toInt(),
            panel = 0xFF311A20.toInt()
        ),
        AppTheme(
            key = "mint",
            label = "Bạc hà",
            accent = 0xFF6EE7C8.toInt(),
            accentStrong = 0xFF2FB79A.toInt(),
            pageTop = 0xFF07120F.toInt(),
            pageMid = 0xFF0F211D.toInt(),
            pageBottom = 0xFF040B09.toInt(),
            panel = 0xFF152A25.toInt()
        ),
        AppTheme(
            key = "violet",
            label = "Tím mây",
            accent = 0xFFC39BFF.toInt(),
            accentStrong = 0xFF9560DE.toInt(),
            pageTop = 0xFF0D0A17.toInt(),
            pageMid = 0xFF1B1429.toInt(),
            pageBottom = 0xFF07050E.toInt(),
            panel = 0xFF231B36.toInt()
        ),
        AppTheme(
            key = "amber",
            label = "Hổ phách",
            accent = 0xFFFFC46B.toInt(),
            accentStrong = 0xFFDB9834.toInt(),
            pageTop = 0xFF120D06.toInt(),
            pageMid = 0xFF231A0D.toInt(),
            pageBottom = 0xFF0B0703.toInt(),
            panel = 0xFF2A2011.toInt()
        )
    )

    fun current(context: Context): AppTheme {
        val key = AppPrefs.themeKey(context)
        val base = if (key == CUSTOM_KEY) {
            custom(context)
        } else {
            ALL.firstOrNull { it.key == key } ?: ALL.first()
        }
        val picked = AppPrefs.themeAccent(context)
        if (picked == 0) return base
        return base.copy(accent = picked, accentStrong = strongAccent(picked))
    }

    private fun strongAccent(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        return Color.HSVToColor(
            floatArrayOf(
                hsv[0],
                (hsv[1] + 0.08f).coerceAtMost(1f),
                (hsv[2] * 0.78f).coerceIn(0f, 1f)
            )
        )
    }

    /** Builds a dark page + panel palette from one freely picked accent colour. */
    fun custom(context: Context): AppTheme {
        val accent = AppPrefs.themeAccent(context).takeIf { it != 0 } ?: 0xFF7CBEFF.toInt()
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        fun shade(value: Float, saturation: Float): Int =
            Color.HSVToColor(
                floatArrayOf(
                    hsv[0],
                    saturation.coerceIn(0f, 1f),
                    value.coerceIn(0f, 1f)
                )
            )
        return AppTheme(
            key = CUSTOM_KEY,
            label = "Tùy chỉnh",
            accent = accent,
            accentStrong = strongAccent(accent),
            pageTop = shade(0.085f, 0.55f),
            pageMid = shade(0.16f, 0.50f),
            pageBottom = shade(0.055f, 0.60f),
            panel = shade(0.19f, 0.42f)
        )
    }

    /**
     * Applies a palette everywhere: accent for the app UI, page gradient and
     * status bars, panel colours for the floating surfaces, then the widgets.
     */
    fun apply(context: Context, theme: AppTheme) {
        // Background palettes no longer move the accent: keep the accent the
        // user already sees, then change only the page and panel colours.
        if (AppPrefs.themeAccent(context) == 0) {
            AppPrefs.setThemeAccent(context, current(context).accent)
        }
        AppPrefs.setThemeKey(context, theme.key)
        applyPalette(context, theme)
    }

    /** Free accent pick: recolours buttons and highlights without touching the background. */
    fun applyAccent(context: Context, accent: Int) {
        AppPrefs.setThemeAccent(context, accent)
        applyPalette(context, current(context))
    }

    private fun applyPalette(context: Context, theme: AppTheme) {
        GlassUi.applyTheme(context)
        AppPrefs.setSharedPanelColor(context, theme.panel)
        AppPrefs.setSharedPanelOpacity(context, theme.panelOpacity)
        AppPrefs.setSharedPanelCorner(context, theme.corner)
        AppPrefs.setPopupPanelColor(context, theme.panel)
        AppPrefs.setPopupPanelOpacity(context, theme.panelOpacity)
        AppPrefs.setPopupPanelCorner(context, theme.corner)
        FloatBallService.refreshPopupAppearance(context)
        GlassWidgets.refreshAll(context)
    }
}

class PanelsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlassUi.applyTheme(this)
        // Realtime redraw of volume/brightness cards after hardware-key changes.
        WidgetLevelObserver.ensure(this)
        HideFromRecents.install(this)
    }
}
