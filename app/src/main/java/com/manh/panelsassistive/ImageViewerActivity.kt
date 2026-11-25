package com.manh.panelsassistive

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen viewer for one image widget: pinch to zoom freely, pan while
 * zoomed, double-tap to toggle 2.5x, and swipe left/right to change picture
 * only after the zoom is back at 100%. Long-press opens the glass sheet with
 * share / edit, plus "copy info" for a QR. Editing returns to
 * [ImageWidgetActivity]; the widget repaints on the way out.
 */
class ImageViewerActivity : Activity() {
    companion object {
        const val EXTRA_WIDGET_ID = "com.manh.panelsassistive.extra.IMAGE_WIDGET_ID"
        private const val MAX_ZOOM = 8f
        private const val DOUBLE_TAP_ZOOM = 2.5f
    }

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var uris: List<String> = emptyList()
    private var qr: AppPrefs.ImageWidgetQr? = null
    private var index = 0

    private lateinit var image: ImageView
    private lateinit var counter: TextView
    private var bitmap: Bitmap? = null
    private var qrBitmap: Bitmap? = null

    private var baseScale = 1f
    private var userScale = 1f
    private var tx = 0f
    private var ty = 0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestures: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        uris = AppPrefs.imageWidgetUris(this, widgetId)
        qr = AppPrefs.imageWidgetQr(this, widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || (uris.isEmpty() && qr == null)) {
            finish()
            return
        }
        window.statusBarColor = 0xFF050B12.toInt()
        window.navigationBarColor = 0xFF050B12.toInt()
        buildUi()
        prepareGestureDetectors()
        load(index)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the editor may add, remove, reorder or delete content.
        val newUris = AppPrefs.imageWidgetUris(this, widgetId)
        val newQr = AppPrefs.imageWidgetQr(this, widgetId)
        if (newUris != uris || newQr != qr) {
            uris = newUris
            if (newQr != qr) qrBitmap = null
            qr = newQr
            if (uris.isEmpty() && qr == null) {
                finish()
                return
            }
            index = index.coerceIn(0, max(0, count() - 1))
            load(index)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation keeps the same bitmap; re-fit it to the new window.
        image.post {
            userScale = 1f
            tx = 0f
            ty = 0f
            applyMatrix()
        }
    }

