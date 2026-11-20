package com.manh.panelsassistive

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*

/** Shared appearance defaults for Expansion Panel, brightness and related panels. */
class BackgroundSettingsActivity : Activity() {
    private val primaryText = Color.rgb(244, 246, 250)
    private val muted = Color.rgb(166, 173, 185)
    private val cardColor = Color.argb(38, 210, 230, 250)
    private lateinit var preview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 24, 43), Color.rgb(14, 33, 54), Color.rgb(7, 18, 33)))
        }
        root.addView(TextView(this).apply {
            text = "Nền bảng"
            textSize = 26f
            setTextColor(primaryText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Thiết lập mặc định dùng chung cho bảng mở rộng, thanh độ sáng và các bảng liên quan."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(18))
        })
        root.addView(section("Màu nền"))
        val colors = listOf(Color.rgb(24,41,61), Color.rgb(18,31,48), Color.rgb(35,48,66), Color.rgb(12,24,38))
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        colors.forEach { color ->
            colorRow.addView(Button(this).apply {
                text = "●"
                textSize = 20f
                setTextColor(color)
                setOnClickListener { AppPrefs.setSharedPanelColor(this@BackgroundSettingsActivity, color); updatePreview() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        root.addView(colorRow)
        root.addView(section("Độ trong suốt"))
        root.addView(slider(AppPrefs.sharedPanelOpacity(this), 5, 100) {
            AppPrefs.setSharedPanelOpacity(this, it); updatePreview()
        })
        val blur = Switch(this).apply {
            text = "Bật làm mờ nền"
            setTextColor(primaryText)
            isChecked = AppPrefs.popupBlurEnabled(this@BackgroundSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.setPopupBlurEnabled(this@BackgroundSettingsActivity, checked)
                updatePreview()
            }
        }
        root.addView(blur)
        root.addView(section("Phạm vi làm mờ"))
        val scope = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val screen = RadioButton(this).apply { text = "Làm mờ toàn bộ màn hình phía sau"; setTextColor(primaryText); id = 1 }
        val panel = RadioButton(this).apply { text = "Chỉ làm mờ bên trong bảng"; setTextColor(primaryText); id = 2 }
        scope.addView(screen); scope.addView(panel)
        scope.check(if (AppPrefs.popupBlurScope(this) == AppPrefs.BLUR_SCOPE_PANEL) 2 else 1)
        scope.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setPopupBlurScope(this, if (checked == 2) AppPrefs.BLUR_SCOPE_PANEL else AppPrefs.BLUR_SCOPE_SCREEN)
            FloatBallService.refreshPopupAppearance(this)
        }
        root.addView(scope)
        root.addView(section("Cường độ làm mờ"))
        root.addView(slider(AppPrefs.popupBlurRadius(this), 0, 80) {
            AppPrefs.setPopupBlurRadius(this, it); FloatBallService.refreshPopupAppearance(this)
        })
        root.addView(section("Bo góc"))
        root.addView(slider(AppPrefs.sharedPanelCorner(this), 0, 48) {
            AppPrefs.setSharedPanelCorner(this, it); updatePreview()
        })
        preview = TextView(this).apply {
            text = "Xem trước bảng"
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            textSize = 15f
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }
        root.addView(section("Xem trước"))
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(100)))
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        updatePreview()
    }

    private fun section(title: String) = TextView(this).apply {
        text = title; textSize = 14f; setTextColor(muted)
        setPadding(0, dp(18), 0, dp(8))
    }
    private fun slider(value: Int, min: Int, max: Int, changed: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max - min; progress = value - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) changed(p + min) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }
    private fun updatePreview() {
        val color = AppPrefs.sharedPanelColor(this)
        val opacity = AppPrefs.sharedPanelOpacity(this)
        preview.background = GradientDrawable().apply {
            setColor(Color.argb((opacity * 255 / 100), Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = dp(AppPrefs.sharedPanelCorner(this@BackgroundSettingsActivity)).toFloat()
            setStroke(dp(1), Color.argb(80, 230, 242, 255))
        }
        FloatBallService.refreshPopupAppearance(this)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
