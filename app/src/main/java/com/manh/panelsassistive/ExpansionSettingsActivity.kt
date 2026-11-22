package com.manh.panelsassistive

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ExpansionSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
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
            "Bản mở rộng",
            "Chọn thanh hiển thị, sắp thứ tự và quản lý lối tắt."
        ))
        root.addView(sliderCard())
        root.addView(shortcutCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sliderCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("sliders-horizontal", "Thanh điều khiển"))
        addView(hint("Ẩn hoặc đổi thứ tự các thanh trong bảng mở rộng. Media mặc định nằm ngoài cùng bên phải."))
        val visible = AppPrefs.expansionSliders(this@ExpansionSettingsActivity)
        val hidden = AppPrefs.EXPANSION_SLIDER_KEYS.filter { it !in visible }
        (visible + hidden).forEach { key ->
            val shown = key in visible
            val row = LinearLayout(this@ExpansionSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(8), dp(8))
                background = GlassUi.glassSurface(this@ExpansionSettingsActivity, 14)
                alpha = if (shown) 1f else 0.55f
                addView(LucideGlyphView(this@ExpansionSettingsActivity, sliderIcon(key), GlassUi.ACCENT),
                    LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(TextView(this@ExpansionSettingsActivity).apply {
                    text = sliderLabel(key)
                    textSize = 14f
                    setTextColor(GlassUi.TEXT_PRIMARY)
                    setPadding(dp(10), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (shown) {
                    addView(glyphButton("chevron-up", "Đưa lên") { moveSlider(key, -1) })
                    addView(glyphButton("chevron-down", "Đưa xuống") { moveSlider(key, 1) })
                }
            }
            row.addView(GlassUi.switch(this@ExpansionSettingsActivity, shown) { enabled ->
                val keys = AppPrefs.expansionSliders(this@ExpansionSettingsActivity).toMutableList()
                if (enabled) {
                    if (key !in keys) keys.add(key)
                } else if (keys.size > 1) {
                    keys.remove(key)
                } else {
                    Toast.makeText(this@ExpansionSettingsActivity, "Cần giữ ít nhất một thanh", Toast.LENGTH_SHORT).show()
                }
                AppPrefs.setExpansionSliders(this@ExpansionSettingsActivity, keys)
                render()
            }.apply { setPadding(0, 0, dp(4), 0) })
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            addView(row)
        }
    }

    private fun shortcutCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("corner-up-right", "Lối tắt"))
        addView(hint("Thêm, đổi hoặc xoá lối tắt tại đây. Nhấn giữ lối tắt ứng dụng trong bảng để mở cửa sổ nổi; bảng tự thu gọn khi không có lối tắt."))
        val shortcuts = AppPrefs.expansionShortcuts(this@ExpansionSettingsActivity)
        val grid = LinearLayout(this@ExpansionSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        shortcuts.forEachIndexed { index, shortcut ->
            val row = LinearLayout(this@ExpansionSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(6), dp(6))
                background = GlassUi.glassSurface(this@ExpansionSettingsActivity, 14)
                contentDescription = shortcut?.label ?: "Ô lối tắt trống ${index + 1}"
                isClickable = true
                isFocusable = true
                setOnClickListener { launchPicker(index) }
                if (shortcut != null) {
                    addView(shortcutIcon(shortcut), LinearLayout.LayoutParams(dp(26), dp(26)))
                } else {
                    addView(LucideGlyphView(this@ExpansionSettingsActivity, "plus", GlassUi.TEXT_MUTED),
                        LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(3) })
                }
                addView(TextView(this@ExpansionSettingsActivity).apply {
                    text = shortcut?.label ?: "Thêm lối tắt"
                    textSize = 13f
                    setTextColor(if (shortcut != null) GlassUi.TEXT_PRIMARY else GlassUi.TEXT_MUTED)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (shortcut != null) {
                    addView(glyphButton("trash-2", "Xoá lối tắt") {
                        AppPrefs.setExpansionShortcut(this@ExpansionSettingsActivity, index, null)
                        render()
                    })
                }
            }
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(4) }
            grid.addView(row)
        }
        addView(grid)
    }

    private fun launchPicker(slot: Int) {
        startActivity(Intent(this, WidgetPickerActivity::class.java).apply {
            putExtra(WidgetPickerActivity.EXTRA_EXPANSION_SLOT, slot)
            putExtra(WidgetPickerActivity.EXTRA_ITEM_TYPE, PanelItemType.ACTION)
        })
    }

    private fun moveSlider(key: String, delta: Int) {
        val keys = AppPrefs.expansionSliders(this).toMutableList()
        val from = keys.indexOf(key)
        val to = from + delta
        if (from < 0 || to !in keys.indices) return
        val moved = keys.removeAt(from)
        keys.add(to, moved)
        AppPrefs.setExpansionSliders(this, keys)
        render()
    }

    private fun sliderLabel(key: String) = when (key) {
        "brightness" -> "Độ sáng"
        "ring" -> "Chuông"
        "notification" -> "Thông báo"
        "alarm" -> "Báo thức"
        else -> "Media"
    }

    private fun sliderIcon(key: String) = when (key) {
        "brightness" -> "sun"
        "ring" -> "phone"
        "notification" -> "bell"
        "alarm" -> "alarm-clock"
        else -> "music-2"
    }

    private fun shortcutIcon(shortcut: ExpansionShortcut): View {
        if (shortcut.type == PanelItemType.ACTION) {
            return ActionGlyphView(this, shortcut.value, GlassUi.ACCENT)
        }
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            try {
                val icon = when (shortcut.type) {
                    PanelItemType.APP -> ComponentName.unflattenFromString(shortcut.value)
                        ?.let { packageManager.getActivityIcon(it) }
                    PanelItemType.SHORTCUT -> Intent.parseUri(shortcut.value, 0)
                        .resolveActivity(packageManager)
                        ?.let { packageManager.getActivityIcon(it) }
                    else -> null
                }
                setImageDrawable(icon ?: packageManager.getDefaultActivityIcon())
            } catch (error: Exception) {
                setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }
    }

    private fun glyphButton(icon: String, description: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = description
            addView(LucideGlyphView(this@ExpansionSettingsActivity, icon, GlassUi.TEXT_MUTED),
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            setOnClickListener { onClick() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }

    private fun cardHeader(icon: String, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@ExpansionSettingsActivity, icon, sizeDp = 36, glyphDp = 20))
            addView(
                GlassUi.cardTitle(this@ExpansionSettingsActivity, title).apply {
                    setPadding(dp(12), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(GlassUi.TEXT_MUTED)
        gravity = Gravity.START
        includeFontPadding = false
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)
}
