package com.manh.panelsassistive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Home-screen widgets in the dark frosted-glass language of the reference
 * mockup: a navy see-through pane, soft light bloom, thin rim, white icons and
 * an accent-tinted level fill for volume/brightness. Everything is RemoteViews,
 * so third-party launchers can host them too.
 */
object GlassWidgets {
    const val ACTION_VOLUME_UP = "com.manh.panelsassistive.widget.VOLUME_UP"
    const val ACTION_VOLUME_DOWN = "com.manh.panelsassistive.widget.VOLUME_DOWN"
    const val ACTION_BRIGHTNESS_UP = "com.manh.panelsassistive.widget.BRIGHTNESS_UP"
    const val ACTION_BRIGHTNESS_DOWN = "com.manh.panelsassistive.widget.BRIGHTNESS_DOWN"
    const val ACTION_MEDIA_PREV = "com.manh.panelsassistive.widget.MEDIA_PREV"
    const val ACTION_MEDIA_TOGGLE = "com.manh.panelsassistive.widget.MEDIA_TOGGLE"
    const val ACTION_MEDIA_NEXT = "com.manh.panelsassistive.widget.MEDIA_NEXT"
    const val EXTRA_WIDGET_ID = "com.manh.panelsassistive.widget.WIDGET_ID"

    val providers = listOf(
        GlassVolumeVerticalWidget::class.java,
        GlassVolumeHorizontalWidget::class.java,
        GlassBrightnessVerticalWidget::class.java,
        GlassBrightnessHorizontalWidget::class.java,
        GlassClockWidget::class.java,
        GlassMusicWidget::class.java,
        GlassMusicCoverWidget::class.java
    )

    /** Every music card, so media notifications repaint both variants. */
    val musicProviders = listOf(
        GlassMusicWidget::class.java,
        GlassMusicCoverWidget::class.java
    )

    val levelProviders = listOf(
        GlassVolumeVerticalWidget::class.java,
        GlassVolumeHorizontalWidget::class.java,
        GlassBrightnessVerticalWidget::class.java,
        GlassBrightnessHorizontalWidget::class.java
    )

    fun refreshAll(context: Context) {
        providers.forEach { refresh(context, it) }
    }

    /** Repaints both music cards after a session or assignment change. */
    fun refreshMusicWidgets(context: Context) {
        musicProviders.forEach { refresh(context, it) }
    }

    fun refreshMusicSoon(context: Context) {
        musicProviders.forEach { refreshSoon(context, it) }
    }

    /** Called by the settings observers whenever volume or brightness changes. */
    fun refreshLevelWidgets(context: Context) {
        levelProviders.forEach { refresh(context, it) }
    }

