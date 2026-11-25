package com.manh.panelsassistive

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.Normalizer

class SettingsScreen(private val activity: Activity) {
    fun createView(): ScrollView {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            background = GlassUi.pageBackground()
        }

        val rowEntries = mutableListOf<Pair<View, String>>()
        val footer = label(
            "Chạm nhẹ vào từng mục để mở cài đặt chi tiết. Công tắc bên phải bật/tắt nhanh.",
            12,
            GlassUi.TEXT_MUTED
        ).apply { setPadding(dp(4), dp(16), dp(4), 0) }
        val emptyState = label("Không tìm thấy mục cài đặt nào.", 13, GlassUi.TEXT_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(8))
            visibility = View.GONE
        }
        fun filter(rawQuery: String) {
            val query = normalize(rawQuery.trim())
            var visible = 0
            rowEntries.forEach { (row, haystack) ->
                val match = query.isEmpty() || haystack.contains(query)
                row.visibility = if (match) View.VISIBLE else View.GONE
                if (match) visible++
            }
            emptyState.visibility = if (query.isNotEmpty() && visible == 0) View.VISIBLE else View.GONE
            footer.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            label(activity.getString(R.string.brand_name), 11, GlassUi.ACCENT, true).apply {
                letterSpacing = 0.18f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        headerRow.addView(searchField { query -> filter(query) }, LinearLayout.LayoutParams(dp(148), dp(38)))
        content.addView(headerRow)

        content.addView(label(activity.getString(R.string.settings_title), 28, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        content.addView(label(activity.getString(R.string.settings_subtitle), 14, GlassUi.TEXT_SECONDARY))

        fun addRow(
            icon: String,
            title: String,
            subtitle: String,
            checked: Boolean? = null,
            onChecked: ((Boolean) -> Unit)? = null,
            tile: View? = null,
            onClick: () -> Unit
        ) {
            val row = menuRow(icon, title, subtitle, checked, onChecked, tile, onClick)
            content.addView(row)
            rowEntries.add(row to searchText(title, subtitle))
        }

        addRow(
            icon = "circle-dot",
            title = "Bóng nổi",
            subtitle = "Biểu tượng, kích thước, ghim cạnh, ẩn cạnh",
            checked = AppPrefs.floatBallEnabled(activity),
            onChecked = ::setFloatBallEnabled,
            tile = GlassUi.floatBallTile(activity)
        ) {
            activity.startActivity(Intent(activity, FloatBallSettingsActivity::class.java))
        }

        addRow(
            icon = "hand",
            title = "Thao tác và cử chỉ",
            subtitle = "Chạm, chạm đôi, chạm ba, nhấn giữ, ô cài đặt nhanh"
        ) {
            activity.startActivity(Intent(activity, GestureSettingsActivity::class.java))
        }

        addRow(
            icon = "scan-search",
            title = "Khoanh vùng tìm kiếm",
            subtitle = "Độ trễ kích hoạt và rung khi quét"
        ) {
            activity.startActivity(Intent(activity, CircleSearchSettingsActivity::class.java))
        }

        addRow(
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
        }

        addRow(
            icon = "sliders-horizontal",
            title = "Bản mở rộng",
            subtitle = "Thứ tự thanh độ sáng, âm lượng và lối tắt"
        ) {
            activity.startActivity(Intent(activity, ExpansionSettingsActivity::class.java))
        }

        addRow(
            icon = "audio-lines",
            title = "Ghi âm nhanh",
            subtitle = "Nguồn micro, nhãn điều khiển, quyền ghi âm"
        ) {
            activity.startActivity(Intent(activity, RecordingSettingsActivity::class.java))
        }

        addRow(
            icon = "sparkles",
            title = "Hiệu ứng",
            subtitle = "Làm mờ, rung và chuyển động tab"
        ) {
            activity.startActivity(Intent(activity, EffectsSettingsActivity::class.java))
        }

        addRow(
            icon = "layout-grid",
            title = "Widget",
            subtitle = "Âm lượng, độ sáng, đồng hồ, nhạc cho màn hình chính"
        ) {
            activity.startActivity(Intent(activity, WidgetSettingsActivity::class.java))
        }

        addRow(
            icon = "palette",
            title = "Giao diện",
            subtitle = "Bảng màu cho bảng nổi và toàn ứng dụng"
        ) {
            activity.startActivity(Intent(activity, ThemeSettingsActivity::class.java))
        }

        addRow(
            icon = "ban",
            title = "Danh sách đen",
            subtitle = blacklistSubtitle()
        ) {
            showBlacklistSheet()
        }

        addRow(
            icon = "settings-2",
            title = activity.getString(R.string.advanced_settings_title),
            subtitle = "Quyền hệ thống, ô cài đặt nhanh, sao lưu"
        ) {
            activity.startActivity(Intent(activity, AdvancedSettingsActivity::class.java))
        }

        content.addView(emptyState)
        content.addView(footer)

        return ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
    }

    private fun searchField(onQuery: (String) -> Unit): FrameLayout {
        val input = EditText(activity).apply {
            background = null
            hint = "Tìm cài đặt"
            setHintTextColor(GlassUi.TEXT_MUTED)
            setTextColor(GlassUi.TEXT_PRIMARY)
            textSize = 13f
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(34), 0, dp(28), 0)
            includeFontPadding = false
        }
        val clear = LucideGlyphView(activity, "x", GlassUi.TEXT_MUTED).apply {
            visibility = View.GONE
            isClickable = true
            contentDescription = "Xóa tìm kiếm"
            setOnClickListener { input.setText("") }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                onQuery(s?.toString().orEmpty())
            }
        })
        return FrameLayout(activity).apply {
            background = GlassUi.fieldBackground(activity, 19)
            addView(LucideGlyphView(activity, "search", GlassUi.TEXT_MUTED), FrameLayout.LayoutParams(
                dp(16), dp(16), Gravity.START or Gravity.CENTER_VERTICAL
            ).apply { marginStart = dp(12) })
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(clear, FrameLayout.LayoutParams(
                dp(15), dp(15), Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(10) })
        }
    }

    private fun menuRow(
        icon: String,
        title: String,
        subtitle: String,
        checked: Boolean? = null,
        onChecked: ((Boolean) -> Unit)? = null,
        tile: View? = null,
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
        if (tile != null) {
            row.addView(tile)
        } else {
            row.addView(GlassUi.iconTile(activity, icon, sizeDp = 40, glyphDp = 22))
        }
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

    private fun blacklistSubtitle(): String {
        val count = AppPrefs.blacklistedPackages(activity).size
        return if (count == 0) {
            "Tự động ẩn bóng nổi trong ứng dụng đã chọn"
        } else {
            "$count ứng dụng tự động ẩn bóng nổi"
        }
    }

    private fun showBlacklistSheet() {
        val sheet = GlassDialogs.sheet(
            activity,
            "Danh sách đen",
            "Bật ứng dụng để bóng nổi tự ẩn khi bạn đang dùng. Cần quyền trợ năng.",
            "Đóng"
        )
        if (!AccessibilityActionService.isEnabled(activity)) {
            val hint = label(
                "Trợ năng chưa bật nên danh sách đen chưa có tác dụng.",
                12,
                GlassUi.ACCENT
            ).apply { setPadding(0, dp(6), 0, dp(2)) }
            sheet.content.addView(hint)
            sheet.content.addView(GlassUi.actionButton(activity, "Mở cài đặt trợ năng") {
                sheet.dismiss()
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        val packageManager = activity.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != activity.packageName }
            .map { packageName ->
                val label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                }.getOrDefault(packageName)
                packageName to label
            }
            .sortedBy { it.second.lowercase() }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        apps.forEach { (packageName, name) ->
            list.addView(blacklistRow(packageName, name))
        }
        sheet.content.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(340)
            )
        })
        sheet.show()
    }

    private fun blacklistRow(packageName: String, name: String): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        runCatching {
            row.addView(ImageView(activity).apply {
                setImageDrawable(activity.packageManager.getApplicationIcon(packageName))
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
        }
        row.addView(label(name, 14, GlassUi.TEXT_PRIMARY).apply {
            setPadding(dp(10), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(GlassUi.switch(
            activity,
            AppPrefs.isBlacklisted(activity, packageName)
        ) { enabled ->
            AppPrefs.setBlacklisted(activity, packageName, enabled)
            PanelsAccessibilityEnvironment.onAppsChanged(activity)
        })
        return row
    }

    /** Diacritic-insensitive search so "bong noi" also finds "Bóng nổi". */
    private fun searchText(title: String, subtitle: String): String =
        normalize("$title $subtitle")

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

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
