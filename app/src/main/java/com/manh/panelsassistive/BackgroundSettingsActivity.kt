package com.manh.panelsassistive

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Shared appearance defaults for the brightness slider and expansion panel. */
class BackgroundSettingsActivity : Activity() {
    private lateinit var preview: TextView
    private lateinit var opacityValue: TextView
    private lateinit var cornerValue: TextView
    private lateinit var borderAlphaValue: TextView
    private lateinit var borderWidthValue: TextView
    private val colorChips = mutableListOf<Pair<Int, View>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            background = GlassUi.pageBackground()
        }
        root.addView(body(getString(R.string.brand_name), 11f, GlassUi.ACCENT, bold = true).apply {
            letterSpacing = 0.18f
        })
        root.addView(body("Nền bảng", 24f, GlassUi.TEXT_PRIMARY, bold = true).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        root.addView(body(
            "Áp dụng cho thanh độ sáng dọc và bảng mở rộng. Bảng tiện ích có cài đặt nền riêng trong mục Bảng tiện ích.",
            13f,
            GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, 0, 0, dp(6)) })

        root.addView(GlassUi.glassCard(this).apply {
            addView(fieldLabel("Màu nền"))
            addView(GlassUi.paletteRow(
                this@BackgroundSettingsActivity,
                AppPrefs.panelPalette,
                AppPrefs.sharedPanelColor(this@BackgroundSettingsActivity),
                "Màu nền",
                colorChips
            ) { color ->
                AppPrefs.setSharedPanelColor(this@BackgroundSettingsActivity, color)
                renderPreview()
                refreshPanels()
            })
        })

        root.addView(GlassUi.glassCard(this).apply {
            val header = valueRow("Độ trong suốt")
            opacityValue = header.second
            addView(header.first)
            addView(slider(AppPrefs.sharedPanelOpacity(this@BackgroundSettingsActivity), 0, 100, { opacity ->
                AppPrefs.setSharedPanelOpacity(this@BackgroundSettingsActivity, opacity)
                opacityValue.text = "$opacity%"
                renderPreview()
            }, ::refreshPanels))
        })

        root.addView(GlassUi.glassCard(this).apply {
            val header = valueRow("Bo góc")
            cornerValue = header.second
            addView(header.first)
            addView(slider(AppPrefs.sharedPanelCorner(this@BackgroundSettingsActivity), 0, 48, { corner ->
                AppPrefs.setSharedPanelCorner(this@BackgroundSettingsActivity, corner)
                cornerValue.text = "${corner}dp"
                renderPreview()
            }, ::refreshPanels))
        })

        root.addView(GlassUi.glassCard(this).apply {
            val borderRow = LinearLayout(this@BackgroundSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    body("Viền trắng", 14f, GlassUi.TEXT_PRIMARY, bold = true),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(GlassUi.switch(
                    this@BackgroundSettingsActivity,
                    AppPrefs.panelBorderEnabled(this@BackgroundSettingsActivity)
                ) { enabled ->
                    AppPrefs.setPanelBorderEnabled(this@BackgroundSettingsActivity, enabled)
                    renderPreview()
                    refreshPanels()
                }.apply { contentDescription = "Bật tắt viền trắng" })
            }
            addView(borderRow)
            addView(body(
                "Tắt để nền bảng không còn đường viền sáng. Khi bật, chỉnh được độ trong suốt " +
                    "và độ dày bên dưới. Áp dụng cho thanh độ sáng và bảng mở rộng; " +
                    "bảng tiện ích có viền riêng.",
                12f,
                GlassUi.TEXT_MUTED
            ))
            val alphaHeader = valueRow("Độ đậm viền")
            borderAlphaValue = alphaHeader.second
            addView(alphaHeader.first.apply { setPadding(0, dp(12), 0, 0) })
            addView(slider(borderAlphaPercent(), 0, 100, { percent ->
                AppPrefs.setPanelBorderAlpha(
                    this@BackgroundSettingsActivity,
                    (percent * 255 / 100).coerceIn(0, 255)
                )
                borderAlphaValue.text = "$percent%"
                renderPreview()
            }, ::refreshPanels))
            val widthHeader = valueRow("Độ dày viền")
            borderWidthValue = widthHeader.second
            addView(widthHeader.first.apply { setPadding(0, dp(10), 0, 0) })
            addView(slider(borderWidthProgress(), 0, 5, { step ->
                val width = 0.5f + step * 0.5f
                AppPrefs.setPanelBorderWidth(this@BackgroundSettingsActivity, width)
                borderWidthValue.text = widthLabel(width)
                renderPreview()
            }, ::refreshPanels))
        })

        preview = TextView(this).apply {
            text = "Xem trước bảng"
            gravity = Gravity.CENTER
            setTextColor(GlassUi.TEXT_PRIMARY)
            textSize = 14f
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }
        root.addView(GlassUi.glassCard(this).apply {
            addView(fieldLabel("Xem trước"))
            addView(preview, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(100)
            ).apply { topMargin = dp(8) })
        })

        setContentView(ScrollView(this).apply { addView(root) })
        updateValues()
        renderPreview()
    }

    private fun fieldLabel(text: String): TextView =
        body(text, 13f, GlassUi.TEXT_SECONDARY, bold = true)

    private fun valueRow(label: String): Pair<LinearLayout, TextView> {
        val value = body("", 13f, GlassUi.ACCENT, bold = true)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(fieldLabel(label), LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(value)
        }
        return header to value
    }

    private fun slider(
        value: Int,
        min: Int,
        max: Int,
        changed: (Int) -> Unit,
        finished: () -> Unit
    ): SeekBar = GlassUi.slider(this).apply {
        this.max = max - min
        progress = (value - min).coerceIn(0, max - min)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) changed(p + min)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = finished()
        })
    }

    private fun updateValues() {
        opacityValue.text = "${AppPrefs.sharedPanelOpacity(this)}%"
        cornerValue.text = "${AppPrefs.sharedPanelCorner(this)}dp"
        borderAlphaValue.text = "${borderAlphaPercent()}%"
        borderWidthValue.text = widthLabel(AppPrefs.panelBorderWidth(this))
    }

    private fun borderAlphaPercent(): Int = AppPrefs.panelBorderAlpha(this) * 100 / 255

    /** Slider position: 0..5 maps to 0.5dp..3dp. */
    private fun borderWidthProgress(): Int =
        ((AppPrefs.panelBorderWidth(this) - 0.5f) / 0.5f).toInt().coerceIn(0, 5)

    private fun widthLabel(width: Float): String {
        val rounded = (width * 2).toInt() / 2f
        return if (rounded % 1f == 0f) "${rounded.toInt()}dp" else "$rounded dp"
    }

    private fun refreshPanels() {
        FloatBallService.refreshPopupAppearance(this)
    }

    private fun renderPreview() {
        val color = AppPrefs.sharedPanelColor(this)
        val opacity = AppPrefs.sharedPanelOpacity(this)
        val alpha = (opacity * 255 / 100).coerceIn(0, 255)
        preview.background = GradientDrawable().apply {
            setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = dp(AppPrefs.sharedPanelCorner(this@BackgroundSettingsActivity)).toFloat()
            GlassUi.applyPanelBorder(this@BackgroundSettingsActivity, this)
        }
        colorChips.forEach { (chipColor, chip) ->
            GlassUi.paintSwatch(this, chip, chipColor, chipColor == color)
        }
    }

    private fun body(
        text: CharSequence,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)
}
