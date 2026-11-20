package com.manh.panelsassistive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
import android.view.KeyEvent
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Home-screen widgets in the same faux-glass language as the in-app panels.
 * All cards share one bitmap-drawn background so every theme also recolours
 * the widgets. Everything is RemoteViews, so third-party launchers work too.
 */
object GlassWidgets {
    const val ACTION_VOLUME_UP = "com.manh.panelsassistive.widget.VOLUME_UP"
    const val ACTION_VOLUME_DOWN = "com.manh.panelsassistive.widget.VOLUME_DOWN"
    const val ACTION_MEDIA_PREV = "com.manh.panelsassistive.widget.MEDIA_PREV"
    const val ACTION_MEDIA_TOGGLE = "com.manh.panelsassistive.widget.MEDIA_TOGGLE"
    const val ACTION_MEDIA_NEXT = "com.manh.panelsassistive.widget.MEDIA_NEXT"

    val providers = listOf(
        GlassBatteryWidget::class.java,
        GlassVolumeWidget::class.java,
        GlassClockWidget::class.java,
        GlassMusicWidget::class.java
    )

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        providers.forEach { provider ->
            val component = android.content.ComponentName(context, provider)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return@forEach
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(component)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }

    fun sizeDp(options: Bundle): Pair<Int, Int> {
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        return (if (width > 0) width else 180) to (if (height > 0) height else 110)
    }

    fun pending(context: Context, cls: Class<*>, action: String, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, cls).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun bindCard(views: RemoteViews, context: Context, options: Bundle, accent: Int) {
        val (widthDp, heightDp) = sizeDp(options)
        val theme = AppThemes.current(context)
        views.setImageViewBitmap(
            R.id.widget_bg,
            cardBitmap(
                context,
                dp(context, widthDp).coerceIn(dp(context, 40), dp(context, 900)),
                dp(context, heightDp).coerceIn(dp(context, 40), dp(context, 900)),
                accent,
                theme.panel
            )
        )
    }

    fun barBitmap(context: Context, widthPx: Int, heightPx: Int, progress: Float, accent: Int): Bitmap {
        val width = widthPx.coerceAtLeast(dp(context, 24))
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = height / 2f
        paint.color = Color.argb(46, 255, 255, 255)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
        val filled = (width * progress.coerceIn(0f, 1f)).coerceAtLeast(height.toFloat())
        paint.color = accent
        canvas.drawRoundRect(RectF(0f, 0f, filled, height.toFloat()), radius, radius, paint)
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

    private fun cardBitmap(
        context: Context,
        width: Int,
        height: Int,
        accent: Int,
        panel: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = dp(context, 24).toFloat().coerceAtMost(width / 2f)
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(path)
        val solid = (panel and 0x00FFFFFF) or 0xE4000000
        val deep = (darken(panel, 0.55f) and 0x00FFFFFF) or 0xE4000000
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                solid, deep, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        // Soft accent glow in the top-left corner, like the reference glass cards.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (accent and 0x00FFFFFF) or 0x30000000
        }
        canvas.drawCircle(width * 0.16f, height * 0.08f, height * 1.05f, glow)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1).toFloat().coerceAtLeast(1f)
            color = (accent and 0x00FFFFFF) or 0x5A000000
        }
        val inset = rim.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius, radius, rim
        )
        return bitmap
    }

    private fun darken(color: Int, factor: Float): Int =
        Color.rgb(
            (Color.red(color) * factor).roundToInt().coerceIn(0, 255),
            (Color.green(color) * factor).roundToInt().coerceIn(0, 255),
            (Color.blue(color) * factor).roundToInt().coerceIn(0, 255)
        )

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}

abstract class GlassWidgetProvider : AppWidgetProvider() {
    protected abstract fun layoutId(context: Context, options: Bundle): Int

    protected abstract fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    )

    protected open fun handleAction(context: Context, action: String): Boolean = false

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
        if (!handleAction(context, action)) return
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(android.content.ComponentName(context, this.javaClass))
            .forEach { render(context, manager, it) }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val options = manager.getAppWidgetOptions(widgetId)
        val accent = AppThemes.current(context).accent
        val views = RemoteViews(context.packageName, layoutId(context, options))
        GlassWidgets.bindCard(views, context, options, accent)
        bind(context, views, widgetId, options, accent)
        manager.updateAppWidget(widgetId, views)
    }
}

class GlassBatteryWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_battery

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = when (status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
        views.setTextViewText(R.id.widget_value, "$percent%")
        views.setTextViewText(R.id.widget_label, if (charging) "PIN · ĐANG SẠC" else "PIN")
        val (widthDp, _) = GlassWidgets.sizeDp(options)
        val barWidth = dp(context, (widthDp - 62).coerceAtLeast(40))
        views.setImageViewBitmap(
            R.id.widget_bar,
            GlassWidgets.barBitmap(context, barWidth, dp(context, 6), percent / 100f, accent)
        )
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}

class GlassVolumeWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int {
        val (widthDp, heightDp) = GlassWidgets.sizeDp(options)
        return if (heightDp > widthDp) {
            R.layout.widget_volume_vertical
        } else {
            R.layout.widget_volume_horizontal
        }
    }

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        views.setTextViewText(R.id.widget_value, "${(current * 100f / max).roundToInt()}%")
        views.setTextViewText(R.id.widget_label, "ÂM LƯỢNG")
        views.setOnClickPendingIntent(
            R.id.widget_plus,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_VOLUME_UP, widgetId * 16)
        )
        views.setOnClickPendingIntent(
            R.id.widget_minus,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_VOLUME_DOWN, widgetId * 16 + 1)
        )
    }

    override fun handleAction(context: Context, action: String): Boolean {
        val direction = when (action) {
            GlassWidgets.ACTION_VOLUME_UP -> AudioManager.ADJUST_RAISE
            GlassWidgets.ACTION_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return false
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }
}

class GlassClockWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_clock

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) = Unit
}

class GlassMusicWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_music

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        views.setImageViewBitmap(
            R.id.widget_play_bg,
            GlassWidgets.circleBitmap(context, dp(context, 44), accent)
        )
        views.setOnClickPendingIntent(
            R.id.widget_prev,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_MEDIA_PREV, widgetId * 16 + 2)
        )
        views.setOnClickPendingIntent(
            R.id.widget_play,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_MEDIA_TOGGLE, widgetId * 16 + 3)
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_MEDIA_NEXT, widgetId * 16 + 4)
        )
    }

    override fun handleAction(context: Context, action: String): Boolean {
        val code = when (action) {
            GlassWidgets.ACTION_MEDIA_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            GlassWidgets.ACTION_MEDIA_TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            GlassWidgets.ACTION_MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return false
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return false
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}
