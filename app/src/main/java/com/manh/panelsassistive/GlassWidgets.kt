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
import android.view.KeyEvent
import android.widget.RemoteViews
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

    fun pending(context: Context, cls: Class<*>, action: String, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, cls).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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

    private fun cardBitmap(
        context: Context,
        width: Int,
        height: Int,
        bolt: Boolean,
        fillLevel: Float,
        fillVertical: Boolean
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
                Color.argb(46, 238, 246, 255),
                Color.argb(18, 238, 246, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        if (bolt) drawBoltWatermark(canvas, width, height)
        drawLevelFill(canvas, context, width, height, fillLevel, fillVertical)
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
                intArrayOf(Color.argb(236, 255, 255, 255), Color.argb(206, 255, 255, 255)),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        } else {
            val right = width * fraction
            paint.shader = LinearGradient(
                (right - soft).coerceAtLeast(0f), 0f, right, 0f,
                intArrayOf(Color.argb(200, 255, 255, 255), Color.argb(236, 255, 255, 255)),
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
        GlassWidgets.bindCard(
            views, context, options, boltWatermark,
            fillLevel(context, options), fillVertical
        )
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
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_music

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val playing = audio.isMusicActive
        views.setImageViewBitmap(
            R.id.widget_play_bg,
            GlassWidgets.circleBitmap(context, GlassWidgets.dp(context, 46), accent)
        )
        val iconPx = GlassWidgets.dp(context, 20)
        views.setImageViewBitmap(
            R.id.widget_play,
            GlassWidgets.tintedBitmap(
                context,
                if (playing) R.drawable.ic_wg_pause else R.drawable.ic_wg_play,
                accent,
                iconPx
            )
        )
        views.setImageViewBitmap(
            R.id.widget_prev,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_prev, accent, iconPx)
        )
        views.setImageViewBitmap(
            R.id.widget_next,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_next, accent, iconPx)
        )
        views.setImageViewBitmap(
            R.id.widget_minus,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_minus, accent, iconPx)
        )
        views.setImageViewBitmap(
            R.id.widget_plus,
            GlassWidgets.tintedBitmap(context, R.drawable.ic_wg_plus, accent, iconPx)
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
        views.setOnClickPendingIntent(
            R.id.widget_minus,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_VOLUME_DOWN, widgetId * 16 + 10)
        )
        views.setOnClickPendingIntent(
            R.id.widget_plus,
            GlassWidgets.pending(context, javaClass, GlassWidgets.ACTION_VOLUME_UP, widgetId * 16 + 11)
        )
        val openTarget = if (AppPrefs.musicWidgetOpensSource(context)) {
            GlassWidgets.mediaSourcePending(context, widgetId * 16 + 9)
        } else {
            GlassWidgets.targetPending(context, widgetId * 16 + 9)
        }
        views.setOnClickPendingIntent(R.id.widget_bg, openTarget)
    }

    override fun handleAction(context: Context, action: String): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (action) {
            GlassWidgets.ACTION_VOLUME_UP -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                GlassWidgets.refreshSoon(context, javaClass)
                return true
            }
            GlassWidgets.ACTION_VOLUME_DOWN -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                GlassWidgets.refreshSoon(context, javaClass)
                return true
            }
        }
        val code = when (action) {
            GlassWidgets.ACTION_MEDIA_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            GlassWidgets.ACTION_MEDIA_TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            GlassWidgets.ACTION_MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return false
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        // Play/pause state settles asynchronously; refresh once it has.
        Handler(Looper.getMainLooper()).postDelayed({
            GlassWidgets.refresh(context, javaClass)
        }, 450L)
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