    fun refresh(context: Context, provider: Class<*>) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, provider)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return
        context.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(component)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        )
    }

    /** Two follow-up redraws make the white fill catch up in visible steps. */
    fun refreshSoon(context: Context, provider: Class<*>) {
        val handler = Handler(Looper.getMainLooper())
        longArrayOf(70L, 180L).forEach { delay ->
            handler.postDelayed({ refresh(context, provider) }, delay)
        }
    }

    fun sizeDp(options: Bundle): Pair<Int, Int> {
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        return (if (width > 0) width else 180) to (if (height > 0) height else 110)
    }

    enum class MusicSize { COMPACT, WIDE, LARGE }

    /**
     * The music card picks one of three layouts from the launcher-provided
     * size: 2x2 compact, 4x2 wide, 4x4 large. Controls live in weighted rows,
     * so they are always split evenly and simply get smaller when the widget
     * is shrunk instead of disappearing.
     */
    fun musicSize(options: Bundle): MusicSize {
        val (width, height) = sizeDp(options)
        return when {
            // 4x4-ish square: both sides much larger than one grid cell.
            width >= 300 && height >= 300 -> MusicSize.LARGE
            // Clearly wider than tall: 4x2 / 3x2.
            width >= 200 && width >= height * 3 / 2 -> MusicSize.WIDE
            else -> MusicSize.COMPACT
        }
    }

    fun musicLayoutId(size: MusicSize): Int = when (size) {
        MusicSize.COMPACT -> R.layout.widget_music
        MusicSize.WIDE -> R.layout.widget_music_wide
        MusicSize.LARGE -> R.layout.widget_music_large
    }

    fun pending(
        context: Context,
        cls: Class<*>,
        action: String,
        request: Int,
        widgetId: Int = -1
    ): PendingIntent {
        val intent = Intent(context, cls).setAction(action)
        if (widgetId >= 0) intent.putExtra(EXTRA_WIDGET_ID, widgetId)
        return PendingIntent.getBroadcast(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Tap target for widget cards: the assigned app, or Panels when none is set. */
    fun targetPending(context: Context, request: Int): PendingIntent {
        val assigned = AppPrefs.widgetShortcut(context)
            .takeIf { it.isNotEmpty() }
            ?.let { ComponentName.unflattenFromString(it) }
        val intent = if (assigned != null) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(assigned)
        } else {
            Intent(context, MainActivity::class.java)
        }
        return PendingIntent.getActivity(
            context,
            request,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Tapping the album art / track info opens the app that is playing right
     * now, or the app assigned to this widget instance when nothing plays.
     */
    fun musicTapPending(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            widgetId * 16 + 12,
            Intent(context, WidgetLaunchActivity::class.java)
                .putExtra(WidgetLaunchActivity.EXTRA_MEDIA_TAP, true)
                .putExtra(EXTRA_WIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Idle play button: opens the assigned app and asks it to start playing. */
    fun musicPlayNowPending(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            widgetId * 16 + 13,
            Intent(context, WidgetLaunchActivity::class.java)
                .putExtra(WidgetLaunchActivity.EXTRA_PLAY_NOW, true)
                .putExtra(EXTRA_WIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun bindCard(
        views: RemoteViews,
        context: Context,
        options: Bundle,
        fillLevel: Float = -1f,
        fillVertical: Boolean = true,
        glow: Boolean = false
    ) {
        val (widthDp, heightDp) = sizeDp(options)
        views.setImageViewBitmap(
            R.id.widget_bg,
            cardBitmap(
                context,
                dp(context, widthDp).coerceIn(dp(context, 40), dp(context, 420)),
                dp(context, heightDp).coerceIn(dp(context, 40), dp(context, 420)),
                fillLevel,
                fillVertical,
                glow,
                false
            )
        )
    }

    /** Music glass: a soft bloom tinted by the album art when one is available. */
    fun bindMusicCard(views: RemoteViews, context: Context, options: Bundle, art: Bitmap?) {
        val (widthDp, heightDp) = sizeDp(options)
        views.setImageViewBitmap(
            R.id.widget_bg,
            cardBitmap(
                context,
                dp(context, widthDp).coerceIn(dp(context, 40), dp(context, 420)),
                dp(context, heightDp).coerceIn(dp(context, 40), dp(context, 420)),
                -1f,
                true,
                true,
                true,
                artTint(art)
            )
        )
    }

    /**
     * Average colour of the album art, softened toward white so it reads as a
     * glow rather than a solid wash. Returns 0 when no usable tint exists.
     */
    fun artTint(art: Bitmap?): Int {
        if (art == null) return 0
        val side = 12
        val small = runCatching { Bitmap.createScaledBitmap(art, side, side, true) }
            .getOrNull() ?: return 0
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        for (y in 0 until side) {
            for (x in 0 until side) {
                val color = small.getPixel(x, y)
                if (Color.alpha(color) < 200) continue
                val redPart = Color.red(color)
                val greenPart = Color.green(color)
                val bluePart = Color.blue(color)
                val maxPart = maxOf(redPart, greenPart, bluePart)
                val minPart = minOf(redPart, greenPart, bluePart)
                // Prefer vivid pixels so a mostly grey cover still gives an accent.
                val share = 0.3 + (maxPart - minPart) / 255.0
                red += redPart * share
                green += greenPart * share
                blue += bluePart * share
                weight += share
            }
        }
        if (small !== art) small.recycle()
        if (weight <= 0.0) return 0
        var tint = Color.rgb(
            (red / weight).roundToInt().coerceIn(0, 255),
            (green / weight).roundToInt().coerceIn(0, 255),
            (blue / weight).roundToInt().coerceIn(0, 255)
        )
        // Lift dark covers and blend toward white for a soft, airy bloom.
        tint = blend(tint, Color.WHITE, 0.45f)
        return blend(tint, Color.WHITE, 0.15f)
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val share = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) * (1f - share) + Color.red(to) * share).roundToInt(),
            (Color.green(from) * (1f - share) + Color.green(to) * share).roundToInt(),
            (Color.blue(from) * (1f - share) + Color.blue(to) * share).roundToInt()
        )
    }

    fun circleBitmap(context: Context, diameterPx: Int, color: Int): Bitmap {
        val size = diameterPx.coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bitmap
    }

    /** Renders a Lucide glyph into a bitmap for RemoteViews buttons. */
    fun glyphBitmap(context: Context, name: String, color: Int, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val glyph = LucideGlyphs.glyph(context, name)
        if (glyph.isEmpty()) return bitmap
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = LucideGlyphs.typeface(context)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.8f
            this.color = color
            // A soft shadow keeps white glyphs readable on a bright wallpaper.
            setShadowLayer(
                dp(context, 2).toFloat(),
                0f,
                dp(context, 1) * 0.35f,
                Color.argb(110, 0, 0, 0)
            )
        }
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        Canvas(bitmap).drawText(glyph, size / 2f, baseline, paint)
        return bitmap
    }

    /** Round album art with a thin light ring, or a glass disc while idle. */
    fun circularArtBitmap(
        context: Context,
        source: Bitmap?,
        sizePx: Int,
        ringColor: Int
    ): Bitmap {
        val size = sizePx.coerceAtLeast(4)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val center = size / 2f
        val radius = center - dp(context, 1)
        canvas.save()
        val clip = Path().apply { addCircle(center, center, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        if (source != null) {
            val scale = max(size.toFloat() / source.width, size.toFloat() / source.height)
            val drawWidth = source.width * scale
            val drawHeight = source.height * scale
            val left = (size - drawWidth) / 2f
            val top = (size - drawHeight) / 2f
            canvas.drawBitmap(
                source,
                null,
                RectF(left, top, left + drawWidth, top + drawHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, size.toFloat(),
                    Color.argb(72, 255, 255, 255),
                    Color.argb(16, 255, 255, 255),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), fill)
            val glyph = LucideGlyphs.glyph(context, "music")
            if (glyph.isNotEmpty()) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = LucideGlyphs.typeface(context)
                    textAlign = Paint.Align.CENTER
                    textSize = size * 0.4f
                    color = Color.argb(210, 255, 255, 255)
                }
                val metrics = paint.fontMetrics
                val baseline = center - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(glyph, center, baseline, paint)
            }
        }
        canvas.restore()
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (context.resources.displayMetrics.density * 0.9f)
                .coerceAtLeast(1f)
            color = ringColor
        }
        canvas.drawCircle(center, center, radius, ring)
        return output
    }

    private fun cardBitmap(
        context: Context,
        width: Int,
        height: Int,
        fillLevel: Float,
        fillVertical: Boolean,
        glow: Boolean,
        strong: Boolean,
        bloomColor: Int = 0
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = dp(context, 32).toFloat().coerceAtMost(minOf(width, height) / 2f)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius, radius, Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        val accent = AppThemes.current(context).accent
        // Airy frost: the launcher wallpaper must stay visible through the card.
        val topAlpha = if (strong) 88 else 76
        val bottomAlpha = if (strong) 52 else 46
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.argb(topAlpha, 26, 46, 74),
                Color.argb(bottomAlpha, 10, 21, 38),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        // Soft top sheen so the pane reads as lit glass.
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height * 0.68f,
                Color.argb(if (strong) 30 else 22, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.68f, sheen)
        // Bloom from a corner: album-art tint for music, accent for the rest.
        val tint = if (bloomColor != 0) bloomColor else accent
        val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.84f, height * 0.94f, maxOf(width, height) * 0.95f,
                Color.argb(if (glow) 64 else 40, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bloom)
        if (glow) {
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    width * 0.2f, height * 0.08f, maxOf(width, height) * 0.9f,
                    Color.argb(30, 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), halo)
            // Gentle bottom scrim keeps white track text legible on white walls.
            val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, height * 0.46f, 0f, height.toFloat(),
                    Color.TRANSPARENT,
                    Color.argb(46, 0, 0, 0),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, height * 0.46f, width.toFloat(), height.toFloat(), scrim)
        }
        drawLevelFill(canvas, context, width, height, fillLevel, fillVertical, accent)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            // 0.9dp hairline, white at ~30% so it reads as a glass edge.
            strokeWidth = (context.resources.displayMetrics.density * 0.9f).coerceAtLeast(1f)
            color = Color.argb(76, 255, 255, 255)
        }
        val inset = rim.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius, radius, rim
        )
        return bitmap
    }

    /**
     * Accent-tinted level fill with a bright edge, so the current value stays
     * readable while white icons keep their contrast on top of it.
     */
    private fun drawLevelFill(
        canvas: Canvas,
        context: Context,
        width: Int,
        height: Int,
        level: Float,
        vertical: Boolean,
        accent: Int
    ) {
        if (level < 0f) return
        val fraction = level.coerceIn(0f, 1f)
        if (fraction < 0.001f) return
        val red = Color.red(accent)
        val green = Color.green(accent)
        val blue = Color.blue(accent)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
        }
        val edgeWidth = dp(context, 2).toFloat()
        if (vertical) {
            val top = height * (1f - fraction)
            paint.shader = LinearGradient(
                0f, top, 0f, height.toFloat(),
                Color.argb(96, red, green, blue),
                Color.argb(156, red, green, blue),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
            canvas.drawRect(0f, top, width.toFloat(), top + edgeWidth, edge)
        } else {
            val right = width * fraction
            paint.shader = LinearGradient(
                0f, 0f, right, 0f,
                Color.argb(96, red, green, blue),
                Color.argb(156, red, green, blue),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, right, height.toFloat(), paint)
            canvas.drawRect(
                (right - edgeWidth).coerceAtLeast(0f), 0f,
                right, height.toFloat(), edge
            )
        }
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}

