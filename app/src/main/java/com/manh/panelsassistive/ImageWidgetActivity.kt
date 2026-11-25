package com.manh.panelsassistive

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
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
            "Chọn nội dung cho widget. Mã QR là ảnh vuông 1:1, luôn căn giữa khi " +
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
            "Mã QR lưu thông tin chuyển khoản. Nhấn giữ mã trong màn hình xem để " +
                "sao chép lại thông tin hoặc chia sẻ ảnh."
        ))
        val code = ImageWidgetMedia.qr(qr.payload(), 512)
        if (code != null) {
            panel.addView(ImageView(this).apply {
                setImageBitmap(code)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(16).toFloat()
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }, LinearLayout.LayoutParams(dp(190), dp(190)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            })
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
                "Chỉ cần điền những mục bạn muốn hiện trong mã; ghi chú có thể để trống."
            }
        ))
        val account = field("Số tài khoản (STK)", existing?.account.orEmpty(), false)
        val bank = field("Ngân hàng", existing?.bank.orEmpty(), false)
        val name = field("Tên người nhận", existing?.name.orEmpty(), false)
        val note = field("Ghi chú (không bắt buộc)", existing?.note.orEmpty(), true)
        listOf(account, bank, name, note).forEach { panel.addView(it) }
        panel.addView(GlassUi.actionButton(this, "Lưu mã QR", prominent = true) {
            val qr = AppPrefs.ImageWidgetQr(
                account = account.text.toString().trim(),
                bank = bank.text.toString().trim(),
                name = name.text.toString().trim(),
                note = note.text.toString().trim()
            )
            if (qr.isEmpty()) {
                Toast.makeText(this, "Nhập ít nhất một thông tin cho mã QR.", Toast.LENGTH_SHORT)
                    .show()
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
        lineSpacing(dp(2).toFloat(), 1f)
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
