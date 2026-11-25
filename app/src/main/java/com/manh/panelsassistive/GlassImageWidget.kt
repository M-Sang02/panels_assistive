package com.manh.panelsassistive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Image / QR card. One painted bitmap fills the slot at every launcher size
 * (2x2, 2x3, 3x2, 4x3 ...), so the silhouette never depends on the cell count:
 *  - no content: the same frosted glass as the idle music card, with a photo
 *    watermark and a "tap to add" line;
 *  - photo: centre-cropped artwork under the same 32dp corners and hairline rim
 *    as the music cards;
 *  - transparent PNG: the glass is masked by the artwork's own alpha, so the
 *    backdrop follows the sticker's shape instead of a rectangle behind it;
 *  - QR mode: the user's own picture, shown whole on the glass so a square
 *    code stays square in a non-square slot.
 */
class GlassImageWidget : GlassWidgetProvider() {
    override fun layoutId(context: Context, options: Bundle): Int = R.layout.widget_image

    // The whole card is painted in bind(), where the content is known.
    override fun bindBackground(context: Context, views: RemoteViews, options: Bundle) = Unit

    override fun bind(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        options: Bundle,
        accent: Int
    ) {
        val (widthDp, heightDp) = GlassWidgets.sizeDp(options)
        val width = GlassWidgets.dp(context, widthDp.coerceIn(64, 420))
        val height = GlassWidgets.dp(context, heightDp.coerceIn(64, 420))
        val scale = (MAX_CARD_PX.toFloat() / maxOf(width, height).toFloat()).coerceAtMost(1f)
        val bitmapWidth = (width * scale).roundToInt().coerceAtLeast(48)
        val bitmapHeight = (height * scale).roundToInt().coerceAtLeast(48)

        val uris = AppPrefs.imageWidgetUris(context, widgetId)
        val qrMode = AppPrefs.imageWidgetQrMode(context, widgetId)
        // A stored picture whose persisted permission was lost can no longer be
        // decoded; the card then behaves as empty so the editor can repair it.
        var showContent = uris.isNotEmpty()
        val painted = runCatching {
            val art = ImageWidgetMedia.loadBitmap(
                context,
                uris.firstOrNull().orEmpty(),
                maxOf(bitmapWidth, bitmapHeight) * 2
            )
            if (art == null) {
                showContent = false
                null
            } else if (qrMode && !ImageWidgetMedia.isTransparent(art)) {
                containCard(context, bitmapWidth, bitmapHeight, scale, art)
            } else if (ImageWidgetMedia.isTransparent(art)) {
                stickerCard(context, bitmapWidth, bitmapHeight, scale, art)
            } else {
                photoCard(context, bitmapWidth, bitmapHeight, scale, art)
            }
        }.getOrNull() ?: idleCard(context, bitmapWidth, bitmapHeight, scale)

        views.setImageViewBitmap(R.id.image_bg, painted)
        // Drop the placeholder drawable so the painted pane is the only layer.
        views.setInt(R.id.image_bg, "setBackgroundColor", Color.TRANSPARENT)
        views.setTextViewText(
            R.id.image_hint,
            if (showContent) " " else "Chạm để thêm ảnh hoặc mã QR"
        )
        views.setViewVisibility(
            R.id.image_hint,
            if (showContent) View.INVISIBLE else View.VISIBLE
        )
        views.setContentDescription(R.id.image_bg, "Widget ảnh")
        views.setOnClickPendingIntent(
            R.id.image_bg,
            tapPending(context, widgetId, showContent)
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach {
            AppPrefs.clearImageWidget(context, it)
            ImageWidgetMedia.deletePage(context, it)
        }
    }

    // ---------------------------------------------------------------- painters

    private fun idleCard(context: Context, width: Int, height: Int, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = corner(context, width, height, scale)
        paintGlass(canvas, context, width, height, scale, scrim = true)
        drawWatermark(canvas, context, width, height)
        paintRim(canvas, context, width, height, scale, radius)
        return bitmap
    }

    /** Opaque photo: centre-cropped and sharp, corners and rim like the music card. */
    private fun photoCard(
        context: Context,
        width: Int,
        height: Int,
        scale: Float,
        art: Bitmap
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = corner(context, width, height, scale)
        val path = roundedPath(width, height, radius)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(
            art,
            centreCrop(art, width, height),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        canvas.restore()
        paintRim(canvas, context, width, height, scale, radius)
        return bitmap
    }

    /**
     * Transparent artwork: a soft shadow and a frosted wash are masked by the
     * artwork's own alpha, so the glass hugs the visible pixels and the card
     * never shows a rectangular pane behind the sticker.
     */
    private fun stickerCard(
        context: Context,
        width: Int,
        height: Int,
        scale: Float,
        art: Bitmap
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pad = GlassWidgets.dp(context, 10) * scale
        val fit = fitRect(art, width, height, pad)

        // Mask: the artwork drawn into the card at its final size.
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        maskCanvas.drawBitmap(
            art, null, fit,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )

        // Drop shadow shaped by the artwork, so the sticker reads on any wallpaper.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(max(1f, GlassWidgets.dp(context, 6) * scale),
                BlurMaskFilter.Blur.NORMAL)
            colorFilter = PorterDuffColorFilter(Color.argb(105, 0, 0, 0), PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(mask, 0f, 0f, shadow)

        // Glass wash restricted to the artwork's alpha.
        val glass = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val glassCanvas = Canvas(glass)
        glassCanvas.drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    Color.argb(52, 255, 255, 255),
                    Color.argb(16, 255, 255, 255),
                    Shader.TileMode.CLAMP
                )
            }
        )
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        glassCanvas.drawBitmap(mask, 0f, 0f, maskPaint)
        canvas.drawBitmap(glass, 0f, 0f, null)
        glass.recycle()