/**
 * Keeps volume/brightness cards in step with the hardware keys and the Quick
 * Settings sliders. It only wakes on an actual settings write, never polls.
 */
object WidgetLevelObserver {
    @Volatile private var started = false

    fun ensure(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                GlassWidgets.refreshLevelWidgets(app)
            }
        }
        runCatching {
            val resolver = app.contentResolver
            resolver.registerContentObserver(
                Settings.System.getUriFor("volume_music"), false, observer
            )
            resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer
            )
        }
    }
}

abstract class GlassWidgetProvider : AppWidgetProvider() {
    protected abstract fun layoutId(context: Context, options: Bundle): Int

    /** 0..1 draws the accent fill over that share of the card; negative disables it. */
    protected open fun fillLevel(context: Context, options: Bundle): Float = -1f

    protected open val fillVertical: Boolean = false

    /** Background pane; the music card swaps in a thicker, milkier glass. */
    protected open fun bindBackground(context: Context, views: RemoteViews, options: Bundle) {
        GlassWidgets.bindCard(
            views, context, options, fillLevel(context, options), fillVertical
        )
    }

    protected abstract fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    )

    protected open fun handleAction(context: Context, action: String, widgetId: Int): Boolean =
        false

    final override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetLevelObserver.ensure(context)
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
    }

    final override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val widgetId = intent.getIntExtra(GlassWidgets.EXTRA_WIDGET_ID, -1)
        if (!handleAction(context, action, widgetId)) return
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, this.javaClass))
            .forEach { render(context, manager, it) }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val options = manager.getAppWidgetOptions(widgetId)
        val accent = AppThemes.current(context).accent
        val layout = layoutId(context, options)
        val views = RemoteViews(context.packageName, layout)
        try {
            bindBackground(context, views, options)
            bind(context, views, widgetId, options, accent)
            manager.updateAppWidget(widgetId, views)
        } catch (error: Exception) {
            // One unsupported action must not leave the card unloadable;
            // fall back to the plain layout rather than the launcher's
            // "Problem loading widget" panel.
            Log.w("GlassWidget", "render $widgetId failed", error)
            runCatching {
                manager.updateAppWidget(widgetId, RemoteViews(context.packageName, layout))
            }
        }
    }
}

