package com.manh.panelsassistive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.max

/**
 * Second, independent music widget. The album art becomes the card itself
 * under a frosted scrim: title and artist sit on top, a compact
 * Previous / Play / Next cluster sits at the bottom. It shares the app
 * assignment, "Play music now" and MediaSession paths with
 * [GlassMusicWidget] but has its own provider, layout and drawables.
 */
class GlassMusicCoverWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int =
        R.layout.widget_music_cover

    // The cover is painted inside bind(), where the album art is known.
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
        val (widthDp, heightDp) = GlassWidgets.sizeDp(options)
        val noApp = AppPrefs.musicWidgetNoApp(context, widgetId)
        val assigned = AppPrefs.musicWidgetPackage(context, widgetId)
        val now = WidgetMedia.snapshot(context, assigned)
        val idle = !now.hasSession && !now.isPlaying

        val width = GlassWidgets.dp(context, widthDp.coerceIn(64, 420))
        val height = GlassWidgets.dp(context, heightDp.coerceIn(64, 420))
        views.setImageViewBitmap(
            R.id.cover_bg,
            coverBitmap(context, width, height, now.art, accent)
        )

        val assignedLabel = assigned.takeIf { it.isNotEmpty() }?.let {
            WidgetMedia.label(context, it)
        }
        val title = now.title?.takeIf { it.isNotBlank() }
            ?: if (now.isPlaying || now.hasSession) "Đang phát nhạc" else "Chưa phát nhạc"
        val artist = now.artist?.takeIf { it.isNotBlank() }
            ?: when {
                now.isPlaying || now.hasSession ->
                    now.packageName?.let { WidgetMedia.label(context, it) } ?: "Nhạc đang phát"
                noApp -> "Không mở ứng dụng"
                assignedLabel != null -> "Chạm để mở $assignedLabel"
                else -> "Chạm để mở ứng dụng nhạc"
            }
        views.setTextViewText(R.id.cover_title, title)
        views.setTextViewText(R.id.cover_artist, artist)
        val wide = widthDp >= 230 && widthDp >= heightDp * 3 / 2
        val big = widthDp >= 250 && heightDp >= 210
        views.setTextViewTextSize(
            R.id.cover_title,
            TypedValue.COMPLEX_UNIT_SP,
            when {
                big -> 19f
                wide -> 17f
                else -> 15.5f
            }
        )
        views.setTextViewTextSize(
            R.id.cover_artist,
            TypedValue.COMPLEX_UNIT_SP,
            when {
                big -> 14f
                wide -> 13f
                else -> 12f
            }
        )

        val glyphAlpha = if (idle) 178 else 245
        views.setImageViewBitmap(
            R.id.cover_prev,
            sideCircleBitmap(context, "skip-back", glyphAlpha)
        )
        views.setImageViewBitmap(
            R.id.cover_next,
            sideCircleBitmap(context, "skip-forward", glyphAlpha)
        )
        val playAlpha = if (idle) 0xB3 else 0xE6
        views.setImageViewBitmap(
            R.id.cover_play_bg,
            GlassWidgets.circleBitmap(
                context,
                GlassWidgets.dp(context, 44),
                (accent and 0x00FFFFFF) or (playAlpha shl 24)
            )
        )
        views.setImageViewBitmap(
            R.id.cover_play,
            GlassWidgets.glyphBitmap(
                context,
                if (now.isPlaying) "pause" else "play",
                Color.WHITE,
                GlassWidgets.dp(context, if (big) 24 else 20)
            )
        )

        views.setOnClickPendingIntent(
            R.id.cover_prev,
            GlassWidgets.pending(
                context, javaClass, GlassWidgets.ACTION_MEDIA_PREV, widgetId * 16 + 2, widgetId
            )
        )
        views.setOnClickPendingIntent(
            R.id.cover_next,
            GlassWidgets.pending(
                context, javaClass, GlassWidgets.ACTION_MEDIA_NEXT, widgetId * 16 + 4, widgetId
            )
        )
        // Playing anything → toggle it. Paused → toggle only the assigned app,
        // otherwise "play now" resumes/opens the card's own app.
        val toggleSession = now.hasSession &&
            (now.isPlaying || assigned.isEmpty() || now.packageName == assigned)
        views.setOnClickPendingIntent(
            R.id.cover_play,
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
        views.setOnClickPendingIntent(R.id.cover_bg, tap)
        views.setOnClickPendingIntent(R.id.cover_title, tap)
        views.setOnClickPendingIntent(R.id.cover_artist, tap)
        views.setOnClickPendingIntent(R.id.cover_root, tap)
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
        // Keep both music cards in step with the session.
        GlassWidgets.refreshMusicSoon(context)
        Handler(Looper.getMainLooper()).postDelayed({
            GlassWidgets.refreshMusicWidgets(context)
        }, 700L)
        return true
    }

    /** Frosted circle with a transport glyph, drawn at a fixed 34dp size. */
    private fun sideCircleBitmap(context: Context, glyphName: String, glyphAlpha: Int): Bitmap {
        val size = GlassWidgets.dp(context, 34).coerceAtLeast(12)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 255, 255, 255)
        }
        canvas.drawCircle(centre, centre, centre, fill)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = GlassWidgets.dp(context, 1).toFloat().coerceAtLeast(1f)
            color = Color.argb(77, 255, 255, 255)
        }
        canvas.drawCircle(centre, centre, centre - ring.strokeWidth / 2f, ring)
        val glyph = LucideGlyphs.glyph(context, glyphName)
        if (glyph.isEmpty()) return bitmap
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = LucideGlyphs.typeface(context)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.46f
            color = Color.argb(glyphAlpha, 255, 255, 255)
            setShadowLayer(GlassWidgets.dp(context, 1).toFloat(), 0f, 0f, Color.argb(80, 0, 0, 0))
        }
        val baseline = centre - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(glyph, centre, baseline, paint)
        return bitmap
    }

    /**
     * The card itself: blurred album art, a dark frosted scrim for contrast,
     * a soft light bloom, a 24dp rounded outline and a hairline white rim.
     * Without artwork it falls back to the light glass with a music watermark.
     */
    private fun coverBitmap(
        context: Context,
        width: Int,
        height: Int,
        art: Bitmap?,
        accent: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = GlassWidgets.dp(context, 24).toFloat()
            .coerceAtMost(minOf(width, height) / 2f)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius, radius, Path.Direction.CW
            )
        }
        canvas.clipPath(path)

        if (art != null) {
            canvas.drawBitmap(
                blurredArt(art, width, height),
                null,
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )
            // Darker towards the top and bottom so both text and controls read.
            val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(
                        Color.argb(132, 6, 12, 24),
                        Color.argb(74, 6, 12, 24),
                        Color.argb(176, 4, 8, 16)
                    ),
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        } else {
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    Color.argb(150, 26, 46, 74),
                    Color.argb(108, 10, 21, 38),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
            drawMusicWatermark(canvas, context, width, height)
        }

        // Light sheen from the upper edge and a soft tint bloom at the bottom.
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height * 0.6f,
                Color.argb(28, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.6f, sheen)
        val tint = GlassWidgets.artTint(art).takeIf { it != 0 } ?: accent
        val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.8f, height * 0.92f, max(width, height) * 0.95f,
                Color.argb(58, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bloom)

        // Brighter hairline along the top edge: the card catches the light.
        val edge = GlassWidgets.dp(context, 1).toFloat().coerceAtLeast(1f)
        val topGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                width * 0.08f, 0f, width * 0.92f, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(76, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(width * 0.08f, 0f, width * 0.92f, edge, topGlow)

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth =
                (context.resources.displayMetrics.density * 0.9f).coerceAtLeast(1f)
            color = Color.argb(88, 255, 255, 255)
        }
        val inset = rim.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius, radius, rim
        )
        return bitmap
    }

    /** Centre-cropped downscale: cheap and enough to read as a frosted blur. */
    private fun blurredArt(art: Bitmap, width: Int, height: Int): Bitmap {
        val sampleW = (width / 14).coerceIn(6, 48)
        val sampleH = (height / 14).coerceIn(6, 48)
        val small = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        val scale = max(
            sampleW.toFloat() / art.width.coerceAtLeast(1),
            sampleH.toFloat() / art.height.coerceAtLeast(1)
        )
        val drawW = art.width * scale
        val drawH = art.height * scale
        val left = (sampleW - drawW) / 2f
        val top = (sampleH - drawH) / 2f
        canvas.drawBitmap(
            art,
            null,
            RectF(left, top, left + drawW, top + drawH),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return small
    }

    /** Large translucent note so the waiting card is not an empty pane. */
    private fun drawMusicWatermark(canvas: Canvas, context: Context, width: Int, height: Int) {
        val glyph = LucideGlyphs.glyph(context, "music")
        if (glyph.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = LucideGlyphs.typeface(context)
            textAlign = Paint.Align.CENTER
            textSize = minOf(width, height) * 0.48f
            color = Color.argb(38, 255, 255, 255)
        }
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(glyph, width / 2f, baseline, paint)
    }
}
