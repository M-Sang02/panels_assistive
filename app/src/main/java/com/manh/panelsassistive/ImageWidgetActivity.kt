package com.manh.panelsassistive

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Floating glass editor for one image widget instance. An empty card opens it
 * with the two choices; "Thêm mã QR" picks the user's own QR picture, lets them
 * crop it to 1:1 and then (optionally) stores transfer info, which the viewer
 * shows on a second page. A card with content opens the full-screen viewer
 * instead, and the viewer's long-press menu comes back here to edit.
 */
class ImageWidgetActivity : Activity() {
    companion object {
        const val EXTRA_WIDGET_ID = "com.manh.panelsassistive.extra.IMAGE_WIDGET_ID"
        const val EXTRA_QR_FORM = "com.manh.panelsassistive.extra.IMAGE_QR_FORM"
        private const val REQUEST_PICK_IMAGES = 71
        private const val REQUEST_PICK_QR = 72
        private const val MAX_IMAGES = 20
        private const val CROP_MAX_ZOOM = 6f
        private const val CROP_OUTPUT_PX = 1080
    }

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var showQrForm = false

    private var cropMode = false
    private var cropSource: Bitmap? = null
    private lateinit var cropView: ImageView
    private var cropBase = 1f
    private var cropScale = 1f
    private var cropTx = 0f
    private var cropTy = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        showQrForm = intent.getBooleanExtra(EXTRA_QR_FORM, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = GlassUi.PAGE_COLOR
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            ImageWidgetMedia.deleteLegacyQr(this, widgetId)
        }
        render()
    }

    @Deprecated("Keeps the sheet in control of its own back steps")
    override fun onBackPressed() {
        when {
            cropMode -> cancelCrop()
            showQrForm -> {
                showQrForm = false
                render()
            }
            else -> super.onBackPressed()
        }
    }

    private fun render() {
        if (cropMode) {
            renderCrop()
            return
        }
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
        val uris = AppPrefs.imageWidgetUris(this, widgetId)
        val qrMode = AppPrefs.imageWidgetQrMode(this, widgetId)
        when {
            qrMode && uris.isNotEmpty() -> qrSection(panel, uris)
            uris.isNotEmpty() -> imageSection(panel, uris)
            else -> choiceSection(panel)
        }
    }

    private fun choiceSection(panel: LinearLayout) {
        panel.addView(hint(
            "Mã QR: chọn ảnh mã bạn đã có, cắt vuông 1:1 rồi thêm thông tin chuyển " +
                "khoản (có thể để trống). Chế độ ảnh cho phép chọn một hoặc nhiều ảnh."
        ))
        panel.addView(GlassUi.actionRow(
            this,
            "Thêm mã QR",
            "Thêm ảnh",
            onFirst = { pickQrImage() },
            onSecond = { pickImages() }
        ))
    }

    private fun imageSection(panel: LinearLayout, uris: List<String>) {
        val qr = AppPrefs.imageWidgetQr(this, widgetId)
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
        panel.addView(GlassUi.actionButton(
            this,
            if (qr == null) "Thêm thông tin chuyển khoản" else "Sửa thông tin chuyển khoản"
        ) {
            showQrForm = true
            render()
        })
        panel.addView(GlassUi.actionButton(this, "Chọn ảnh mã QR vuông") {
            pickQrImage()
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

    private fun qrSection(panel: LinearLayout, uris: List<String>) {
        val qr = AppPrefs.imageWidgetQr(this, widgetId)
        panel.addView(hint(
            "Ảnh QR hiển thị trọn vẹn và căn giữa trong widget. Khi có thông tin, " +
                "vuốt ảnh sang trái trong màn hình xem để mở trang thông tin."
        ))
        panel.addView(GlassUi.cardTitle(this, "Ảnh mã QR"))
        val bitmap = ImageWidgetMedia.loadBitmap(this, uris.first(), 480)
        if (bitmap != null) {
            panel.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val rounded = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                    cornerRadius = dp(16).toFloat()
                }
                setImageDrawable(rounded)
            }, LinearLayout.LayoutParams(dp(190), dp(190)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            })
        }
        if (qr != null && !qr.isEmpty()) {
            panel.addView(GlassUi.cardTitle(this, "Thông tin").apply {
                setPadding(0, dp(10), 0, 0)
            })
            qr.payload().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                panel.addView(label(line, 13, GlassUi.TEXT_SECONDARY).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }
        panel.addView(GlassUi.actionButton(
            this,
            if (qr == null || qr.isEmpty()) "Thêm thông tin" else "Sửa thông tin",
            prominent = true
        ) {
            showQrForm = true
            render()
        })
        panel.addView(GlassUi.actionButton(this, "Đổi ảnh QR") {
            pickQrImage()
        })
        panel.addView(GlassUi.actionButton(this, "Chuyển thành widget ảnh") {
            AppPrefs.setImageWidgetQrMode(this, widgetId, false)
            refreshWidget()
            render()
        })
        panel.addView(GlassUi.actionButton(this, "Xoá ảnh QR") {
            AppPrefs.setImageWidgetUris(this, widgetId, emptyList())
            AppPrefs.setImageWidgetQr(this, widgetId, null)
            AppPrefs.setImageWidgetQrMode(this, widgetId, false)
            ImageWidgetMedia.deletePage(this, widgetId)
            refreshWidget()
            render()
        })
    }

    private fun qrForm(panel: LinearLayout) {
        val existing = AppPrefs.imageWidgetQr(this, widgetId)
        val uris = AppPrefs.imageWidgetUris(this, widgetId)
        panel.addView(hint(
            "Thông tin là tuỳ chọn: điền bao nhiêu ô cũng được, để trống thì widget chỉ " +
                "hiện ảnh. Thông tin được mở bằng cách vuốt sang trái trong màn hình xem."
        ))
        if (uris.isNotEmpty()) {
            val bitmap = ImageWidgetMedia.loadBitmap(this, uris.first(), 360)
            if (bitmap != null) {
                panel.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val rounded = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                        cornerRadius = dp(14).toFloat()
                    }
                    setImageDrawable(rounded)
                }, LinearLayout.LayoutParams(dp(120), dp(120)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(10)
                })
            }
        }
        val account = field("Số tài khoản (STK)", existing?.account.orEmpty(), false)
        val bank = field("Ngân hàng", existing?.bank.orEmpty(), false)
        val name = field("Tên người nhận", existing?.name.orEmpty(), false)
        val note = field("Ghi chú (không bắt buộc)", existing?.note.orEmpty(), true)
        listOf(account, bank, name, note).forEach { panel.addView(it) }

        panel.addView(GlassUi.actionButton(this, "Lưu thông tin", prominent = true) {
            val info = AppPrefs.ImageWidgetQr(
                account = account.text.toString().trim(),
                bank = bank.text.toString().trim(),
                name = name.text.toString().trim(),
                note = note.text.toString().trim()
            )
            AppPrefs.setImageWidgetQr(this, widgetId, info)
            showQrForm = false
            refreshWidget()
            render()
        })
        panel.addView(GlassUi.actionButton(this, "Huỷ") {
            showQrForm = false
            render()
        })
    }

    // ------------------------------------------------------------------ picking

    private fun pickImages() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            REQUEST_PICK_IMAGES
        )
    }

    private fun pickQrImage() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*"),
            REQUEST_PICK_QR
        )
    }

    @Deprecated("Handles the system document picker result on supported versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQUEST_PICK_QR) {
            val uri = data?.data ?: return
            val bitmap = ImageWidgetMedia.loadBitmap(this, uri.toString(), 2048)
            if (bitmap == null) {
                Toast.makeText(this, "Không đọc được ảnh này.", Toast.LENGTH_SHORT).show()
                return
            }
            cropSource = bitmap
            cropMode = true
            render()
            return
        }
        if (requestCode != REQUEST_PICK_IMAGES) return
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
        // Pictures and the cropped QR picture use separate display modes. The
        // cropped file stays while it is still one of the pictures.
        AppPrefs.setImageWidgetQrMode(this, widgetId, false)
        AppPrefs.setImageWidgetQr(this, widgetId, null)
        if (ImageWidgetMedia.pageFile(this, widgetId).absolutePath !in merged) {
            ImageWidgetMedia.deletePage(this, widgetId)
        }
        showQrForm = false
        refreshWidget()
        render()
    }

    // -------------------------------------------------------------------- crop

    private fun renderCrop() {
        val source = cropSource
        if (source == null) {
            cropMode = false
            render()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF050B12.toInt()) }
        val side = (min(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        ) - dp(170)).coerceAtLeast(dp(160))
        cropView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(0xFF0B1624.toInt())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width > 0 && view.height > 0) {
                        outline.setRect(0, 0, view.width, view.height)
                    } else {
                        outline.setEmpty()
                    }
                }
            }
            clipToOutline = true
        }
        root.addView(cropView, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        root.addView(label("Kéo và phóng to để căn khung vuông 1:1", 12, GlassUi.TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), statusBarHeight() + dp(10), dp(16), 0)
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        root.addView(GlassUi.actionRow(
            this,
            "Huỷ",
            "Dùng ảnh",
            prominentSecond = true,
            onFirst = { cancelCrop() },
            onSecond = { confirmCrop() }
        ), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    cropZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                    return true
                }
            }
        )
        val dragDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (scaleDetector.isInProgress) return false
                    cropPan(-distanceX, -distanceY)
                    return true
                }
            }
        )
        cropView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            dragDetector.onTouchEvent(event)
            true
        }
        setContentView(root)
        cropView.post { resetCrop() }
    }

    private fun resetCrop() {
        val source = cropSource ?: return
        val side = cropView.width.coerceAtLeast(1).toFloat()
        cropBase = max(side / source.width, side / source.height)
        cropScale = 1f
        cropTx = (side - source.width * cropBase) / 2f
        cropTy = (side - source.height * cropBase) / 2f
        applyCropMatrix()
    }

    private fun applyCropMatrix() {
        val source = cropSource ?: return
        val side = cropView.width.coerceAtLeast(1).toFloat()
        val total = cropBase * cropScale
        val scaledW = source.width * total
        val scaledH = source.height * total
        cropTx = if (scaledW <= side) (side - scaledW) / 2f else cropTx.coerceIn(side - scaledW, 0f)
        cropTy = if (scaledH <= side) (side - scaledH) / 2f else cropTy.coerceIn(side - scaledH, 0f)
        cropView.imageMatrix = Matrix().apply {
            setScale(total, total)
            postTranslate(cropTx, cropTy)
        }
    }

    private fun cropZoom(factor: Float, focusX: Float, focusY: Float) {
        val next = (cropScale * factor).coerceIn(1f, CROP_MAX_ZOOM)
        if (next == cropScale) return
        val applied = next / cropScale
        cropScale = next
        cropTx = focusX - (focusX - cropTx) * applied
        cropTy = focusY - (focusY - cropTy) * applied
        applyCropMatrix()
    }

    private fun cropPan(dx: Float, dy: Float) {
        cropTx += dx
        cropTy += dy
        applyCropMatrix()
    }

    private fun cancelCrop() {
        cropMode = false
        cropSource = null
        render()
    }

    private fun confirmCrop() {
        val source = cropSource ?: return
        val side = cropView.width
        if (side <= 0) return
        val inverse = Matrix()
        if (!cropView.imageMatrix.invert(inverse)) return
        val corners = floatArrayOf(
            0f, 0f,
            side.toFloat(), 0f,
            0f, side.toFloat(),
            side.toFloat(), side.toFloat()
        )
        inverse.mapPoints(corners)
        val left = floor(corners[0]).toInt().coerceIn(0, source.width - 1)
        val top = floor(corners[1]).toInt().coerceIn(0, source.height - 1)
        val right = ceil(corners[6]).toInt().coerceIn(left + 1, source.width)
        val bottom = ceil(corners[7]).toInt().coerceIn(top + 1, source.height)
        val cropSide = min(right - left, bottom - top).coerceAtLeast(1)
        val cropLeft = left + (right - left - cropSide) / 2
        val cropTop = top + (bottom - top - cropSide) / 2
        val cropped = Bitmap.createBitmap(source, cropLeft, cropTop, cropSide, cropSide)
        val square = Bitmap.createScaledBitmap(cropped, CROP_OUTPUT_PX, CROP_OUTPUT_PX, true)
        if (square !== cropped) cropped.recycle()
        val path = ImageWidgetMedia.savePage(this, widgetId, square)
        square.recycle()
        if (path == null) {
            Toast.makeText(this, "Không lưu được ảnh. Thử lại.", Toast.LENGTH_SHORT).show()
            return
        }
        AppPrefs.setImageWidgetUris(this, widgetId, listOf(path))
        AppPrefs.setImageWidgetQrMode(this, widgetId, true)
        cropMode = false
        cropSource = null
        showQrForm = true
        refreshWidget()
        render()
    }

    // ----------------------------------------------------------------- editing

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

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)
}

