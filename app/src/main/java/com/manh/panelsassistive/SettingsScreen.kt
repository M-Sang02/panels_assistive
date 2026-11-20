package com.manh.panelsassistive

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsScreen(private val activity: Activity) {
    fun createView(): ScrollView {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            background = GlassUi.pageBackground()
        }

        content.addView(label(activity.getString(R.string.brand_name), 11, GlassUi.ACCENT, true).apply {
            letterSpacing = 0.18f
        })
        content.addView(label(activity.getString(R.string.settings_title), 28, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        content.addView(label(activity.getString(R.string.settings_subtitle), 14, GlassUi.TEXT_SECONDARY))

        content.addView(menuRow(
            icon = "circle-dot",
            title = "Bóng nổi",
            subtitle = "Biểu tượng, kích thước, ghim cạnh, ẩn cạnh",
            checked = AppPrefs.floatBallEnabled(activity),
            onChecked = ::setFloatBallEnabled
        ) {
            activity.startActivity(Intent(activity, FloatBallSettingsActivity::class.java))
        })

        content.addView(menuRow(
            icon = "square-stack",
            title = "Bảng tiện ích",
            subtitle = "Bố cục, kích thước ô và nền riêng",
            checked = AppPrefs.widgetPopupEnabled(activity),
            onChecked = { enabled ->
                AppPrefs.setWidgetPopupEnabled(activity, enabled)
                if (!enabled && FloatBallService.running) {
                    activity.startService(Intent(activity, FloatBallService::class.java).apply {
                        action = FloatBallService.ACTION_HIDE_POPUP
                    })
                }
            }
        ) {
            activity.startActivity(
                Intent(activity, WidgetPanelSettingsActivity::class.java)
                    .putExtra(WidgetPanelSettingsActivity.EXTRA_LANDSCAPE_LAYOUT, false)
            )
        })

        content.addView(menuRow(
            icon = "mic",
            title = "Ghi âm nhanh",
            subtitle = "Nguồn micro, nhãn điều khiển, quyền ghi âm"
        ) {
            activity.startActivity(Intent(activity, RecordingSettingsActivity::class.java))
        })

        content.addView(menuRow(
            icon = "hand",
            title = "Thao tác và cử chỉ",
            subtitle = "Chạm, chạm đôi, chạm ba, nhấn giữ"
        ) {
            activity.startActivity(Intent(activity, GestureSettingsActivity::class.java))
        })

        content.addView(menuRow(
            icon = "sparkles",
            title = "Hiệu ứng",
            subtitle = "Làm mờ, rung và chuyển động tab"
        ) {
            activity.startActivity(Intent(activity, EffectsSettingsActivity::class.java))
        })

        content.addView(menuRow(
            icon = "settings-2",
            title = activity.getString(R.string.advanced_settings_title),
            subtitle = "Quyền hệ thống, ô cài đặt nhanh, sao lưu"
        ) {
            activity.startActivity(Intent(activity, AdvancedSettingsActivity::class.java))
        })

        content.addView(label(
            "Chạm nhẹ vào từng mục để mở cài đặt chi tiết. Công tắc bên phải bật/tắt nhanh.",
            12,
            GlassUi.TEXT_MUTED
        ).apply { setPadding(dp(4), dp(16), dp(4), 0) })

        return ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
    }

    private fun menuRow(
        icon: String,
        title: String,
        subtitle: String,
        checked: Boolean? = null,
        onChecked: ((Boolean) -> Unit)? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(12), dp(14))
            background = GlassUi.glassSurface(activity, 20)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(GlassUi.iconTile(activity, icon, sizeDp = 40, glyphDp = 22))
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(label(title, 15, GlassUi.TEXT_PRIMARY, true))
            addView(label(subtitle, 12, GlassUi.TEXT_MUTED).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (checked != null) {
            row.addView(GlassUi.switch(activity, checked) { enabled -> onChecked?.invoke(enabled) })
        }
        row.addView(LucideGlyphView(activity, "chevron-right", GlassUi.TEXT_MUTED).apply {
            alpha = 0.7f
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
            bottomMargin = dp(2)
        }
        return row
    }

    private fun setFloatBallEnabled(enabled: Boolean) {
        if (!enabled) {
            FloatBallService.stop(activity)
        } else if (Settings.canDrawOverlays(activity)) {
            FloatBallService.start(activity)
        } else {
            AppPrefs.setPendingOverlayEnable(activity, true)
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }
    }

    private fun label(
        text: String,
        sizeSp: Int,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(activity).apply {
        this.text = text
        textSize = sizeSp.toFloat()
        setTextColor(color)
        gravity = Gravity.START
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = GlassUi.dp(activity, value)
}
