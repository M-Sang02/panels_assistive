package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Shared appearance defaults for the utility panel, expansion panel and related panels. */
class BackgroundSettingsActivity : Activity() {
    private lateinit var preview: TextView
    private lateinit var opacityValue: TextView
    private lateinit var cornerValue: TextView
    private lateinit var blurValue: TextView
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
            "Thiết lập mặc định dùng chung cho bảng tiện ích, bảng mở rộng, thanh độ sáng và các bảng liên quan.",
            13f,
            GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, 0, 0, dp(6)) })

        root.addView(GlassUi.glassCard(this).apply {
            addView(fieldLabel("Màu nền"))
            addView(colorRow())
        })

        root.addView(GlassUi.glassCard(this).apply {
            val header = valueRow("Độ trong suốt")
            opacityValue = header.second
            addView(header.first)
            addView(slider(AppPrefs.sharedPanelOpacity(this@BackgroundSettingsActivity), 5, 100, { opacity ->
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

        val blurOptions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (AppPrefs.popupBlurEnabled(this@BackgroundSettingsActivity)) View.VISIBLE else View.GONE
            addView(fieldLabel("Phạm vi làm mờ").apply { setPadding(0, dp(10), 0, 0) })
            val scope = RadioGroup(this@BackgroundSettingsActivity).apply {
                orientation = RadioGroup.VERTICAL
                val screen = styledRadio("Làm mờ toàn bộ màn hình phía sau").apply { id = 1 }
                val panel = styledRadio("Chỉ làm mờ bên trong bảng").apply { id = 2 }
                addView(screen)
                addView(panel)
                check(
                    if (AppPrefs.popupBlurScope(this@BackgroundSettingsActivity) ==
                        AppPrefs.BLUR_SCOPE_PANEL
                    ) 2 else 1
                )
                setOnCheckedChangeListener { _, checked ->
                    AppPrefs.setPopupBlurScope(
                        this@BackgroundSettingsActivity,
                        if (checked == 2) AppPrefs.BLUR_SCOPE_PANEL else AppPrefs.BLUR_SCOPE_SCREEN
                    )
                    FloatBallService.refreshPopupAppearance(this@BackgroundSettingsActivity)
                }
            }
            addView(scope)
            val header = valueRow("Cường độ làm mờ").also { blurValue = it.second }
            header.first.setPadding(0, dp(10), 0, 0)
            addView(header.first)
            addView(slider(AppPrefs.popupBlurRadius(this@BackgroundSettingsActivity), 0, 80, { radius ->
                AppPrefs.setPopupBlurRadius(this@BackgroundSettingsActivity, radius)
                blurValue.text = radius.toString()
            }, { FloatBallService.refreshPopupAppearance(this@BackgroundSettingsActivity) }))
        }

        root.addView(GlassUi.glassCard(this).apply {
            addView(GlassUi.switch(
                this@BackgroundSettingsActivity,
                AppPrefs.popupBlurEnabled(this@BackgroundSettingsActivity)
            ) { enabled ->
                AppPrefs.setPopupBlurEnabled(this@BackgroundSettingsActivity, enabled)
                blurOptions.visibility = if (enabled) View.VISIBLE else View.GONE
                refreshPanels()
            }.apply {
                text = "Bật làm mờ nền"
                setTextColor(GlassUi.TEXT_PRIMARY)
                textSize = 14f
            })
            addView(body(
                "Tuỳ chọn tăng cường trên thiết bị hỗ trợ. Mặc định tắt để giao diện giả kính luôn ổn định.",
                12f,
                GlassUi.TEXT_MUTED
            ).apply { setPadding(0, dp(2), 0, 0) })
            addView(blurOptions)
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

    private fun styledRadio(label: String): RadioButton = RadioButton(this).apply {
        text = label
        textSize = 14f
        setTextColor(GlassUi.TEXT_PRIMARY)
        buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(GlassUi.ACCENT, Color.argb(255, 158, 168, 182))
        )
        setPadding(0, dp(4), 0, dp(4))
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

    private fun colorRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        AppPrefs.panelPalette.forEach { color ->
            val chip = View(this@BackgroundSettingsActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                isClickable = true
                contentDescription = "Màu nền"
                setOnClickListener {
                    AppPrefs.setSharedPanelColor(this@BackgroundSettingsActivity, color)
                    updatePreview()
                    refreshPanels()
                }
            }
            colorChips.add(color to chip)
            addView(chip, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(6)
            })
        }
        addView(ImageButton(this@BackgroundSettingsActivity).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(GlassUi.TEXT_PRIMARY)
            background = GlassUi.fieldBackground(this@BackgroundSettingsActivity, 12)
            contentDescription = "Màu nền tùy chỉnh"
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { showCustomColorPicker() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
    }

    private fun showCustomColorPicker() {
        val initial = AppPrefs.sharedPanelColor(this)
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val plane = CustomColorPlane(this, hsv[0], hsv[1], hsv[2]) { color ->
            AppPrefs.setSharedPanelColor(this, color)
            updatePreview()
            refreshPanels()
        }
        container.addView(plane, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(220)
        ))
        val hue = GlassUi.slider(this).apply {
            max = 359
            progress = hsv[0].toInt().coerceIn(0, 359)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) plane.setHue(progress.toFloat())
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(hue, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply { topMargin = dp(8) })
        AlertDialog.Builder(this)
            .setTitle("Màu nền tùy chỉnh")
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateValues() {
        opacityValue.text = "${AppPrefs.sharedPanelOpacity(this)}%"
        cornerValue.text = "${AppPrefs.sharedPanelCorner(this)}dp"
        blurValue.text = AppPrefs.popupBlurRadius(this).toString()
    }

    private fun refreshPanels() {
        FloatBallService.refreshPopupAppearance(this)
    }

    private fun updatePreview() {
        renderPreview()
        updateValues()
    }

    private fun renderPreview() {
        val color = AppPrefs.sharedPanelColor(this)
        val opacity = AppPrefs.sharedPanelOpacity(this)
        val alpha = (opacity * 255 / 100).coerceIn(0, 255)
        preview.background = GradientDrawable().apply {
            setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = dp(AppPrefs.sharedPanelCorner(this@BackgroundSettingsActivity)).toFloat()
            setStroke(dp(1), Color.argb(65, 225, 238, 250))
        }
        colorChips.forEach { (chipColor, chip) ->
            (chip.background as? GradientDrawable)?.setStroke(
                dp(2),
                if (chipColor == color) GlassUi.TEXT_PRIMARY else Color.argb(80, 255, 255, 255)
            )
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

    private class CustomColorPlane(
        context: android.content.Context,
        private var hue: Float,
        private var saturation: Float,
        private var value: Float,
        private val onColor: (Int) -> Unit
    ) : View(context) {
        private val paint = Paint()
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun setHue(value: Float) {
            hue = value
            invalidate()
            emitColor()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val step = dp(6).coerceAtLeast(4)
            var y = 0
            while (y < height) {
                val yFraction = if (height > 0) y.toFloat() / height else 0f
                var x = 0
                while (x < width) {
                    val xFraction = if (width > 0) x.toFloat() / width else 0f
                    val valueFraction = 1f - yFraction
                    paint.color = Color.HSVToColor(floatArrayOf(hue, xFraction, valueFraction))
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + step).toFloat().coerceAtMost(width.toFloat()),
                        (y + step).toFloat().coerceAtMost(height.toFloat()),
                        paint
                    )
                    x += step
                }
                y += step
            }
            val markerX = saturation * width
            val markerY = (1f - value) * height
            canvas.drawCircle(markerX, markerY, dp(7).toFloat(), marker)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                event.actionMasked == android.view.MotionEvent.ACTION_MOVE
            ) {
                saturation = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                value = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                emitColor()
                return true
            }
            return super.onTouchEvent(event)
        }

        private fun emitColor() {
            onColor(Color.HSVToColor(floatArrayOf(hue, saturation, value)))
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
