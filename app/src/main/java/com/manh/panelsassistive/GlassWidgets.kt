package com.manh.panelsassistive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Home-screen widgets in the same see-through glass language as the reference
 * mockup: a quiet white pane, thin rim and a faint watermark. The active theme
 * only tints the buttons (volume, music) so the cards stay neutral everywhere.
 * Volume and battery cards fill with white in proportion to their value.
 * Everything is RemoteViews, so third-party launchers can host them too.
 */
object GlassWidgets {
    const val ACTION_VOLUME_UP = "com.manh.panelsassistive.widget.VOLUME_UP"
    const val ACTION_VOLUME_DOWN = "com.manh.panelsassistive.widget.VOLUME_DOWN"
    const val ACTION_MEDIA_PREV = "com.manh.panelsassistive.widget.MEDIA_PREV"
    const val ACTION_MEDIA_TOGGLE = "com.manh.panelsassistive.widget.MEDIA_TOGGLE"
    const val ACTION_MEDIA_NEXT = "com.manh.panelsassistive.widget.MEDIA_NEXT"
    const val EXTRA_WIDGET_ID = "com.manh.panelsassistive.widget.WIDGET_ID"

    val providers = listOf(
        GlassBatteryWidget::class.java,
        GlassVolumeVerticalWidget::class.java,
        GlassVolumeHorizontalWidget::class.java,
        GlassClockWidget::class.java,
        GlassMusicWidget::class.java
    )

    fun refreshAll(context: Context) {
        providers.forEach { refresh(context, it) }
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
            width >= 220 && height >= 200 -> MusicSize.LARGE
            width >= 200 -> MusicSize.WIDE
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
        boltWatermark: Boolean = false,
        fillLevel: Float = -1f,
        fillVertical: Boolean = true
    ) {
        val (widthDp, heightDp) = sizeDp(options)
        views.setImageViewBitmap(
            R.id.widget_bg,
            cardBitmap(
                context,
                dp(context, widthDp).coerceIn(dp(context, 40), dp(context, 900)),
                dp(context, heightDp).coerceIn(dp(context, 40), dp(context, 900)),
                boltWatermark,
                fillLevel,
                fillVertical
            )
        )
    }

    /** Stronger, milkier glass used by the album-art music card. */
    fun bindMusicCard(views: RemoteViews, context: Context, options: Bundle) {
        val (widthDp, heightDp) = sizeDp(options)
        views.setImageViewBitmap(
            R.id.widget_bg,
            cardBitmap(
                context,
                dp(context, widthDp).coerceIn(dp(context, 40), dp(context, 900)),
                dp(context, heightDp).coerceIn(dp(context, 40), dp(context, 900)),
                bolt = false,
                fillLevel = -1f,
                fillVertical = true,
                glassTopAlpha = 140,
                glassBottomAlpha = 82,
                rimAlpha = 150
            )
        )
    }

