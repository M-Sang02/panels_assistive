package com.manh.panelsassistive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * XGesture-style freeform settings: enable switch, launch policy, OEM
 * windowing mode, a live size/position editor and "follow trigger position".
 */
class FreeformSettingsActivity : Activity() {
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        render()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            background = GlassUi.pageBackground()
        }
        root.addView(GlassUi.backBar(this) { finish() })
        root.addView(GlassUi.pageHeader(
            this,
            "Cửa sổ tự do",
            "Cấu hình cách mở và kích thước cửa sổ nổi cho app bật từ bảng."
        ))
        root.addView(launchCard())
        root.addView(windowCard())
        root.addView(shizukuCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun launchCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val activity = this@FreeformSettingsActivity
        addView(GlassUi.cardTitle(activity, "Cách mở"))
        addView(hint("Bật để app mở từ bảng chạy trong cửa sổ nổi. Kiểu mở quyết định chạm và nhấn giữ."))
        addView(switchRow(
            "Bật cửa sổ tự do",
            AppPrefs.freeformApps(activity),
            "Khi tắt, mọi app mở toàn màn hình như bình thường."
        ) { on ->
            AppPrefs.setFreeformApps(activity, on)
            if (on) {
                FloatingApps.enableFreeformSupport()
                if (FloatingApps.shizukuInstalled(activity) && !FloatingApps.shizukuReady()) {
                    FloatingApps.requestShizukuPermission(activity)
                }
            }
            render()
        })
        val current = FloatingApps.policy(activity)
        addView(settingRow("Kiểu mở", policyTitle(current)) { showPolicyPicker() })
        addView(hint(policyCaption(current)))
    }

    private fun windowCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val activity = this@FreeformSettingsActivity
        addView(GlassUi.cardTitle(activity, "Cửa sổ tự do"))
        val mode = FloatingApps.mode(activity)
        addView(settingRow("Chế độ OEM", modeSummary()) { showModePicker() })
        addView(hint(modeCaption(mode)))
        addView(settingRow("Chỉnh kích thước và vị trí", layoutSummary()) {
            if (FloatingApps.mode(activity) == FloatingApps.Mode.SUNSHINE) {
                Toast.makeText(
                    activity,
                    "Chế độ Sunshine tự quyết định kích thước cửa sổ.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                showLayoutEditor()
            }
        })
        addView(hint("Rộng×cao theo % màn hình, thứ tự: dọc · ngang."))
        addView(switchRow(
            "Bám theo vị trí kích hoạt",
            AppPrefs.freeformFollowTrigger(activity),
            "Mở cửa sổ về phía đã chạm và canh giữa theo độ cao điểm chạm; " +
                "khi không có điểm chạm thì dùng vị trí cố định ở trên."
        ) { on -> AppPrefs.setFreeformFollowTrigger(activity, on) })
        addView(infoBox(
            "Các hãng mở cửa sổ nổi qua ActivityOptions windowingMode với giá trị khác nhau " +
                "(Flyme 11, HyperOS 5, ColorOS 100…). Nếu không được, hãy bật cửa sổ nổi / " +
                "cửa sổ tự do trong cài đặt hệ thống."
        ))
    }

    private fun shizukuCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val activity = this@FreeformSettingsActivity
        addView(GlassUi.cardTitle(activity, "Shizuku"))
        addView(hint(
            "Shizuku mở cửa sổ nổi chắc chắn hơn trên ROM chặn ActivityOptions; " +
                "vài máy không cần."
        ))
        when {
            FloatingApps.shizukuReady() -> {
                addView(accentLine("Shizuku đã kết nối"))
                addView(GlassUi.actionButton(activity, "Bật hỗ trợ cửa sổ nổi trong hệ thống") {
                    FloatingApps.enableFreeformSupport()
                    Toast.makeText(activity, "Đã gửi lệnh bật cửa sổ nổi.", Toast.LENGTH_SHORT).show()
                })
            }
            FloatingApps.shizukuInstalled(activity) -> {
                addView(hint("Đã cài Shizuku nhưng chưa cấp quyền cho Panels."))
                addView(GlassUi.actionButton(activity, "Cấp quyền Shizuku") {
                    FloatingApps.requestShizukuPermission(activity)
                })
            }
            else -> {
                addView(hint(
                    "Chưa cài Shizuku. App vẫn thử mở thẳng cửa sổ nổi; nếu ROM bỏ qua, " +
                        "cài Shizuku để chạy chắc chắn."
                ))
                addView(GlassUi.actionButton(activity, "Cài Shizuku") {
                    val market = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=${FloatingApps.SHIZUKU_PACKAGE}")
                    )
                    val web = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://play.google.com/store/apps/details?id=" +
                                FloatingApps.SHIZUKU_PACKAGE
                        )
                    )
                    try {
                        startActivity(if (market.resolveActivity(packageManager) != null) market else web)
                    } catch (_: Exception) {
                        Toast.makeText(
                            activity,
                            "Không mở được cửa hàng ứng dụng.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
        }
    }

    private fun showPolicyPicker() {
        val current = FloatingApps.policy(this)
        val items = FloatingApps.Policy.values().map { policy ->
            GlassDialogs.Item(
                id = policy.id.toString(),
                label = policyTitle(policy),
                caption = policyCaption(policy),
                glyph = "smartphone",
                selected = policy == current
            )
        }
        GlassDialogs.show(
            this,
            "Kiểu mở",
            items,
            "Chạm và nhấn giữ trên biểu tượng app trong bảng",
            searchable = false
        ) { item ->
            item.id.toIntOrNull()?.let { AppPrefs.setFreeformPolicy(this, it) }
            render()
        }
    }

    private fun showModePicker() {
        val raw = AppPrefs.freeformMode(this)
        val detected = FloatingApps.mode(this)
        val auto = GlassDialogs.Item(
            id = "-1",
            label = "Tự động (theo hãng)",
            caption = "Đang nhận diện: ${modeTitle(detected)}",
            glyph = "smartphone",
            selected = raw < 0
        )
        val items = listOf(auto) + FloatingApps.Mode.values().map { mode ->
            GlassDialogs.Item(
                id = mode.id.toString(),
                label = modeTitle(mode),
                caption = modeCaption(mode),
                glyph = "app-window",
                selected = raw == mode.id
            )
        }
        GlassDialogs.show(
            this,
            "Chọn chế độ mở",
            items,
            "Giá trị windowingMode phù hợp với từng hãng"
        ) { item ->
            val picked = item.id.toIntOrNull() ?: return@show
            AppPrefs.setFreeformMode(this, picked)
            render()
        }
    }

    /** Live editor: portrait/landscape tabs, a draggable frame, save button. */
    private fun showLayoutEditor() {
        val activity = this
        val sheet = GlassDialogs.sheet(
            activity,
            "Kích thước và vị trí",
            "Kéo khung để di chuyển, kéo góc dưới bên phải để đổi kích thước."
        )
        var landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var portrait = AppPrefs.freeformLayout(activity, false)
        var land = AppPrefs.freeformLayout(activity, true)

        val percent = TextView(activity).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GlassUi.ACCENT)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(8), 0, dp(4))
        }
        val portraitTab = TextView(activity)
        val landscapeTab = TextView(activity)
        lateinit var preview: FreeformPreview

        fun refresh() {
            styleTab(portraitTab, !landscape, "Dọc")
            styleTab(landscapeTab, landscape, "Ngang")
            val current = if (landscape) land else portrait
            preview.layout = current
            percent.text = "${current.width}% × ${current.height}%"
        }

        preview = FreeformPreview(activity, if (landscape) land else portrait) { updated ->
            if (landscape) land = updated else portrait = updated
            percent.text = "${updated.width}% × ${updated.height}%"
        }
        portraitTab.setOnClickListener {
            landscape = false
            refresh()
        }
        landscapeTab.setOnClickListener {
            landscape = true
            refresh()
        }

        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(portraitTab, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(
                landscapeTab,
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) }
            )
        }
        sheet.content.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        sheet.content.addView(percent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val previewHeight = minOf(
            dp(300),
            (resources.displayMetrics.heightPixels * 0.40f).toInt()
        )
        sheet.content.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            previewHeight
        ))
        sheet.content.addView(GlassUi.actionButton(
            activity,
            "Hoàn tất xem trước và lưu",
            prominent = true
        ) {
            AppPrefs.setFreeformLayout(activity, false, portrait)
            AppPrefs.setFreeformLayout(activity, true, land)
            sheet.dismiss()
            render()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        refresh()
        sheet.show()
    }

    private fun styleTab(view: TextView, selected: Boolean, title: String) {
        view.text = title
        view.textSize = 13f
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        view.setTypeface(view.typeface, Typeface.BOLD)
        view.setTextColor(if (selected) GlassUi.ACCENT else GlassUi.TEXT_SECONDARY)
        view.background = GlassUi.tabSurface(this, selected)
        view.isClickable = true
        view.isFocusable = true
    }

    private fun modeSummary(): String {
        val detected = modeTitle(FloatingApps.mode(this))
        return if (AppPrefs.freeformMode(this) < 0) "Tự động: $detected" else detected
    }

    private fun layoutSummary(): String {
        val portrait = AppPrefs.freeformLayout(this, false)
        val land = AppPrefs.freeformLayout(this, true)
        return "${portrait.width}×${portrait.height} · ${land.width}×${land.height}"
    }

    private fun policyTitle(policy: FloatingApps.Policy): String = when (policy) {
        FloatingApps.Policy.ALWAYS_FULLSCREEN -> "Luôn toàn màn hình"
        FloatingApps.Policy.ALWAYS_FREE_WINDOW -> "Luôn cửa sổ nổi"
        FloatingApps.Policy.FULLSCREEN_LONG_FREE_WINDOW -> "Toàn màn hình, nhấn giữ mở nổi"
        FloatingApps.Policy.FREE_WINDOW_LONG_FULLSCREEN -> "Cửa sổ nổi, nhấn giữ toàn màn hình"
    }

    private fun policyCaption(policy: FloatingApps.Policy): String = when (policy) {
        FloatingApps.Policy.ALWAYS_FULLSCREEN ->
            "Không dùng cửa sổ nổi, kể cả khi đã bật."
        FloatingApps.Policy.ALWAYS_FREE_WINDOW ->
            "Chạm là mở trong cửa sổ nổi."
        FloatingApps.Policy.FULLSCREEN_LONG_FREE_WINDOW ->
            "Chạm mở toàn màn hình; nhấn giữ rồi thả để mở cửa sổ nổi."
        FloatingApps.Policy.FREE_WINDOW_LONG_FULLSCREEN ->
            "Chạm mở cửa sổ nổi; nhấn giữ rồi thả để mở toàn màn hình."
    }

    private fun modeTitle(mode: FloatingApps.Mode): String = when (mode) {
        FloatingApps.Mode.STANDARD -> "Chuẩn · 5"
        FloatingApps.Mode.COLOROS -> "ColorOS · 100"
        FloatingApps.Mode.MAGICOS -> "HarmonyOS/MagicOS · 102"
        FloatingApps.Mode.ORIGINOS -> "OriginOS · 106"
        FloatingApps.Mode.FLYME -> "FlymeOS · 11"
        FloatingApps.Mode.NUBIA -> "Nubia / RedMagic"
        FloatingApps.Mode.SUNSHINE -> "Sunshine"
    }

    private fun modeCaption(mode: FloatingApps.Mode): String = when (mode) {
        FloatingApps.Mode.STANDARD -> "Cho HyperOS, MIUI và máy tương tự"
        FloatingApps.Mode.COLOROS -> "Cho OPPO, realme và OnePlus"
        FloatingApps.Mode.MAGICOS -> "Cho Huawei và Honor"
        FloatingApps.Mode.ORIGINOS -> "Cho vivo và iQOO"
        FloatingApps.Mode.FLYME -> "Cho Meizu"
        FloatingApps.Mode.NUBIA -> "Dùng window-reply identifier; không cần Shizuku"
        FloatingApps.Mode.SUNSHINE -> "Mở qua app Sunshine (cần com.sunshine.freeform)"
    }

    private fun switchRow(
        title: String,
        checked: Boolean,
        subtitle: String? = null,
        onChanged: (Boolean) -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        addView(LinearLayout(this@FreeformSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                label(title, 14, GlassUi.TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(GlassUi.switch(this@FreeformSettingsActivity, checked) { onChanged(it) })
        })
        if (subtitle != null) addView(hint(subtitle))
    }

    private fun settingRow(title: String, value: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            addView(
                label(title, 14, GlassUi.TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(label(value, 13, GlassUi.TEXT_MUTED))
            addView(
                LucideGlyphView(
                    this@FreeformSettingsActivity,
                    "chevron-right",
                    GlassUi.TEXT_MUTED
                ),
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) }
            )
            setOnClickListener { onClick() }
        }

    private fun infoBox(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(GlassUi.ACCENT)
        includeFontPadding = false
        background = GlassUi.fieldBackground(this@FreeformSettingsActivity, 14)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun accentLine(text: String): TextView =
        label(text, 13, GlassUi.ACCENT).apply { setPadding(0, dp(4), 0, dp(6)) }

    private fun hint(text: String): TextView = label(text, 12, GlassUi.TEXT_MUTED).apply {
        setPadding(0, dp(4), 0, dp(6))
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
 * Draggable/resizable frame on a mock screen. Values stay as percentages so
 * the same layout works on any device; the activity persists them on save.
 */
private class FreeformPreview(
    context: Context,
    initial: AppPrefs.FreeformLayout,
    private val onChanged: (AppPrefs.FreeformLayout) -> Unit
) : View(context) {
    var layout: AppPrefs.FreeformLayout = initial
        set(value) {
            field = value
            updateRects()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screenRect = RectF()
    private val windowRect = RectF()
    private val barRect = RectF()
    private val pad = 12f * density
    private val handleSize = 28f * density
    private var dragging = false
    private var resizing = false
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRects()
    }

    private fun updateRects() {
        if (width == 0 || height == 0) return
        screenRect.set(pad, pad, width - pad, height - pad)
        val windowWidth = screenRect.width() * layout.width / 100f
        val windowHeight = screenRect.height() * layout.height / 100f
        val left = (screenRect.left + screenRect.width() * layout.left / 100f)
            .coerceAtMost(screenRect.right - windowWidth)
        val top = (screenRect.top + screenRect.height() * layout.top / 100f)
            .coerceAtMost(screenRect.bottom - windowHeight)
        windowRect.set(left, top, left + windowWidth, top + windowHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val screenRadius = 18f * density
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 7, 16, 28)
        canvas.drawRoundRect(screenRect, screenRadius, screenRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.argb(46, 255, 255, 255)
        canvas.drawRoundRect(screenRect, screenRadius, screenRadius, paint)

        val windowRadius = 12f * density
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(56, 78, 150, 232)
        canvas.drawRoundRect(windowRect, windowRadius, windowRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = GlassUi.ACCENT
        canvas.drawRoundRect(windowRect, windowRadius, windowRadius, paint)

        barRect.set(
            windowRect.left,
            windowRect.top,
            windowRect.right,
            windowRect.top + 10f * density
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(130, 78, 150, 232)
        canvas.drawRoundRect(barRect, windowRadius, windowRadius, paint)

        barRect.set(
            windowRect.right - handleSize,
            windowRect.bottom - handleSize,
            windowRect.right - 6f * density,
            windowRect.bottom - 6f * density
        )
        paint.color = GlassUi.ACCENT
        canvas.drawRoundRect(barRect, 4f * density, 4f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nearHandle = event.x >= windowRect.right - handleSize &&
                    event.y >= windowRect.bottom - handleSize
                resizing = nearHandle
                dragging = !nearHandle && windowRect.contains(event.x, event.y)
                if (!dragging && !resizing) return false
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !resizing) return false
                if (screenRect.width() <= 0f || screenRect.height() <= 0f) return true
                val dx = (event.x - lastX) / screenRect.width() * 100f
                val dy = (event.y - lastY) / screenRect.height() * 100f
                lastX = event.x
                lastY = event.y
                layout = if (resizing) {
                    layout.copy(
                        width = (layout.width + dx).toInt().coerceIn(35, 95),
                        height = (layout.height + dy).toInt().coerceIn(35, 90)
                    )
                } else {
                    layout.copy(
                        left = (layout.left + dx).toInt().coerceIn(0, 100 - layout.width),
                        top = (layout.top + dy).toInt().coerceIn(0, 100 - layout.height)
                    )
                }
                onChanged(layout)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = dragging || resizing
                dragging = false
                resizing = false
                return handled
            }
        }
        return false
    }
}
