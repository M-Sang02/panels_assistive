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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Floating glass editor for one image widget instance. An empty card opens it
 * with the two choices ("Thêm mã QR" / "Thêm ảnh"); a card with content opens
 * the full-screen viewer instead, and the viewer's long-press menu comes back
 * here to add, remove or reorder pictures. Everything is a translucent centre
 * panel over the app's own scrim, with the shared glass controls.
 */
class ImageWidgetActivity : Activity() {
    companion object {
        const val EXTRA_WIDGET_ID = "com.manh.panelsassistive.extra.IMAGE_WIDGET_ID"
        private const val REQUEST_PICK_IMAGES = 71
        private const val MAX_IMAGES = 20
    }

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var showQrForm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = GlassUi.PAGE_COLOR
        render()
    }

    private fun render() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { finish() }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            setOnClickListener { }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = panelBackground()
            setOnClickListener { }
        }
        scroll.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val width = minOf((resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(520))
        root.addView(scroll, FrameLayout.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            topMargin = dp(28)
            bottomMargin = dp(28)
        })
        scroll.post {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.86f).toInt()
            if (scroll.height > maxHeight) {
                val params = scroll.layoutParams as FrameLayout.LayoutParams
                params.height = maxHeight
                scroll.layoutParams = params
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("Widget ảnh", 18, GlassUi.TEXT_PRIMARY, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(glyphButton("x") { finish() })
        panel.addView(header)

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            panel.addView(hint("Không tìm thấy widget này. Hãy gỡ và thêm lại widget."))
            setContentView(root)
            return
        }

        if (showQrForm) qrForm(panel) else mainContent(panel)
        setContentView(root)
    }

    private fun mainContent(panel: LinearLayout) {
        val qr = AppPrefs.imageWidgetQr(this, widgetId)
        val uris = AppPrefs.imageWidgetUris(this, widgetId)
        when {
            qr != null -> qrSection(panel, qr)
            uris.isNotEmpty() -> imageSection(panel, uris)
            else -> choiceSection(panel)
        }
    }

    private fun choiceSection(panel: LinearLayout) {
        panel.addView(hint(
            "Chọn nội dung cho widget. Mã QR là một ảnh vuông, luôn căn giữa khi " +
                "widget không vuông; chế độ ảnh cho phép chọn một hoặc nhiều ảnh."
        ))
        panel.addView(GlassUi.actionRow(
            this,
            "Thêm mã QR",
            "Thêm ảnh",
            onFirst = {
                showQrForm = true
                render()
            },
            onSecond = { pickImages() }
        ))
    }

    private fun imageSection(panel: LinearLayout, uris: List<String>) {
        panel.addView(hint(
            "Ảnh hiện trong widget theo đúng thứ tự này. Chạm widget để xem toàn màn " +
                "hình, phóng to và vuốt đổi ảnh khi đã thu về 100%."
        ))
        panel.addView(GlassUi.cardTitle(this, "Ảnh trong widget (${uris.size})"))
        uris.forEachIndexed { index, value ->
            panel.addView(imageRow(index, uris, value))
        }
        panel.addView(GlassUi.actionButton(this, "Thêm ảnh", prominent = true) {
            pickImages()
        })
        panel.addView(GlassUi.actionButton(this, "Thay bằng mã QR") {
            showQrForm = true
            render()
        })
    }

    private fun imageRow(index: Int, uris: List<String>, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(8), dp(7))
            background = GlassUi.glassSurface(this@ImageWidgetActivity, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) }

            val bitmap = ImageWidgetMedia.loadBitmap(this@ImageWidgetActivity, value, 160)
            addView(ImageView(this@ImageWidgetActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (bitmap != null) {
                    val rounded = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                        cornerRadius = dp(10).toFloat()
                    }
                    setImageDrawable(rounded)
                } else {
                    setBackground(GlassUi.fieldBackground(this@ImageWidgetActivity, 10))
                }
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(label("Ảnh ${index + 1}", 13, GlassUi.TEXT_PRIMARY).apply {
                setPadding(dp(10), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (index > 0) {
                addView(glyphButton("chevron-left") { move(uris, index, index - 1) })
            }
            if (index < uris.lastIndex) {
                addView(glyphButton("chevron-right") { move(uris, index, index + 1) })
            }
            addView(glyphButton("trash-2") { remove(uris, index) })
        }

    private fun qrSection(panel: LinearLayout, qr: AppPrefs.ImageWidgetQr) {
        panel.addView(hint(
            "Mã QR lưu thông tin chuyển khoản và được xem như một ảnh. Nhấn giữ mã " +
                "trong màn hình xem để sao chép lại thông tin hoặc chia sẻ ảnh."
        ))
        val code = ImageWidgetMedia.loadFile(ImageWidgetMedia.qrFile(this, widgetId))
            ?: ImageWidgetMedia.writeQr(this, widgetId, qr.payload())
                ?.let { ImageWidgetMedia.loadFile(it) }
        if (code != null) {
            panel.addView(ImageView(this).apply {
                setImageBitmap(code)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(190), dp(190)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            })
        } else {
            panel.addView(hint("Chưa tạo được mã. Mở \"Sửa thông tin QR\" và lưu lại."))
        }
        qr.payload().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            panel.addView(label(line, 13, GlassUi.TEXT_SECONDARY).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        panel.addView(GlassUi.actionButton(this, "Sửa thông tin QR", prominent = true) {
            showQrForm = true
            render()
        })
        panel.addView(GlassUi.actionButton(this, "Thay bằng ảnh") {
            pickImages()
        })
        panel.addView(GlassUi.actionButton(this, "Xoá mã QR") {
            AppPrefs.setImageWidgetQr(this, widgetId, null)
            ImageWidgetMedia.deleteQr(this, widgetId)
            refreshWidget()
            render()
        })
    }

    private fun qrForm(panel: LinearLayout) {
        val existing = AppPrefs.imageWidgetQr(this, widgetId)
        val hasImages = AppPrefs.imageWidgetUris(this, widgetId).isNotEmpty()
        panel.addView(hint(
            if (hasImages) {
                "Lưu mã QR sẽ thay danh sách ảnh hiện có. Chỉ cần điền những mục bạn " +
                    "muốn hiện trong mã; ghi chú có thể để trống."
            } else {
                "Mã QR tự cập nhật khi bạn nhập. Chỉ cần điền những mục bạn muốn " +
                    "hiện trong mã; ghi chú có thể để trống."
            }
        ))
        val account = field("Số tài khoản (STK)", existing?.account.orEmpty(), false)
        val bank = field("Ngân hàng", existing?.bank.orEmpty(), false)
        val name = field("Tên người nhận", existing?.name.orEmpty(), false)
        val note = field("Ghi chú (không bắt buộc)", existing?.note.orEmpty(), true)
        listOf(account, bank, name, note).forEach { panel.addView(it) }

        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        panel.addView(preview, LinearLayout.LayoutParams(dp(150), dp(150)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })

        fun build(): AppPrefs.ImageWidgetQr = AppPrefs.ImageWidgetQr(
            account = account.text.toString().trim(),
            bank = bank.text.toString().trim(),
            name = name.text.toString().trim(),
            note = note.text.toString().trim()
        )

        fun refreshPreview() {
            val qr = build()
            preview.setImageBitmap(
                if (qr.isEmpty()) null else ImageWidgetMedia.qrPlate(qr.payload(), 384)
            )
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPreview()
        }
        listOf(account, bank, name, note).forEach { it.addTextChangedListener(watcher) }
        refreshPreview()

        panel.addView(GlassUi.actionButton(this, "Lưu mã QR", prominent = true) {
            val qr = build()
            if (qr.isEmpty()) {
                Toast.makeText(this, "Nhập ít nhất một thông tin cho mã QR.", Toast.LENGTH_SHORT)
                    .show()
                return@actionButton
            }
            if (ImageWidgetMedia.writeQr(this, widgetId, qr.payload()) == null) {
                Toast.makeText(this, "Không tạo được mã QR. Thử lại.", Toast.LENGTH_SHORT).show()
                return@actionButton
            }
            AppPrefs.setImageWidgetQr(this, widgetId, qr)
            AppPrefs.setImageWidgetUris(this, widgetId, emptyList())
            showQrForm = false
            refreshWidget()
            render()
        })
        panel.addView(GlassUi.actionButton(this, "Huỷ") {
            showQrForm = false
            render()
        })
    }

    private fun pickImages() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            REQUEST_PICK_IMAGES
        )
    }

    @Deprecated("Handles the system document picker result on supported versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IMAGES || resultCode != RESULT_OK) return
        val picked = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }.distinct()
        if (picked.isEmpty()) return
        picked.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val existing = AppPrefs.imageWidgetUris(this, widgetId)
        val merged = (existing + picked.map(Uri::toString)).distinct().take(MAX_IMAGES)
        AppPrefs.setImageWidgetUris(this, widgetId, merged)
        // A card shows either pictures or one QR; picking pictures retires the QR.
        AppPrefs.setImageWidgetQr(this, widgetId, null)
        ImageWidgetMedia.deleteQr(this, widgetId)
        showQrForm = false
        refreshWidget()
        render()
    }

    private fun move(uris: List<String>, from: Int, to: Int) {
        if (to !in uris.indices) return
        val reordered = uris.toMutableList()
        val moved = reordered.removeAt(from)
        reordered.add(to, moved)
        AppPrefs.setImageWidgetUris(this, widgetId, reordered)
        refreshWidget()
        render()
    }

    private fun remove(uris: List<String>, index: Int) {
        if (index !in uris.indices) return
        val remaining = uris.toMutableList().apply { removeAt(index) }
        AppPrefs.setImageWidgetUris(this, widgetId, remaining)
        refreshWidget()
        render()
    }

    private fun refreshWidget() {
        GlassWidgets.refresh(this, GlassImageWidget::class.java)
    }

    private fun field(hint: String, value: String, multiline: Boolean): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            setTextColor(GlassUi.TEXT_PRIMARY)
            setHintTextColor(GlassUi.TEXT_MUTED)
            background = GlassUi.fieldBackground(this@ImageWidgetActivity, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            includeFontPadding = false
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            maxLines = if (multiline) 3 else 1
            minLines = if (multiline) 2 else 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun glyphButton(glyph: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = GlassUi.glassSurface(this@ImageWidgetActivity, 11)
            isClickable = true
            isFocusable = true
            addView(
                LucideGlyphView(this@ImageWidgetActivity, glyph, GlassUi.TEXT_SECONDARY),
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginStart = dp(4)
            }
        }

    private fun panelBackground(): LayerDrawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(GlassUi.PAGE_COLOR, 0xFF0A1626.toInt())
        ).apply {
            cornerRadius = dp(26).toFloat()
        }
        return LayerDrawable(arrayOf(base, GlassUi.glassSurface(this, 26)))
    }

    private fun hint(text: String): TextView = label(text, 12, GlassUi.TEXT_MUTED).apply {
        setPadding(0, dp(8), 0, 0)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun label(
        text: String,
        sizeSp: Int,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp.toFloat()
        setTextColor(color)
        gravity = Gravity.START
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)
}