    /** Draws an icon drawable tinted with the theme accent, for RemoteViews buttons. */
    fun tintedBitmap(context: Context, resId: Int, color: Int, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = context.getDrawable(resId) ?: return bitmap
        drawable.setTint(color)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    fun circleBitmap(context: Context, diameterPx: Int, color: Int): Bitmap {
        val size = diameterPx.coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bitmap
    }

    /** Center-crops the album art into a rounded rectangle for RemoteViews. */
    fun roundedBitmap(
        source: Bitmap,
        widthPx: Int,
        heightPx: Int,
        radiusPx: Int
    ): Bitmap {
        val width = widthPx.coerceAtLeast(2)
        val height = heightPx.coerceAtLeast(2)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radiusPx.toFloat(), radiusPx.toFloat(), Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    /** Frosted tile with a soft Lucide music glyph while no album art exists. */
    fun artPlaceholderBitmap(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        radiusPx: Int,
        iconColor: Int
    ): Bitmap {
        val width = widthPx.coerceAtLeast(2)
        val height = heightPx.coerceAtLeast(2)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radiusPx.toFloat(), radiusPx.toFloat(), Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.argb(112, 255, 255, 255),
                Color.argb(44, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1).toFloat().coerceAtLeast(1f)
            color = Color.argb(90, 255, 255, 255)
        }
        val inset = rim.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radiusPx.toFloat(), radiusPx.toFloat(), rim
        )
        val glyph = LucideGlyphs.glyph(context, "music")
        if (glyph.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = LucideGlyphs.typeface(context)
                textAlign = Paint.Align.CENTER
                textSize = minOf(width, height) * 0.42f
                color = (iconColor and 0x00FFFFFF) or 0xB8000000.toInt()
            }
            val metrics = paint.fontMetrics
            val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(glyph, width / 2f, baseline, paint)
        }
        return output
    }

    private fun cardBitmap(
        context: Context,
        width: Int,
        height: Int,
        bolt: Boolean,
        fillLevel: Float,
        fillVertical: Boolean,
        glassTopAlpha: Int = 46,
        glassBottomAlpha: Int = 18,
        rimAlpha: Int = 96
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = dp(context, 34).toFloat().coerceAtMost(minOf(width, height) / 2f)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius, radius, Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.argb(glassTopAlpha, 238, 246, 255),
                Color.argb(glassBottomAlpha, 238, 246, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        if (glassTopAlpha > 80) {
            val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height * 0.62f,
                    Color.argb(52, 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height * 0.62f, sheen)
        }
        if (bolt) drawBoltWatermark(canvas, width, height)
        drawLevelFill(canvas, context, width, height, fillLevel, fillVertical)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1).toFloat().coerceAtLeast(1f)
            color = Color.argb(rimAlpha, 255, 255, 255)
        }
        val inset = rim.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius, radius, rim
        )
        return bitmap
    }

    /** White fill that grows with the value: full white at 100%, half at 50%. */
    private fun drawLevelFill(
        canvas: Canvas,
        context: Context,
        width: Int,
        height: Int,
        level: Float,
        vertical: Boolean
    ) {
        if (level < 0f) return
        val fraction = level.coerceIn(0f, 1f)
        if (fraction < 0.001f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val soft = dp(context, 14).toFloat()
        if (vertical) {
            val top = height * (1f - fraction)
            paint.shader = LinearGradient(
                0f, top, 0f, height.toFloat(),
                Color.argb(236, 255, 255, 255),
                Color.argb(206, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        } else {
            val right = width * fraction
            paint.shader = LinearGradient(
                (right - soft).coerceAtLeast(0f), 0f, right, 0f,
                Color.argb(200, 255, 255, 255),
                Color.argb(236, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, right, height.toFloat(), paint)
        }
    }

    private fun drawBoltWatermark(canvas: Canvas, width: Int, height: Int) {
        val size = minOf(width, height) * 0.66f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(124, 156, 198)
            style = Paint.Style.FILL
        }
        val layers = floatArrayOf(1f, 1.2f, 1.42f)
        val alphas = intArrayOf(72, 52, 34)
        layers.forEachIndexed { index, layer ->
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            val scale = size / 24f * layer
            canvas.scale(scale, scale)
            canvas.translate(-12f, -12f)
            paint.alpha = alphas[index]
            canvas.drawPath(boltPath(), paint)
            canvas.restore()
        }
    }

    private fun boltPath(): Path = Path().apply {
        moveTo(13.2f, 2f)
        lineTo(4.6f, 13.4f)
        lineTo(10.6f, 13.4f)
        lineTo(9.2f, 22f)
        lineTo(19.4f, 10.4f)
        lineTo(13.4f, 10.4f)
        close()
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}

abstract class GlassWidgetProvider : AppWidgetProvider() {
    protected abstract fun layoutId(context: Context, options: Bundle): Int

    protected open val boltWatermark: Boolean = false

    /** 0..1 draws a white fill over that share of the card; negative disables it. */
    protected open fun fillLevel(context: Context, options: Bundle): Float = -1f

    protected open val fillVertical: Boolean = false

    /** Background pane; the music card swaps in a thicker, milkier glass. */
    protected open fun bindBackground(context: Context, views: RemoteViews, options: Bundle) {
        GlassWidgets.bindCard(
            views, context, options, boltWatermark,
            fillLevel(context, options), fillVertical
        )
    }

    protected abstract fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    )

    protected open fun handleAction(context: Context, action: String): Boolean = false

    /** Overload for providers that need the widget instance (music assignment). */
    protected open fun handleAction(context: Context, action: String, widgetId: Int): Boolean =
        handleAction(context, action)

    final override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
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
        val views = RemoteViews(context.packageName, layoutId(context, options))
        bindBackground(context, views, options)
        bind(context, views, widgetId, options, accent)
        manager.updateAppWidget(widgetId, views)
    }
}

class GlassBatteryWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_battery

    override val boltWatermark: Boolean = true

    override val fillVertical: Boolean = true

    override fun fillLevel(context: Context, options: Bundle): Float {
        val (percent, _) = batteryState(context)
        return percent / 100f
    }

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        val (percent, charging) = batteryState(context)
        val label = when {
            charging -> "ĐANG SẠC"
            percent <= 20 -> "PIN YẾU"
            else -> "PIN"
        }
        val textColor = AppPrefs.widgetTextColor(context)
        views.setTextColor(R.id.widget_value, textColor)
        views.setTextColor(R.id.widget_label, (textColor and 0x00FFFFFF) or 0xCC000000.toInt())
        views.setTextViewText(R.id.widget_value, "$percent%")
        views.setTextViewText(R.id.widget_label, label)
        views.setOnClickPendingIntent(
            R.id.widget_value,
            GlassWidgets.targetPending(context, widgetId * 16 + 5)
        )
        views.setOnClickPendingIntent(
            R.id.widget_bg,
            GlassWidgets.targetPending(context, widgetId * 16 + 6)
        )
    }

    private fun batteryState(context: Context): Pair<Int, Boolean> {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = when (status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
        return percent to charging
    }
}

