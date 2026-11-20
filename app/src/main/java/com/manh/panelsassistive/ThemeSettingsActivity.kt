package com.manh.panelsassistive

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ThemeSettingsActivity : Activity() {
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
            "Giao diện",
            "Một bảng màu áp dụng cho widget, bảng nổi và toàn ứng dụng."
        ))
        root.addView(paletteCard())
        root.addView(widgetLinkCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun paletteCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@ThemeSettingsActivity, "Bảng màu"))
        addView(hint("Chọn bảng màu để đổi màu nhấn, nền các bảng nổi, status bar và widget."))

        val current = AppThemes.current(this@ThemeSettingsActivity)
        AppThemes.ALL.forEach { theme ->
            addView(themeRow(theme, theme.key == current.key))
        }

        addView(hint(
            "Áp dụng bảng màu sẽ ghi đè màu nền bảng bạn đã tự chỉnh trong " +
                "Bảng tiện ích và Hiệu ứng."
        ))
    }

    private fun themeRow(theme: AppTheme, selected: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GlassUi.glassSurface(this@ThemeSettingsActivity, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { applyTheme(theme) }
            addView(preview(theme), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@ThemeSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(label(theme.label, 14, GlassUi.TEXT_PRIMARY, true))
                addView(label("Màu nhấn " + hex(theme.accent), 11, GlassUi.TEXT_MUTED).apply {
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) {
                addView(LucideGlyphView(this@ThemeSettingsActivity, "check", GlassUi.ACCENT),
                    LinearLayout.LayoutParams(dp(18), dp(18)))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun preview(theme: AppTheme): FrameLayout =
        FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(theme.pageTop, theme.pageMid, theme.pageBottom)
            ).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), (theme.accent and 0x00FFFFFF) or 0x72000000)
            }
            addView(TextView(this@ThemeSettingsActivity).apply {
                text = "Aa"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(theme.accent)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

    private fun widgetLinkCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@ThemeSettingsActivity, "Widget dùng chung bảng màu"))
        addView(hint("Đồng hồ, pin, âm lượng và nhạc trên màn hình chính đổi màu theo bảng màu này."))
        addView(GlassUi.actionButton(
            this@ThemeSettingsActivity,
            "Mở cài đặt widget"
        ) {
            startActivity(Intent(this@ThemeSettingsActivity, WidgetSettingsActivity::class.java))
        })
    }

    private fun applyTheme(theme: AppTheme) {
        AppThemes.apply(this, theme)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        render()
        Toast.makeText(this, "Đã áp dụng bảng màu ${theme.label}", Toast.LENGTH_SHORT).show()
    }

    private fun hex(color: Int) =
        "#" + Integer.toHexString(color and 0xFFFFFF).padStart(6, '0').uppercase()

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