/**
 * Shared card for volume and brightness: one white glyph, the whole card split
 * in two tap zones, and an accent fill that shows the current level.
 */
abstract class GlassLevelWidget : GlassWidgetProvider() {
    protected abstract val upAction: String
    protected abstract val downAction: String

    /** Whether the tap zones can actually change the level right now. */
    protected open fun ready(context: Context): Boolean = true

    /** Opens the permission screen when [ready] is false. */
    protected open fun openSetup(context: Context): PendingIntent? = null

    protected abstract fun currentLevel(context: Context): Float

    protected abstract fun adjust(context: Context, up: Boolean): Boolean

    protected open fun iconName(context: Context, level: Float): String = "volume"

    protected open val showsStepIcons: Boolean = false

    /** Glyph shown near the top/bottom edge of the vertical card. */
    protected open fun stepIcons(): Pair<String, String> = "plus" to "minus"

    override fun fillLevel(context: Context, options: Bundle): Float =
        currentLevel(context).coerceIn(0f, 1f)

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        WidgetLevelObserver.ensure(context)
        val level = currentLevel(context).coerceIn(0f, 1f)
        val iconPx = GlassWidgets.dp(context, 24)
        views.setImageViewBitmap(
            R.id.widget_icon,
            GlassWidgets.glyphBitmap(context, iconName(context, level), Color.WHITE, iconPx)
        )
        if (showsStepIcons) {
            val (upGlyph, downGlyph) = stepIcons()
            val stepPx = GlassWidgets.dp(context, 16)
            views.setImageViewBitmap(
                R.id.widget_up_icon,
                GlassWidgets.glyphBitmap(context, upGlyph, Color.argb(235, 255, 255, 255), stepPx)
            )
            views.setImageViewBitmap(
                R.id.widget_down_icon,
                GlassWidgets.glyphBitmap(context, downGlyph, Color.argb(235, 255, 255, 255), stepPx)
            )
        }