abstract class GlassVolumeWidget : GlassWidgetProvider() {
    override fun fillLevel(context: Context, options: Bundle): Float {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return current.toFloat() / max
    }

    protected fun bindVolume(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        accent: Int
    ) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val textColor = AppPrefs.widgetTextColor(context)
        views.setTextColor(R.id.widget_value, textColor)
        views.setTextViewText(R.id.widget_value, "${(current * 100f / max).roundToInt()}%")
        views.setImageViewBitmap(
            R.id.widget_label,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_volume, textColor, GlassWidgets.dp(context, 14))
        )
        val iconPx = GlassWidgets.dp(context, 18)
        views.setImageViewBitmap(
            R.id.widget_plus,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_plus, accent, iconPx)
        )
        views.setImageViewBitmap(
            R.id.widget_minus,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_minus, accent, iconPx)
        )
        views.setOnClickPendingIntent(
            R.id.widget_plus,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_VOLUME_UP, widgetId * 16)
        )
        views.setOnClickPendingIntent(
            R.id.widget_minus,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_VOLUME_DOWN, widgetId * 16 + 1)
        )
        val openTarget = GlassWidgets.targetPending(context, widgetId * 16 + 7)
        views.setOnClickPendingIntent(R.id.widget_value, openTarget)
        views.setOnClickPendingIntent(R.id.widget_bg, openTarget)
    }

    override fun handleAction(context: Context, action: String): Boolean {
        val direction = when (action) {
            GlassWidgets.ACTION_VOLUME_UP -> AudioManager.ADJUST_RAISE
            GlassWidgets.ACTION_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return false
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        GlassWidgets.refreshSoon(context, javaClass)
        return true
    }
}

class GlassVolumeVerticalWidget : GlassVolumeWidget() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_volume_vertical

    override val fillVertical: Boolean = true

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) = bindVolume(context, views, widgetId, accent)
}

