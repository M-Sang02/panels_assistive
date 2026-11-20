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
import android.view.KeyEvent
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Home-screen widgets in the same see-through glass language as the reference
 * mockup: a quiet white pane, thin rim and a faint watermark. The active theme
 * only tints the buttons (volume, music) so the cards stay neutral everywhere.
 * Everything is RemoteViews, so third-party launchers can host them too.
 */
object GlassWidgets {
    const val ACTION_VOLUME_UP = "com.manh.panelsassistive.widget.VOLUME_UP"
    const val ACTION_VOLUME_DOWN = "com.manh.panelsassistive.widget.VOLUME_DOWN"
    const val ACTION_MEDIA_PREV = "com.manh.panelsassistive.widget.MEDIA_PREV"
    const val ACTION_MEDIA_TOGGLE = "com.manh.panelsassistive.widget.MEDIA_TOGGLE"
    const val ACTION_MEDIA_NEXT = "com.manh.panelsassistive.widget.MEDIA_NEXT"

    val providers = listOf(
        GlassBatteryWidget::class.java,
        GlassVolumeVerticalWidget::class.java,
        GlassVolumeHorizontalWidget::class.java,
        GlassClockWidget::class.java,
        GlassMusicWidget::class.java
    )

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        providers.forEach { provider ->
            val component = ComponentName(context, provider)
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

    fun appPending(context: Context, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Tapping the music card opens whichever app is playing right now. */
    fun mediaSourcePending(context: Context, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent(context, WidgetLaunchActivity::class.java)
                .putExtra(WidgetLaunchActivity.EXTRA_OPEN_MEDIA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun bindCard(
        views: RemoteViews,
        context: Context,
        options: Bundle,
        boltWatermark: Boolean = false
    ) {
        val (widthDp, heightDp) = sizeDp(options)
        views.setImageViewBitmap(
            R.id.widget_bg,
            cardBitmap(
                context,
                dp(context, widthDp).coerceIn(dp(context, 40), dp(context, 900)),
                dp(context, heightDp).coerceIn(dp(context, 40), dp(context, 900)),
                boltWatermark
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

    private fun cardBitmap(context: Context, width: Int, height: Int, bolt: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = dp(context, 26).toFloat().coerceAtMost(minOf(width, height) / 2f)
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
                Color.argb(46, 238, 246, 255),
                Color.argb(18, 238, 246, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        if (bolt) drawBoltWatermark(canvas, width, height)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1).toFloat().coerceAtLeast(1f)
            color = Color.argb(96, 255, 255, 255)
        }
        val inset = rim.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius, radius, rim
        )
        return bitmap
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}

abstract class GlassWidgetProvider : AppWidgetProvider() {
    protected abstract fun layoutId(context: Context, options: Bundle): Int

    protected open val boltWatermark: Boolean = false

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
        manager.getAppWidgetIds(ComponentName(context, this.javaClass))
            .forEach { render(context, manager, it) }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val options = manager.getAppWidgetOptions(widgetId)
        val accent = AppThemes.current(context).accent
        val views = RemoteViews(context.packageName, layoutId(context, options))
        GlassWidgets.bindCard(views, context, options, boltWatermark)
        bind(context, views, widgetId, options, accent)
        manager.updateAppWidget(widgetId, views)
    }
}

class GlassBatteryWidget : GlassWidgetProvider() {    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_battery

    override val boltWatermark: Boolean = true

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
        val label = when {
            charging -> "ĐANG SẠC"
            percent <= 20 -> "PIN YẾU"
            else -> "PIN"
        }
        views.setTextViewText(R.id.widget_value, "$percent%")
        views.setTextViewText(R.id.widget_label, label)
        views.setOnClickPendingIntent(
            R.id.widget_value,
            GlassWidgets.appPending(context, widgetId * 16 + 5)
        )
        views.setOnClickPendingIntent(
            R.id.widget_bg,
            GlassWidgets.appPending(context, widgetId * 16 + 6)
        )
    }
}

abstract class GlassVolumeWidget : GlassWidgetProvider() {
    protected fun bindVolume(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        accent: Int
    ) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        views.setTextViewText(R.id.widget_value, "${(current * 100f / max).roundToInt()}%")
        views.setTextViewText(R.id.widget_label, "ÂM LƯỢNG")
        val iconPx = dp(context, 18)
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
        views.setOnClickPendingIntent(
            R.id.widget_value,
            GlassWidgets.appPending(context, widgetId * 16 + 7)
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}

class GlassVolumeVerticalWidget : GlassVolumeWidget() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_volume_vertical

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
        views.setOnClickPendingIntent(
            R.id.widget_value,
            GlassWidgets.appPending(context, widgetId * 16 + 8)
        )
    }
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
        val iconPx = dp(context, 20)
        views.setImageViewBitmap(
            R.id.widget_prev,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_prev, accent, iconPx)
        )
        views.setImageViewBitmap(
            R.id.widget_next,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_next, accent, iconPx)
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
        val openSource = GlassWidgets.mediaSourcePending(context, widgetId * 16 + 9)
        views.setOnClickPendingIntent(R.id.widget_bg, openSource)
        views.setOnClickPendingIntent(R.id.widget_title, openSource)
        views.setOnClickPendingIntent(R.id.widget_label, openSource)
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