    private fun count(): Int = if (qr != null) 1 else uris.size

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF050B12.toInt())
        }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Ảnh trong widget"
        }
        root.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), statusBarHeight() + dp(6), dp(12), dp(6))
        }
        topBar.addView(FrameLayout(this).apply {
            background = GlassUi.glassSurface(this@ImageViewerActivity, 12)
            isClickable = true
            addView(
                LucideGlyphView(this@ImageViewerActivity, "chevron-left", GlassUi.TEXT_PRIMARY),
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            )
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        counter = TextView(this).apply {
            textSize = 13f
            setTextColor(GlassUi.TEXT_SECONDARY)
            includeFontPadding = false
            gravity = Gravity.END
        }
        topBar.addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        val tip = TextView(this).apply {
            text = if (qr != null) {
                "Chạm giữ để chia sẻ, sửa hoặc sao chép thông tin"
            } else {
                "Chạm giữ để chia sẻ hoặc sửa · vuốt đổi ảnh khi thu về 100%"
            }
            textSize = 11f
            setTextColor(GlassUi.TEXT_MUTED)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        root.addView(tip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { bottomMargin = dp(18) })

        setContentView(root)
    }

    private fun prepareGestureDetectors() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                if (userScale <= 1.01f) return false
                pan(-distanceX, -distanceY)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (userScale > 1.01f) return false
                if (abs(velocityX) < 500f || abs(velocityX) < abs(velocityY)) return false
                step(if (velocityX < 0f) 1 else -1)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (userScale > 1.01f) {
                    userScale = 1f
                    tx = 0f
                    ty = 0f
                } else {
                    zoomBy(DOUBLE_TAP_ZOOM, e.x, e.y)
                }
                applyMatrix()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showOptions()
            }
        })
        image.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestures.onTouchEvent(event)
            true
        }
    }

    private fun load(position: Int) {
        index = position.coerceIn(0, max(0, count() - 1))
        val screen = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val previous = bitmap
        bitmap = if (qr != null) {
            qrBitmap ?: ImageWidgetMedia.qr(qr?.payload().orEmpty(), screen)
                ?.also { qrBitmap = it }
        } else {
            ImageWidgetMedia.loadBitmap(this, uris.getOrNull(index).orEmpty(), screen)
        }
        if (previous != null && previous !== bitmap && previous !== qrBitmap) previous.recycle()
        image.setImageBitmap(bitmap)
        userScale = 1f
        tx = 0f
        ty = 0f
        counter.text = if (qr != null) "Mã QR" else "${index + 1} / ${uris.size}"
        image.post { applyMatrix() }
    }

    private fun applyMatrix() {
        val bmp = bitmap ?: return
        val viewWidth = image.width.coerceAtLeast(1).toFloat()
        val viewHeight = image.height.coerceAtLeast(1).toFloat()
        baseScale = min(viewWidth / bmp.width, viewHeight / bmp.height)
        val total = baseScale * userScale
        val scaledWidth = bmp.width * total
        val scaledHeight = bmp.height * total
        tx = if (scaledWidth <= viewWidth) {
            (viewWidth - scaledWidth) / 2f
        } else {
            tx.coerceIn(viewWidth - scaledWidth, 0f)
        }
        ty = if (scaledHeight <= viewHeight) {
            (viewHeight - scaledHeight) / 2f
        } else {
            ty.coerceIn(viewHeight - scaledHeight, 0f)
        }
        val matrix = Matrix().apply {
            setScale(total, total)
            postTranslate(tx, ty)
        }
        image.imageMatrix = matrix
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (bitmap == null) return
        val next = (userScale * factor).coerceIn(1f, MAX_ZOOM)
        if (next == userScale) return
        val applied = next / userScale
        userScale = next
        tx = focusX - (focusX - tx) * applied
        ty = focusY - (focusY - ty) * applied
        if (userScale <= 1.001f) {
            userScale = 1f
            tx = 0f
            ty = 0f
        }
        applyMatrix()
    }

    private fun pan(dx: Float, dy: Float) {
        tx += dx
        ty += dy
        applyMatrix()
    }

    private fun step(delta: Int) {
        if (qr != null || uris.size < 2) return
        index = (index + delta + uris.size) % uris.size
        load(index)
    }

    private fun showOptions() {
        val currentQr = qr
        val items = buildList {
            add(GlassDialogs.Item(
                id = "share",
                label = if (currentQr != null) "Chia sẻ mã QR" else "Chia sẻ ảnh",
                glyph = "share-2"
            ))
            add(GlassDialogs.Item(
                id = "edit",
                label = if (currentQr != null) "Sửa mã QR" else "Sửa ảnh",
                caption = if (currentQr != null) null
                else "Thêm, bớt hoặc đổi thứ tự ảnh",
                glyph = "image"
            ))
            if (currentQr != null) {
                add(GlassDialogs.Item(
                    id = "copy",
                    label = "Sao chép thông tin",
                    caption = currentQr.payload().lineSequence().firstOrNull(),
                    glyph = "copy"
                ))
            }
        }
        GlassDialogs.show(
            this,
            if (currentQr != null) "Mã QR" else "Ảnh ${index + 1} / ${uris.size}",
            items
        ) { item ->
            when (item.id) {
                "share" -> shareCurrent()
                "edit" -> startActivity(
                    Intent(this, ImageWidgetActivity::class.java)
                        .putExtra(ImageWidgetActivity.EXTRA_WIDGET_ID, widgetId)
                )
                "copy" -> copyQrInfo()
            }
        }
    }

    private fun shareCurrent() {
        val currentQr = qr
        if (currentQr != null) {
            val code = qrBitmap ?: ImageWidgetMedia.qr(currentQr.payload(), 1024) ?: return
            val shared = try {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val file = File(dir, "widget-qr.png")
                FileOutputStream(file).use { out ->
                    code.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                FileProvider.getUriForFile(this, "$packageName.files", file)
            } catch (_: Exception) {
                Toast.makeText(this, "Không tạo được tệp chia sẻ.", Toast.LENGTH_SHORT).show()
                return
            }
            sendShare(shared, "image/png")
            return
        }
        val value = uris.getOrNull(index) ?: return
        val uri = Uri.parse(value)
        sendShare(uri, contentResolver.getType(uri) ?: "image/*")
    }

    private fun sendShare(uri: Uri, type: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Chia sẻ ảnh"))
    }

    private fun copyQrInfo() {
        val payload = qr?.payload().orEmpty()
        if (payload.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Mã QR", payload))
        Toast.makeText(this, "Đã sao chép thông tin mã QR.", Toast.LENGTH_SHORT).show()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)
}