/**
 * Full-screen viewer for one image widget: pinch to zoom freely, pan while
 * zoomed, double-tap to toggle 2.5x. At 100% the picture follows the finger and
 * fades out as it is dragged; past the threshold the next page fades in. The
 * optional transfer info is the last page. Long-press opens the glass sheet
 * with share / edit (and copy info on the info page).
 */
class ImageViewerActivity : Activity() {
    companion object {
        const val EXTRA_WIDGET_ID = "com.manh.panelsassistive.extra.IMAGE_WIDGET_ID"
        private const val MAX_ZOOM = 8f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val FADE_IN_MS = 220L
        private const val FADE_OUT_MS = 110L
        private const val DRAG_THRESHOLD = 0.28f
    }

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var uris: List<String> = emptyList()
    private var info: AppPrefs.ImageWidgetQr? = null
    private var index = 0

    private lateinit var image: ImageView
    private lateinit var ghost: ImageView
    private lateinit var counter: TextView
    private var bitmap: Bitmap? = null
    private var infoBitmap: Bitmap? = null
    private var fadeToken = 0

    private var baseScale = 1f
    private var userScale = 1f
    private var tx = 0f
    private var ty = 0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestures: GestureDetector

    private var dragging = false
    private var dragDistance = 0f
    private var flingDirection = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        uris = AppPrefs.imageWidgetUris(this, widgetId)
        info = AppPrefs.imageWidgetQr(this, widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || uris.isEmpty()) {
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
        // Returning from the editor may change the pictures or the info page.
        val newUris = AppPrefs.imageWidgetUris(this, widgetId)
        val newInfo = AppPrefs.imageWidgetQr(this, widgetId)
        if (newUris != uris || newInfo != info) {
            uris = newUris
            info = newInfo
            infoBitmap = null
            if (uris.isEmpty()) {
                finish()
                return
            }
            index = index.coerceIn(0, max(0, pageCount() - 1))
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

    private fun pageCount(): Int = uris.size + if (info != null) 1 else 0

    private fun isInfoPage(): Boolean = index >= uris.size

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
            text = if (info != null) {
                "Vuốt sang trái để xem thông tin · chạm giữ để chia sẻ hoặc sửa"
            } else {
                "Chạm giữ để chia sẻ hoặc sửa · kéo ngang để đổi ảnh khi đang 100%"
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
                if (userScale > 1.01f) {
                    pan(-distanceX, -distanceY)
                    return true
                }
                if (pageCount() < 2) return false
                if (!dragging) {
                    val startX = e1?.x ?: e2.x
                    val startY = e1?.y ?: e2.y
                    val dx = e2.x - startX
                    val dy = e2.y - startY
                    if (abs(dy) > abs(dx)) return false
                    dragging = true
                    image.animate().cancel()
                }
                val width = image.width.coerceAtLeast(1).toFloat()
                dragDistance = (dragDistance - distanceX).coerceIn(-width, width)
                image.alpha = (1f - abs(dragDistance) / width).coerceIn(0f, 1f)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (userScale > 1.01f || pageCount() < 2) return false
                if (abs(velocityX) < 450f || abs(velocityX) < abs(velocityY)) return false
                flingDirection = if (velocityX < 0f) 1 else -1
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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDistance = 0f
                    dragging = false
                    flingDirection = 0
                    image.animate().cancel()
                    if (image.alpha < 1f) image.alpha = 1f
                }
                MotionEvent.ACTION_UP -> finishDrag(canceled = false)
                MotionEvent.ACTION_CANCEL -> finishDrag(canceled = true)
            }
            true
        }
    }

    /** Ends a horizontal drag: fade back, or finish the fade-out and move on. */
    private fun finishDrag(canceled: Boolean) {
        if (!dragging) {
            if (image.alpha < 1f) {
                image.animate().alpha(1f).setDuration(FADE_IN_MS)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            return
        }
        dragging = false
        val width = image.width.coerceAtLeast(1).toFloat()
        val direction = when {
            canceled -> 0
            flingDirection != 0 -> flingDirection
            abs(dragDistance) >= width * DRAG_THRESHOLD ->
                if (dragDistance > 0f) 1 else -1
            else -> 0
        }
        if (direction == 0) {
            image.animate().alpha(1f).setDuration(FADE_IN_MS)
                .setInterpolator(DecelerateInterpolator()).start()
            return
        }
        // The page already faded with the finger; let it vanish, then fade the next in.
        val token = ++fadeToken
        image.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
            if (token != fadeToken) return@withEndAction
            step(direction, fromDrag = true)
        }.start()
    }

    private fun load(position: Int, animate: Boolean, fromDrag: Boolean = false) {
        index = position.coerceIn(0, max(0, pageCount() - 1))
        val next = decode()
        if (next == null) {
            Toast.makeText(this, "Không mở được ảnh này.", Toast.LENGTH_SHORT).show()
            image.alpha = 1f
            return
        }
        counter.text = if (isInfoPage()) "Thông tin" else "${index + 1} / ${uris.size}"
        swapTo(next, animate, fromDrag)
    }

    private fun decode(): Bitmap? {
        if (isInfoPage()) return infoPage()
        val screen = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        return ImageWidgetMedia.loadBitmap(this, uris.getOrNull(index).orEmpty(), screen)
    }

    /** Shows [next] straight away, cross-fades it, or fades it in after a drag. */
    private fun swapTo(next: Bitmap, animate: Boolean, fromDrag: Boolean) {
        val previous = bitmap
        val oldMatrix = image.imageMatrix
        bitmap = next
        userScale = 1f
        tx = 0f
        ty = 0f
        image.animate().cancel()

        if (!animate || previous == null || previous === next) {
            fadeToken++
            ghost.animate().cancel()
            ghost.alpha = 0f
            ghost.setImageDrawable(null)
            image.alpha = 1f
            image.setImageBitmap(next)
            image.post { applyMatrix() }
            return
        }

        if (fromDrag) {
            fadeToken++
            ghost.animate().cancel()
            ghost.alpha = 0f
            ghost.setImageDrawable(null)
            image.alpha = 0f
            image.setImageBitmap(next)
            image.post { applyMatrix() }
            image.animate()
                .alpha(1f)
                .setDuration(FADE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            return
        }

        ghost.animate().cancel()
        ghost.alpha = 1f
        ghost.setImageBitmap(previous)
        ghost.imageMatrix = oldMatrix
        image.alpha = 0f
        image.setImageBitmap(next)
        image.post { applyMatrix() }
        val token = ++fadeToken
        image.animate()
            .alpha(1f)
            .setDuration(FADE_IN_MS)
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

    private fun step(delta: Int, fromDrag: Boolean = false) {
        if (pageCount() < 2) return
        index = (index + delta + pageCount()) % pageCount()
        load(index, animate = true, fromDrag = fromDrag)
    }

    /** White info card with the typed transfer details, drawn once per change. */
    private fun infoPage(): Bitmap? {
        val qr = info ?: return null
        val cached = infoBitmap
        if (cached != null && !cached.isRecycled) return cached
        val size = 1080
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()), 64f, 64f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        )
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0B1522.toInt()
            textSize = 58f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("Thông tin", 72f, 132f, title)
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF24303C.toInt()
            textSize = 44f
            typeface = Typeface.DEFAULT
        }
        val text = qr.payload()
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, body, size - 144)
            .setLineSpacing(14f, 1f)
            .build()
        canvas.save()
        canvas.translate(72f, 196f)
        layout.draw(canvas)
        canvas.restore()
        infoBitmap = bitmap
        return bitmap
    }

    private fun showOptions() {
        val qr = info
        if (isInfoPage() && qr != null) {
            GlassDialogs.show(this, "Thông tin", listOf(
                GlassDialogs.Item(id = "copy", label = "Sao chép thông tin", glyph = "copy"),
                GlassDialogs.Item(id = "share", label = "Chia sẻ thông tin", glyph = "share-2"),
                GlassDialogs.Item(id = "edit", label = "Sửa thông tin", glyph = "image")
            )) { item ->
                when (item.id) {
                    "copy" -> copyInfo()
                    "share" -> shareInfo()
                    "edit" -> openEditor(qrForm = true)
                }
            }
            return
        }
        val items = buildList {
            add(GlassDialogs.Item(id = "share", label = "Chia sẻ ảnh", glyph = "share-2"))
            add(GlassDialogs.Item(
                id = "edit",
                label = "Sửa ảnh",
                caption = "Thêm, bớt hoặc đổi thứ tự ảnh",
                glyph = "image"
            ))
            if (qr != null) {
                add(GlassDialogs.Item(
                    id = "info",
                    label = "Xem thông tin",
                    caption = qr.payload().lineSequence().firstOrNull(),
                    glyph = "copy"
                ))
            }
        }
        GlassDialogs.show(this, "Ảnh ${index + 1} / ${uris.size}", items) { item ->
            when (item.id) {
                "share" -> shareImage()
                "edit" -> openEditor(qrForm = false)
                "info" -> load(uris.size, animate = true)
            }
        }
    }

    private fun openEditor(qrForm: Boolean) {
        startActivity(
            Intent(this, ImageWidgetActivity::class.java)
                .putExtra(ImageWidgetActivity.EXTRA_WIDGET_ID, widgetId)
                .putExtra(ImageWidgetActivity.EXTRA_QR_FORM, qrForm)
        )
    }

    private fun shareImage() {
        val value = uris.getOrNull(index) ?: return
        val uri = if (value.contains("://")) {
            Uri.parse(value)
        } else {
            FileProvider.getUriForFile(this, "$packageName.files", File(value))
        }
        sendShare(uri, contentResolver.getType(uri) ?: "image/*")
    }

    private fun shareInfo() {
        val payload = info?.payload().orEmpty()
        if (payload.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, payload)
        startActivity(Intent.createChooser(intent, "Chia sẻ thông tin"))
    }

    private fun sendShare(uri: Uri, type: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Chia sẻ ảnh"))
    }

    private fun copyInfo() {
        val payload = info?.payload().orEmpty()
        if (payload.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Thông tin", payload))
        Toast.makeText(this, "Đã sao chép thông tin.", Toast.LENGTH_SHORT).show()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)
}