class GlassVolumeHorizontalWidget : GlassVolumeWidget() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_volume_horizontal

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) = bindVolume(context, views, widgetId, accent)
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
        val textColor = AppPrefs.widgetTextColor(context)
        views.setTextColor(R.id.widget_value, textColor)
        views.setTextColor(R.id.widget_label, (textColor and 0x00FFFFFF) or 0xCC000000.toInt())
        val openTarget = GlassWidgets.targetPending(context, widgetId * 16 + 8)
        views.setOnClickPendingIntent(R.id.widget_value, openTarget)
        views.setOnClickPendingIntent(R.id.widget_bg, openTarget)
    }
}

class GlassMusicWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int =
        GlassWidgets.musicLayoutId(GlassWidgets.musicSize(options))

    override fun bindBackground(context: Context, views: RemoteViews, options: Bundle) =
        GlassWidgets.bindMusicCard(views, context, options)

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

        val assigned = AppPrefs.musicWidgetPackage(context, widgetId)
        val now = WidgetMedia.snapshot(context, assigned)
        val textColor = AppPrefs.widgetTextColor(context)
        val muted = (textColor and 0x00FFFFFF) or 0xCC000000.toInt()
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
                assignedLabel != null -> "Chạm để mở $assignedLabel"
                else -> "Chạm để mở ứng dụng nhạc"
            }
        val titleSp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 14f
            GlassWidgets.MusicSize.WIDE -> 15f
            GlassWidgets.MusicSize.LARGE -> 19f
        }
        val artistSp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 11f
            GlassWidgets.MusicSize.WIDE -> 12f
            GlassWidgets.MusicSize.LARGE -> 14f
        }
        views.setTextViewText(R.id.widget_title, title)
        views.setTextColor(R.id.widget_title, textColor)
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
        views.setTextViewText(R.id.widget_artist, artist)
        views.setTextColor(R.id.widget_artist, muted)
        views.setTextViewTextSize(R.id.widget_artist, TypedValue.COMPLEX_UNIT_SP, artistSp)

        val artDp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 56
            GlassWidgets.MusicSize.WIDE -> 88
            GlassWidgets.MusicSize.LARGE -> 132
        }
        val artRadiusDp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 16
            GlassWidgets.MusicSize.WIDE -> 18
            GlassWidgets.MusicSize.LARGE -> 24
        }
        val artPx = GlassWidgets.dp(context, artDp)
        val artRadius = GlassWidgets.dp(context, artRadiusDp)
        val art = now.art?.let { GlassWidgets.roundedBitmap(it, artPx, artPx, artRadius) }
            ?: GlassWidgets.artPlaceholderBitmap(context, artPx, artPx, artRadius, textColor)
        views.setImageViewBitmap(R.id.widget_art, art)

        val playCircleDp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 34
            GlassWidgets.MusicSize.WIDE -> 38
            GlassWidgets.MusicSize.LARGE -> 54
        }
        val playIconDp = when (size) {
            GlassWidgets.MusicSize.COMPACT -> 16
            GlassWidgets.MusicSize.WIDE -> 18
            GlassWidgets.MusicSize.LARGE -> 24
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
                GlassWidgets.dp(context, playCircleDp) * 2,
                accent
            )
        )
        views.setImageViewBitmap(
            R.id.widget_play,
            GlassWidgets.tintedBitmap(
                context,
                if (now.isPlaying) R.drawable.ic_wg_pause else R.drawable.ic_wg_play,
                Color.WHITE,
                GlassWidgets.dp(context, playIconDp) * 2
            )
        )
        views.setImageViewBitmap(
            R.id.widget_prev,
            GlassWidgets.tintedBitmap(
                context,
                R.drawable.ic_wg_prev,
                textColor,
                GlassWidgets.dp(context, skipIconDp) * 2
            )
        )
        views.setImageViewBitmap(
            R.id.widget_next,
            GlassWidgets.tintedBitmap(
                context,
                R.drawable.ic_wg_next,
                textColor,
                GlassWidgets.dp(context, skipIconDp) * 2
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

/** Few manifest-safe battery broadcasts keep the card fresh without polling. */
class BatteryWidgetReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, GlassBatteryWidget::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return
        context.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(component)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        )
    }
}