        // Full artwork on top, untouched colours.
        canvas.drawBitmap(
            art, null, fit,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        mask.recycle()
        return bitmap
    }

    /** QR mode: the whole picture on the glass, never cropped. */
    private fun containCard(
        context: Context,
        width: Int,
        height: Int,
        scale: Float,
        art: Bitmap
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = corner(context, width, height, scale)
        paintGlass(canvas, context, width, height, scale)
        val pad = GlassWidgets.dp(context, 8) * scale
        canvas.drawBitmap(
            art, null, fitRect(art, width, height, pad),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        paintRim(canvas, context, width, height, scale, radius)
        return bitmap
    }

    /** Same frost, wash and bloom as [GlassWidgets.bindMusicCard]. */
    private fun paintGlass(
        canvas: Canvas,
        context: Context,
        width: Int,
        height: Int,
        scale: Float,
        scrim: Boolean = false
    ) {
        val radius = corner(context, width, height, scale)
        canvas.save()
        canvas.clipPath(roundedPath(width, height, radius))
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.argb(46, 255, 255, 255),
                Color.argb(12, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.argb(22, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wash)
        val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.84f, height * 0.9f, max(width, height) * 0.95f,
                Color.argb(12, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bloom)
        if (scrim) {
            // Same gentle shading the idle music card puts under its text.
            val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, height * 0.5f, 0f, height.toFloat(),
                    Color.TRANSPARENT,
                    Color.argb(34, 8, 12, 20),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, height * 0.5f, width.toFloat(), height.toFloat(), shade)
        }
        canvas.restore()
    }

    /** 0.9dp hairline at the same 22% white as the music card. */
    private fun paintRim(
        canvas: Canvas,
        context: Context,
        width: Int,
        height: Int,
        scale: Float,
        radius: Float
    ) {
        val stroke = (context.resources.displayMetrics.density * 0.9f * scale)
            .coerceAtLeast(1f)
        val inset = stroke / 2f
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.argb(56, 255, 255, 255)
        }
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius, radius, rim
        )
    }

    private fun drawWatermark(canvas: Canvas, context: Context, width: Int, height: Int) {
        val glyph = LucideGlyphs.glyph(context, "image")
        if (glyph.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = LucideGlyphs.typeface(context)
            textAlign = Paint.Align.CENTER
            textSize = min(width, height) * 0.30f
            color = Color.argb(30, 255, 255, 255)
        }
        val baseline = height * 0.46f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(glyph, width / 2f, baseline, paint)
    }

    private fun corner(context: Context, width: Int, height: Int, scale: Float): Float =
        (GlassWidgets.dp(context, 32) * scale).coerceAtMost(min(width, height) / 2f)

    private fun roundedPath(width: Int, height: Int, radius: Float): Path = Path().apply {
        addRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radius, radius, Path.Direction.CW
        )
    }

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

    /** Contain rect with equal padding; never crops a sticker. */
    private fun fitRect(art: Bitmap, width: Int, height: Int, pad: Float): RectF {
        val availableW = (width - pad * 2f).coerceAtLeast(1f)
        val availableH = (height - pad * 2f).coerceAtLeast(1f)
        val scale = min(availableW / art.width, availableH / art.height)
        val w = art.width * scale
        val h = art.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    private fun tapPending(context: Context, widgetId: Int, hasContent: Boolean): PendingIntent {
        val intent = if (hasContent) {
            Intent(context, ImageViewerActivity::class.java)
                .putExtra(ImageViewerActivity.EXTRA_WIDGET_ID, widgetId)
        } else {
            Intent(context, ImageWidgetActivity::class.java)
                .putExtra(ImageWidgetActivity.EXTRA_WIDGET_ID, widgetId)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            widgetId * 16 + if (hasContent) 31 else 30,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /** Same payload budget as the music cards; the pane is mostly smooth. */
        private const val MAX_CARD_PX = 288
    }
}

/**
 * Image decoding, transparency detection and cropped-picture storage shared by
 * the widget, the editor and the full-screen viewer.
 */
object ImageWidgetMedia {
    private const val TAG = "ImageWidget"

    fun loadBitmap(context: Context, value: String, maxPx: Int): Bitmap? {
        if (value.isBlank()) return null
        val uri = if (value.contains("://")) Uri.parse(value) else Uri.fromFile(File(value))
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = open(context, uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            rotate(context, uri, decoded)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to load image", error)
            null
        }
    }

    /** True when the visible artwork has genuinely transparent pixels. */
    fun isTransparent(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val size = 40
        val small = Bitmap.createScaledBitmap(bitmap, size, size, false)
        var transparent = false
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (Color.alpha(small.getPixel(x, y)) < 245) {
                    transparent = true
                    break
                }
            }
            if (transparent) break
        }
        if (small !== bitmap) small.recycle()
        return transparent
    }

    /** Cropped QR pictures live in app storage, one per widget. */
    fun pageFile(context: Context, widgetId: Int): File =
        File(File(context.filesDir, "image_widget"), "page_$widgetId.png")

    /** Writes the cropped square and returns its path for AppPrefs, or null. */
    fun savePage(context: Context, widgetId: Int, bitmap: Bitmap): String? {
        val file = pageFile(context, widgetId)
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        } catch (error: Exception) {
            Log.w(TAG, "Unable to save the picture", error)
            null
        }
    }

    fun deletePage(context: Context, widgetId: Int) {
        runCatching { pageFile(context, widgetId).delete() }
    }

    /** Removes the PNG generated by older builds that encoded the code itself. */
    fun deleteLegacyQr(context: Context, widgetId: Int) {
        runCatching { File(File(context.filesDir, "image_widget"), "qr_$widgetId.png").delete() }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") FileInputStream(File(uri.path.orEmpty()))
        else context.contentResolver.openInputStream(uri)

    private fun rotate(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            open(context, uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }
}
