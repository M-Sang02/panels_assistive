package com.manh.panelsassistive

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Trigger timing for the native Circle to Search action. */
class CircleSearchSettingsActivity : Activity() {
    private lateinit var delayValue: TextView

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
            "Khoanh vùng tìm kiếm",
            "Thời điểm gọi Circle to Search và phản hồi rung."
        ))
        root.addView(triggerCard())
        root.addView(infoCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun triggerCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("timer", "Độ trễ kích hoạt"))
        addView(hint("Chờ một chút sau khi bảng và bóng nổi ẩn để ảnh chụp màn hình sạch trước khi quét."))
        delayValue = TextView(this@CircleSearchSettingsActivity).apply {
            text = "${AppPrefs.circleSearchDelay(this@CircleSearchSettingsActivity)} ms"
            textSize = 22f
            setTextColor(GlassUi.ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, dp(8), 0, dp(2))
        }
        addView(delayValue)
        addView(GlassUi.slider(this@CircleSearchSettingsActivity).apply {
            max = DELAY_STEPS
            progress = AppPrefs.circleSearchDelay(this@CircleSearchSettingsActivity) / DELAY_STEP_MS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = progress * DELAY_STEP_MS
                    AppPrefs.setCircleSearchDelay(this@CircleSearchSettingsActivity, value)
                    delayValue.text = "$value ms"
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        addView(rangeRow())
        addView(GlassUi.divider(this@CircleSearchSettingsActivity))
        addView(GlassUi.switch(
            this@CircleSearchSettingsActivity,
            AppPrefs.circleSearchVibrate(this@CircleSearchSettingsActivity)
        ) { checked ->
            AppPrefs.setCircleSearchVibrate(this@CircleSearchSettingsActivity, checked)
        }.apply {
            text = "Rung khi kích hoạt"
            textSize = 15f
            setTextColor(GlassUi.TEXT_PRIMARY)
            setPadding(0, dp(10), 0, dp(2))
        })
    }

    private fun infoCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("info", "Cần biết"))
        addView(hint("Circle to Search do hệ thống vẽ và quét, nên Google phải là trợ lý mặc định (tắt thay thế trợ lý của Panels) và máy phải hỗ trợ Circle to Search. Nếu không, thao tác sẽ báo lỗi."))
    }

    private fun rangeRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(this@CircleSearchSettingsActivity).apply {
            text = "0 ms"
            textSize = 11f
            setTextColor(GlassUi.TEXT_MUTED)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@CircleSearchSettingsActivity).apply {
            text = "2000 ms"
            textSize = 11f
            setTextColor(GlassUi.TEXT_MUTED)
            gravity = Gravity.END
        })
    }

    private fun cardHeader(icon: String, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@CircleSearchSettingsActivity, icon, sizeDp = 36, glyphDp = 20))
            addView(
                TextView(this@CircleSearchSettingsActivity).apply {
                    text = title
                    textSize = 16f
                    setTextColor(GlassUi.TEXT_PRIMARY)
                    setTypeface(typeface, Typeface.BOLD)
                    includeFontPadding = false
                    setPadding(dp(10), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(GlassUi.TEXT_MUTED)
        includeFontPadding = false
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)

    companion object {
        private const val DELAY_STEP_MS = 50
        private const val DELAY_STEPS = 2000 / DELAY_STEP_MS
    }
}
