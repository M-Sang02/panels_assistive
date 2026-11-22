package com.manh.panelsassistive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Second, independent music widget. The album art becomes the card itself —
 * centre-cropped and sharp, under a light glass veil that dissolves into the
 * wallpaper towards the base: title and artist sit on top, a compact
 * Previous / Play / Next cluster sits at the bottom. It shares the app
 * assignment, "Play music now" and MediaSession paths with
 * [GlassMusicWidget] but has its own provider, layout and drawables.
 */
class GlassMusicCoverWidget : GlassWidgetProvider() {
    companion object {
        /**
         * Bitmap side for the whole card. A frosted, low-res pane used to turn
         * the cover into an unrecognisable smear; 288 px (the same cap as the
         * regular music card) keeps the photo and the hairline rim sharp while
         * the payload (~330 KB) stays far below the binder budget that some
         * launchers enforce (about 1 MB per RemoteViews).
         */
        private const val COVER_MAX_PX = 288
    }

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
        // Two native pixels are enough per dp for a frosted blur; the payload
        // stays far below the RemoteViews binder budget.
        val scale = (COVER_MAX_PX.toFloat() / maxOf(width, height).toFloat()).coerceAtMost(1f)
        val bitmapWidth = (width * scale).roundToInt().coerceAtLeast(48)
        val bitmapHeight = (height * scale).roundToInt().coerceAtLeast(48)
        runCatching { coverBitmap(context, bitmapWidth, bitmapHeight, now.art, scale) }
            .getOrNull()
            ?.let {
                views.setImageViewBitmap(R.id.cover_bg, it)
                // Drop the placeholder drawable so the painted pane is the only
                // frost; otherwise the two layers stack into a milky card.
                views.setInt(R.id.cover_bg, "setBackgroundColor", Color.TRANSPARENT)
            }

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
                big -> 18f
                wide -> 14f
                else -> 12f
            }
        )
        views.setTextViewTextSize(
            R.id.cover_artist,
            TypedValue.COMPLEX_UNIT_SP,
            when {
                big -> 13f
                wide -> 11f
                else -> 10f
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
        val playAlpha = if (idle) 0xD9 else 0xFF
        views.setImageViewBitmap(
            R.id.cover_play_bg,
            GlassWidgets.circleBitmap(
                context,
                GlassWidgets.dp(context, 34),
                (accent and 0x00FFFFFF) or (playAlpha shl 24)
            )
        )
        // Remove the placeholder oval from the XML so only the painted disc
        // (and no leftover ring) shows at runtime.
        views.setInt(R.id.cover_play_bg, "setBackgroundColor", Color.TRANSPARENT)
        views.setImageViewBitmap(
            R.id.cover_play,
            GlassWidgets.glyphBitmap(
                context,
                if (now.isPlaying) "pause" else "play",
                Color.WHITE,
                GlassWidgets.dp(context, if (big) 19 else 14)
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

    /**
     * White transport disc with a dark glyph, like the reference mock-up.
     * The Play disc is the only element tinted with the widget colour.
     */
    private fun sideCircleBitmap(context: Context, glyphName: String, glyphAlpha: Int): Bitmap {
        val size = GlassWidgets.dp(context, 26).coerceAtLeast(12)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((glyphAlpha * 0.96f).toInt().coerceIn(0, 255), 255, 255, 255)
        }
        canvas.drawCircle(centre, centre, centre, disc)
        val glyph = LucideGlyphs.glyph(context, glyphName)
        if (glyph.isEmpty()) return bitmap
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = LucideGlyphs.typeface(context)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.46f
            color = Color.argb(glyphAlpha.coerceIn(0, 255), 22, 26, 34)
        }
        val baseline = centre - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(glyph, centre, baseline, paint)
        return bitmap
    }

    /**
     * The card itself: the album art centre-cropped at full sharpness, a light
     * frosted veil, a soft scrim behind the track text, a 24dp rounded outline,
     * a hairline rim and a bottom fade that dissolves the art into the
     * wallpaper. Without artwork it falls back to the white glass with a music
     * watermark.
     */
    private fun coverBitmap(
        context: Context,
        width: Int,
        height: Int,
        art: Bitmap?,
        scale: Float = 1f
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = (GlassWidgets.dp(context, 24) * scale).coerceAtMost(minOf(width, height) / 2f)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius, radius, Path.Direction.CW
            )
        }
        canvas.clipPath(path)

        if (art != null) {
            // Draw the original pixels at this card's resolution instead of a
            // heavily downscaled copy: the reference keeps the photo readable.
            canvas.drawBitmap(
                art,
                centreCrop(art, width, height),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )
        } else {
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isDither = true
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    Color.argb(20, 255, 255, 255),
                    Color.argb(6, 255, 255, 255),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
            drawMusicWatermark(canvas, context, width, height)
        }

        // One continuous frosted veil across the whole card: a soft white wash
        // that thins out towards the base. The previous build drew a haze band
        // plus a separate scrim rectangle that ended abruptly at 78% height,
        // which produced the horizontal slice running through the transport row.
        val veil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.argb(26, 255, 255, 255),
                Color.argb(6, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)

        // Bottom dissolve: the lower half thins out gradually and is nearly
        // clear at the very base, leaving only a hint of the artwork. The
        // transport buttons are separate ImageViews, so they stay readable.
        val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            shader = LinearGradient(
                0f, height * 0.30f, 0f, height.toFloat(),
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(50, 0, 0, 0),
                    Color.argb(150, 0, 0, 0),
                    Color.argb(232, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.30f, 0.70f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, height * 0.30f, width.toFloat(), height.toFloat(), fade)

        // Bright light catching the top edge, like the reference card.
        val edge = (context.resources.displayMetrics.density * 1.8f * scale)
            .coerceAtLeast(1f)
        val topGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                width * 0.04f, 0f, width * 0.96f, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(112, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(width * 0.04f, 0f, width * 0.96f, edge, topGlow)

        // Crisp rim painted *after* the dissolve so the frame stays closed on
        // every side. The vertical gradient keeps the top/corners brighter
        // while the bottom stays visible instead of fading away with the art.
        // Same rounded path as the clipped cover, so the line follows the
        // corners exactly without breaks or offsets.
        val rimWidth = (context.resources.displayMetrics.density * scale)
            .coerceAtLeast(1f)
        val inset = rimWidth / 2f
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = rimWidth
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(
                    Color.argb(190, 255, 255, 255),
                    Color.argb(154, 255, 255, 255),
                    Color.argb(128, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius, radius, rim
        )
        return bitmap
    }

    /** Centre crop that keeps the artwork's aspect ratio without distortion. */
    private fun centreCrop(art: Bitmap, width: Int, height: Int): Rect {
        val target = width.toFloat() / height.coerceAtLeast(1)
        val aspect = art.width.toFloat() / art.height.coerceAtLeast(1)
        return if (aspect > target) {
            val w = (art.height * target).roundToInt().coerceAtMost(art.width)
            val left = (art.width - w) / 2
            Rect(left, 0, left + w, art.height)
        } else {
            val h = (art.width / target).roundToInt().coerceAtMost(art.height)
            val top = (art.height - h) / 2
            Rect(0, top, art.width, top + h)
        }
    }

    /** Large translucent note so the waiting card is not an empty pane. */
    private fun drawMusicWatermark(canvas: Canvas, context: Context, width: Int, height: Int) {
        val glyph = LucideGlyphs.glyph(context, "music")
        if (glyph.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = LucideGlyphs.typeface(context)
            textAlign = Paint.Align.CENTER
            textSize = minOf(width, height) * 0.48f
            color = Color.argb(30, 255, 255, 255)
        }
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(glyph, width / 2f, baseline, paint)
    }
}