        val upPending = if (ready(context)) {
            GlassWidgets.pending(context, javaClass, upAction, widgetId * 16 + 8, widgetId)
        } else {
            openSetup(context)
        }
        val downPending = if (ready(context)) {
            GlassWidgets.pending(context, javaClass, downAction, widgetId * 16 + 9, widgetId)
        } else {
            openSetup(context)
        }
        if (upPending != null) views.setOnClickPendingIntent(R.id.widget_up, upPending)
        if (downPending != null) views.setOnClickPendingIntent(R.id.widget_down, downPending)
    }

    override fun handleAction(context: Context, action: String, widgetId: Int): Boolean {
        val up = when (action) {
            upAction -> true
            downAction -> false
            else -> return false
        }
        if (!ready(context)) {
            return true
        }
        adjust(context, up)
        GlassWidgets.refreshSoon(context, javaClass)
        return true
    }
}

/** Volume card: hardware music stream, speaker glyph reflects the level. */
abstract class GlassVolumeWidget : GlassLevelWidget() {
    override val upAction: String get() = GlassWidgets.ACTION_VOLUME_UP
    override val downAction: String get() = GlassWidgets.ACTION_VOLUME_DOWN

    override fun currentLevel(context: Context): Float {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return 0f
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun iconName(context: Context, level: Float): String = when {
        level <= 0.01f -> "volume-x"
        level <= 0.35f -> "volume-1"
        level <= 0.7f -> "volume"
        else -> "volume-2"
    }

    override fun adjust(context: Context, up: Boolean): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        return true
    }
}

class GlassVolumeVerticalWidget : GlassVolumeWidget() {
    override fun layoutId(context: Context, options: Bundle): Int =
        R.layout.widget_volume_vertical

    override val fillVertical: Boolean = true

    override val showsStepIcons: Boolean = true
}

class GlassVolumeHorizontalWidget : GlassVolumeWidget() {
    override fun layoutId(context: Context, options: Bundle): Int =
        R.layout.widget_volume_horizontal

    override val showsStepIcons: Boolean = true
}

/** Brightness card: writes the system slider, needs the WRITE_SETTINGS grant. */
abstract class GlassBrightnessWidget : GlassLevelWidget() {
    override val upAction: String get() = GlassWidgets.ACTION_BRIGHTNESS_UP
    override val downAction: String get() = GlassWidgets.ACTION_BRIGHTNESS_DOWN

