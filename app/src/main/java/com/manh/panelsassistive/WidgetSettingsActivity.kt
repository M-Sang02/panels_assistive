package com.manh.panelsassistive

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class WidgetSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
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
            "Widget màn hình chính",
            "Pin, âm lượng, đồng hồ và nhạc theo phong cách kính của ứng dụng."
        ))
        root.addView(widgetCard(
            "volume-2",
            "Âm lượng",
            "Tự xoay dọc khi kéo cao và ngang khi kéo rộng. Nút + / − chỉnh âm lượng nhạc.",
            GlassVolumeWidget::class.java
        ))
        root.addView(widgetCard(
            "battery-charging",
            "Pin",
            "Hiển thị phần trăm pin và trạng thái sạc, có thanh mức pin theo màu chủ đề.",
            GlassBatteryWidget::class.java
        ))
        root.addView(widgetCard(
            "clock",
            "Đồng hồ",
            "Giờ và thứ trong ngày, tự cập nhật theo múi giờ và định dạng 12/24 giờ của máy.",
            GlassClockWidget::class.java
        ))
        root.addView(widgetCard(
            "music",
            "Nhạc",
            "Nút bài trước, phát/tạm dừng và bài tiếp theo cho trình phát nhạc đang hoạt động.",
            GlassMusicWidget::class.java
        ))
        root.addView(styleCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun widgetCard(
        icon: String,
        title: String,
        description: String,
        provider: Class<*>
    ): LinearLayout = GlassUi.glassCard(this).apply {
        addView(LinearLayout(this@WidgetSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@WidgetSettingsActivity, icon, sizeDp = 38, glyphDp = 20))
            addView(GlassUi.cardTitle(this@WidgetSettingsActivity, title).apply {
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(hint(description))
        addView(GlassUi.actionButton(
            this@WidgetSettingsActivity,
            "Thêm $title",
            prominent = true
        ) { pin(provider) })
    }

    private fun styleCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Giao diện của widget"))
        addView(hint(
            "Widget dùng chung bảng màu ở mục Giao diện. Nhấn giữ widget trên màn hình " +
                "chính để đổi kích thước; kéo cao để Âm lượng chuyển sang bố cục dọc."
        ))
        addView(GlassUi.actionButton(
            this@WidgetSettingsActivity,
            "Mở Giao diện"
        ) {
            startActivity(Intent(this@WidgetSettingsActivity, ThemeSettingsActivity::class.java))
        })
    }

    private fun pin(provider: Class<*>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(
                this,
                "Máy chưa hỗ trợ thêm widget trực tiếp. Nhấn giữ màn hình chính → Widget → Panels Assistive.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(
                this,
                "Launcher này không hỗ trợ thêm trực tiếp. Hãy nhấn giữ màn hình chính → Widget → Panels Assistive.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val requested = try {
            manager.requestPinAppWidget(ComponentName(this, provider), null, null)
        } catch (error: IllegalStateException) {
            android.util.Log.w("WidgetSettings", "Pin request failed", error)
            false
        }
        Toast.makeText(
            this,
            if (requested) "Xác nhận thêm widget trên màn hình chính." else "Không thể thêm widget lúc này.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun hint(text: String): TextView = label(text, 12, GlassUi.TEXT_MUTED).apply {
        setPadding(0, dp(6), 0, dp(4))
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
