package com.manh.panelsassistive

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class EffectsSettingsActivity : Activity() {
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
            getString(R.string.effects_settings_title),
            "Làm mờ, rung phản hồi và chuyển động giữa các tab."
        ))
        root.addView(panelSurfaceCard())
        root.addView(hapticsCard())
        root.addView(transitionCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun panelSurfaceCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        addView(cardHeader("layers", "Nền & làm mờ bảng"))
        addView(hint(
            if (supported) getString(R.string.popup_blur_description)
            else "Thiết bị chưa hỗ trợ làm mờ thời gian thực."
        ))
        val controls = LinearLayout(this@EffectsSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (AppPrefs.popupBlurEnabled(this@EffectsSettingsActivity) && supported) {
                View.VISIBLE
            } else View.GONE
        }
        val scopeRow = LinearLayout(this@EffectsSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val intensityValue = label(
            getString(R.string.blur_intensity) + ": " + AppPrefs.popupBlurRadius(this@EffectsSettingsActivity),
            12,
            GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, dp(8), 0, 0) }
        val intensity = GlassUi.slider(this@EffectsSettingsActivity).apply {
            max = 80
            progress = AppPrefs.popupBlurRadius(this@EffectsSettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    AppPrefs.setPopupBlurRadius(this@EffectsSettingsActivity, progress)
                    intensityValue.text = getString(R.string.blur_intensity) + ": $progress"
                    FloatBallService.refreshPopupAppearance(this@EffectsSettingsActivity)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        lateinit var screenChip: TextView
        lateinit var panelChip: TextView
        fun refreshScope() {
            val panel = AppPrefs.popupBlurScope(this@EffectsSettingsActivity) == AppPrefs.BLUR_SCOPE_PANEL
            screenChip.isSelected = !panel
            screenChip.setTextColor(if (!panel) GlassUi.ACCENT else GlassUi.TEXT_MUTED)
            screenChip.background = GlassUi.tabSurface(this@EffectsSettingsActivity, !panel)
            panelChip.isSelected = panel
            panelChip.setTextColor(if (panel) GlassUi.ACCENT else GlassUi.TEXT_MUTED)
            panelChip.background = GlassUi.tabSurface(this@EffectsSettingsActivity, panel)
        }
        fun chooseScope(panel: Boolean) {
            AppPrefs.setPopupBlurScope(
                this@EffectsSettingsActivity,
                if (panel) AppPrefs.BLUR_SCOPE_PANEL else AppPrefs.BLUR_SCOPE_SCREEN
            )
            refreshScope()
            FloatBallService.refreshPopupAppearance(this@EffectsSettingsActivity)
        }
        screenChip = chip(getString(R.string.popup_blur_scope_screen)) { chooseScope(false) }
        panelChip = chip(getString(R.string.popup_blur_scope_panel)) { chooseScope(true) }
        // Two full-width rows so the longer Vietnamese labels stay readable.
        scopeRow.addView(screenChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        scopeRow.addView(panelChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply { topMargin = dp(6) })
        controls.addView(label(getString(R.string.popup_blur_scope), 13, GlassUi.TEXT_SECONDARY).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        controls.addView(scopeRow)
        controls.addView(intensityValue)
        controls.addView(intensity)
        refreshScope()
        addView(GlassUi.switch(
            this@EffectsSettingsActivity,
            AppPrefs.popupBlurEnabled(this@EffectsSettingsActivity) && supported
        ) { enabled ->
            AppPrefs.setPopupBlurEnabled(this@EffectsSettingsActivity, enabled)
            controls.visibility = if (enabled) View.VISIBLE else View.GONE
            intensity.isEnabled = enabled
            FloatBallService.refreshPopupAppearance(this@EffectsSettingsActivity)
        }.apply {
            text = getString(R.string.popup_blur)
            setTextColor(GlassUi.TEXT_PRIMARY)
            setPadding(0, dp(6), 0, dp(6))
            isEnabled = supported
        })
        addView(controls)
        addView(GlassUi.divider(this@EffectsSettingsActivity))
        addView(hint("Màu nền, độ trong suốt, bo góc và viền trắng dùng chung cho bảng mở rộng và thanh độ sáng."))
        addView(GlassUi.actionButton(
            this@EffectsSettingsActivity,
            getString(R.string.background_panel_settings)
        ) {
            startActivity(Intent(this@EffectsSettingsActivity, BackgroundSettingsActivity::class.java))
        })
    }

    private fun hapticsCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("vibrate", getString(R.string.haptic_feedback)))
        addView(hint(getString(R.string.haptic_feedback_description)))
        val strengthValue = label(
            getString(R.string.haptic_strength) + ": " + AppPrefs.hapticStrength(this@EffectsSettingsActivity) + "%",
            12,
            GlassUi.TEXT_SECONDARY
        )
        val strength = GlassUi.slider(this@EffectsSettingsActivity).apply {
            max = 99
            progress = AppPrefs.hapticStrength(this@EffectsSettingsActivity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = progress + 1
                    AppPrefs.setHapticStrength(this@EffectsSettingsActivity, value)
                    strengthValue.text = getString(R.string.haptic_strength) + ": $value%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val controls = LinearLayout(this@EffectsSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (AppPrefs.hapticsEnabled(this@EffectsSettingsActivity)) View.VISIBLE else View.GONE
            addView(strengthValue)
            addView(strength)
        }
        addView(GlassUi.switch(
            this@EffectsSettingsActivity,
            AppPrefs.hapticsEnabled(this@EffectsSettingsActivity)
        ) { enabled ->
            AppPrefs.setHapticsEnabled(this@EffectsSettingsActivity, enabled)
            controls.visibility = if (enabled) View.VISIBLE else View.GONE
        }.apply {
            text = getString(R.string.haptic_feedback)
            setTextColor(GlassUi.TEXT_PRIMARY)
            setPadding(0, dp(6), 0, dp(6))
        })
        addView(controls)
    }

    private fun transitionCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("sparkles", "Chuyển động giữa các tab"))
        addView(hint("Hiệu ứng khi bạn chuyển tab trong bảng tiện ích."))
        val preview = TextView(this@EffectsSettingsActivity).apply {
            text = "Tab A → Tab B"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(GlassUi.TEXT_PRIMARY)
            background = GlassUi.glassSurface(this@EffectsSettingsActivity, 14)
            minHeight = dp(56)
        }
        val row = LinearLayout(this@EffectsSettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val options = listOf(
            "none" to "Không",
            "fade" to "Mờ dần",
            "slide" to "Trượt",
            "flip" to "Lật thẻ"
        )
        val chips = options.map { (key, text) ->
            chip(text) {
                AppPrefs.setTabTransition(this@EffectsSettingsActivity, key)
                render()
            }.apply {
                isSelected = AppPrefs.tabTransition(this@EffectsSettingsActivity) == key
            }
        }
        chips.forEachIndexed { index, view ->
            view.text = options[index].second
            val transition = AppPrefs.tabTransition(this@EffectsSettingsActivity)
            val selected = transition == options[index].first
            view.setTextColor(if (selected) GlassUi.ACCENT else GlassUi.TEXT_MUTED)
            view.background = GlassUi.tabSurface(this@EffectsSettingsActivity, selected)
            row.addView(view, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index != chips.lastIndex) marginEnd = dp(4)
            })
        }
        addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        addView(GlassUi.actionButton(
            this@EffectsSettingsActivity,
            "Xem thử"
        ) {
            when (AppPrefs.tabTransition(this@EffectsSettingsActivity)) {
                "none" -> Unit
                "fade" -> {
                    preview.alpha = 0f
                    preview.animate().alpha(1f).setDuration(180L).start()
                }
                "slide" -> {
                    preview.translationX = dp(26).toFloat()
                    preview.alpha = 0.35f
                    preview.animate().translationX(0f).alpha(1f).setDuration(220L).start()
                }
                "flip" -> {
                    preview.cameraDistance = dp(8000).toFloat()
                    preview.rotationY = 65f
                    preview.alpha = 0.4f
                    preview.animate().rotationY(0f).alpha(1f).setDuration(240L).start()
                }
            }
        })
    }

    private fun cardHeader(icon: String, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@EffectsSettingsActivity, icon, sizeDp = 36, glyphDp = 20))
            addView(
                GlassUi.cardTitle(this@EffectsSettingsActivity, title).apply {
                    setPadding(dp(12), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

    private fun chip(text: CharSequence, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(9), dp(8), dp(9))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

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