    override fun currentLevel(context: Context): Float {
        val value = runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        }.getOrDefault(128)
        return value.coerceIn(1, 255) / 255f
    }

    override fun iconName(context: Context, level: Float): String = when {
        level <= 0.35f -> "sun-dim"
        level <= 0.7f -> "sun-medium"
        else -> "sun"
    }

    override fun ready(context: Context): Boolean = Settings.System.canWrite(context)

    override fun openSetup(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            SETUP_REQUEST,
            Intent(context, WidgetLaunchActivity::class.java)
                .putExtra(WidgetLaunchActivity.EXTRA_OPEN_WRITE_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun adjust(context: Context, up: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) return false
        val resolver = context.contentResolver
        val current = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        val target = (if (up) current + STEP else current - STEP).coerceIn(1, 255)
        return runCatching {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
        }.isSuccess
    }

    private companion object {
        const val STEP = 24
        const val SETUP_REQUEST = 9090
    }
}

class GlassBrightnessVerticalWidget : GlassBrightnessWidget() {
    override fun layoutId(context: Context, options: Bundle): Int =
        R.layout.widget_brightness_vertical

    override val fillVertical: Boolean = true

    override val showsStepIcons: Boolean = true
}

class GlassBrightnessHorizontalWidget : GlassBrightnessWidget() {
    override fun layoutId(context: Context, options: Bundle): Int =
        R.layout.widget_brightness_horizontal

    override val showsStepIcons: Boolean = true
}

class GlassClockWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_clock

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        val openTarget = GlassWidgets.targetPending(context, widgetId * 16 + 8)
        views.setOnClickPendingIntent(R.id.widget_value, openTarget)
        views.setOnClickPendingIntent(R.id.widget_bg, openTarget)
    }
}

class GlassMusicWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int =
        GlassWidgets.musicLayoutId(GlassWidgets.musicSize(options))

    // The music pane is painted inside bind(), where the album art is known.
    override fun bindBackground(context: Context, views: RemoteViews, options: Bundle) = Unit

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { AppPrefs.clearMusicWidgetPackage(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        val size = GlassWidgets.musicSize(options)

        val noApp = AppPrefs.musicWidgetNoApp(context, widgetId)
        val assigned = AppPrefs.musicWidgetPackage(context, widgetId)
        val now = WidgetMedia.snapshot(context, assigned)
        GlassWidgets.bindMusicCard(views, context, options, now.art)
        val muted = Color.argb(217, 255, 255, 255)
        val assignedLabel = assigned.takeIf { it.isNotEmpty() }?.let {
            WidgetMedia.label(context, it)
        }
        val title = now.title?.takeIf { it.isNotBlank() }
            ?: when {
                now.hasSession || now.isPlaying -> "Đang phát nhạc"
                else -> "Chưa phát nhạc"
            }
        val artist = now.artist?.takeIf { it.isNotBlank() }
            ?: when {
                now.isPlaying || now.hasSession ->
                    now.packageName?.let { WidgetMedia.label(context, it) } ?: "Nhạc đang phát"
                noApp -> "Không mở ứng dụng"
                assignedLabel != null -> "Mở $assignedLabel"
                else -> "Chạm để mở nhạc"
            }
        val titleBase = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 13f
            GlassWidgets.MusicSize.WIDE -> 15f
            GlassWidgets.MusicSize.LARGE -> 20f
        }
        // Shrink long Vietnamese titles instead of cutting them with “…”.
        val titleSp = titleBase * when {
            title.length > 32 -> 0.72f
            title.length > 24 -> 0.84f
            else -> 1f
        }
        val artistBase = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 11f
            GlassWidgets.MusicSize.WIDE -> 12f
            GlassWidgets.MusicSize.LARGE -> 14f
        }
        val artistSp = artistBase * when {
            artist.length > 32 -> 0.8f
            artist.length > 24 -> 0.9f
            else -> 1f
        }
        views.setTextViewText(R.id.widget_title, title)
        views.setTextColor(R.id.widget_title, Color.WHITE)
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
        views.setTextViewText(R.id.widget_artist, artist)
        views.setTextColor(R.id.widget_artist, muted)
        views.setTextViewTextSize(R.id.widget_artist, TypedValue.COMPLEX_UNIT_SP, artistSp)
        // Note: TextView#setSelected is not a @RemotableViewMethod, so a
        // widget cannot start a marquee. Titles shrink and stay static instead;
        // calling setSelected here makes the launcher show a loading error.

        val artDp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 64
            GlassWidgets.MusicSize.WIDE -> 84
            GlassWidgets.MusicSize.LARGE -> 150
        }
        val ring = Color.argb(120, 255, 255, 255)
        views.setImageViewBitmap(
            R.id.widget_art,
            GlassWidgets.circularArtBitmap(context, now.art, GlassWidgets.dp(context, artDp), ring)
        )

        val playCircleDp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 32
            GlassWidgets.MusicSize.WIDE -> 36
            GlassWidgets.MusicSize.LARGE -> 50
        }
        val playIconDp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 15
            GlassWidgets.MusicSize.WIDE -> 17
            GlassWidgets.MusicSize.LARGE -> 22
        }
        val skipIconDp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 18
            GlassWidgets.MusicSize.WIDE -> 20
            GlassWidgets.MusicSize.LARGE -> 26
        }
        views.setImageViewBitmap(
            R.id.widget_play_bg,
            GlassWidgets.circleBitmap(
                context,
                GlassWidgets.dp(context, playCircleDp),
                (accent and 0x00FFFFFF) or 0xE6000000.toInt()
            )
        )
        views.setImageViewBitmap(
            R.id.widget_play,
            GlassWidgets.glyphBitmap(
                context,
                if (now.isPlaying) "pause" else "play",
                Color.WHITE,
                GlassWidgets.dp(context, playIconDp)
            )
        )
        views.setImageViewBitmap(
            R.id.widget_prev,
            GlassWidgets.glyphBitmap(
                context,
                "skip-back",
                Color.WHITE,
                GlassWidgets.dp(context, skipIconDp)
            )
        )
        views.setImageViewBitmap(
            R.id.widget_next,
            GlassWidgets.glyphBitmap(
                context,
                "skip-forward",
                Color.WHITE,
                GlassWidgets.dp(context, skipIconDp)
            )
        )

        views.setOnClickPendingIntent(
            R.id.widget_prev,
            GlassWidgets.pending(
                context, javaClass, GlassWidgets.ACTION_MEDIA_PREV,
                widgetId * 16 + 2, widgetId
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            GlassWidgets.pending(
                context, javaClass, GlassWidgets.ACTION_MEDIA_NEXT,
                widgetId * 16 + 4, widgetId
            )
        )
        // Playing anything → toggle it. Paused → toggle only the assigned app,
        // otherwise "play now" resumes/opens the card's own app.
        val toggleSession = now.hasSession &&
            (now.isPlaying || assigned.isEmpty() || now.packageName == assigned)
        views.setOnClickPendingIntent(
            R.id.widget_play,
            if (toggleSession) {
                GlassWidgets.pending(
                    context, javaClass, GlassWidgets.ACTION_MEDIA_TOGGLE,
                    widgetId * 16 + 3, widgetId
                )
            } else {
                GlassWidgets.musicPlayNowPending(context, widgetId)
            }
        )
        val tap = GlassWidgets.musicTapPending(context, widgetId)
        views.setOnClickPendingIntent(R.id.widget_art, tap)
        views.setOnClickPendingIntent(R.id.widget_title, tap)
        views.setOnClickPendingIntent(R.id.widget_artist, tap)
        views.setOnClickPendingIntent(R.id.widget_root, tap)
    }

    override fun handleAction(context: Context, action: String, widgetId: Int): Boolean {
        if (action != GlassWidgets.ACTION_MEDIA_PREV &&
            action != GlassWidgets.ACTION_MEDIA_TOGGLE &&
            action != GlassWidgets.ACTION_MEDIA_NEXT
        ) {
            return false
        }
        val preferred = if (widgetId >= 0) {
            AppPrefs.musicWidgetPackage(context, widgetId)
        } else {
            AppPrefs.musicWidgetPackage(context)
        }
        WidgetMedia.send(context, action, preferred)
        GlassWidgets.refreshSoon(context, javaClass)
        // The session may settle after the command; refresh once more.
        Handler(Looper.getMainLooper()).postDelayed({
            GlassWidgets.refresh(context, javaClass)
        }, 700L)
        return true
    }
}