/**
 * Full-screen viewer for one image widget: pinch to zoom freely, pan while
 * zoomed, double-tap to toggle 2.5x, and swipe left/right to change picture
 * only after the zoom is back at 100%. Pages cross-fade so the change reads as
 * a smooth slide through the gallery. Long-press opens the glass sheet with
 * share / edit, plus "copy info" for a QR. Editing returns to
 * [ImageWidgetActivity]; the widget repaints on the way out.
 */
class ImageViewerActivity : Activity() {
    companion object {
        const val EXTRA_WIDGET_ID = "com.manh.panelsassistive.extra.IMAGE_WIDGET_ID"
        private const val MAX_ZOOM = 8f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val FADE_MS = 220L
    }

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var uris: List<String> = emptyList()
    private var qr: AppPrefs.ImageWidgetQr? = null
    private var index = 0

    private lateinit var image: ImageView
    private lateinit var ghost: ImageView
    private lateinit var counter: TextView
    private var bitmap: Bitmap? = null
    private var fadeToken = 0

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
        load(index, animate = false)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the editor may add, remove, reorder or delete content.
        val newUris = AppPrefs.imageWidgetUris(this, widgetId)
        val newQr = AppPrefs.imageWidgetQr(this, widgetId)
        if (newUris != uris || newQr != qr) {
            uris = newUris
            qr = newQr
            if (uris.isEmpty() && qr == null) {
                finish()
                return
            }
            index = index.coerceIn(0, max(0, count() - 1))
            load(index, animate = true)
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
        ghost = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0f
            isClickable = false
        }
        root.addView(ghost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))
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

    private fun load(position: Int, animate: Boolean) {
        index = position.coerceIn(0, max(0, count() - 1))
        val next = decode()
        if (next == null) {
            Toast.makeText(this, "Không mở được ảnh này.", Toast.LENGTH_SHORT).show()
            if (bitmap == null) finish()
            return
        }
        counter.text = if (qr != null) "Mã QR" else "${index + 1} / ${uris.size}"
        swapTo(next, animate)
    }

    private fun decode(): Bitmap? {
        val info = qr
        if (info != null) {
            val file = ImageWidgetMedia.qrFile(this, widgetId)
            return ImageWidgetMedia.loadFile(file)
                ?: ImageWidgetMedia.writeQr(this, widgetId, info.payload())
                    ?.let { ImageWidgetMedia.loadFile(it) }
        }
        val screen = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        return ImageWidgetMedia.loadBitmap(this, uris.getOrNull(index).orEmpty(), screen)
    }

    /** Shows [next] straight away, or cross-fades it over the previous page. */
    private fun swapTo(next: Bitmap, animate: Boolean) {
        val previous = bitmap
        val oldMatrix = image.imageMatrix
        bitmap = next
        userScale = 1f
        tx = 0f
        ty = 0f
        image.animate().cancel()
        image.alpha = 1f
        image.setImageBitmap(next)
        image.post { applyMatrix() }

        if (!animate || previous == null || previous === next) {
            fadeToken++
            ghost.animate().cancel()
            ghost.alpha = 0f
            ghost.setImageDrawable(null)
            return
        }

        ghost.animate().cancel()
        ghost.alpha = 1f
        ghost.setImageBitmap(previous)
        ghost.imageMatrix = oldMatrix
        image.alpha = 0f
        val token = ++fadeToken
        image.animate()
            .alpha(1f)
            .setDuration(FADE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (token != fadeToken) return@withEndAction
                ghost.alpha = 0f
                ghost.setImageDrawable(null)
            }
            .start()
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
        load(index, animate = true)
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
            val file = ImageWidgetMedia.qrFile(this, widgetId)
            if (!file.isFile &&
                ImageWidgetMedia.writeQr(this, widgetId, currentQr.payload()) == null
            ) {
                Toast.makeText(this, "Không tạo được tệp chia sẻ.", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            sendShare(uri, "image/png")
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
