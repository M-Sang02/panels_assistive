package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
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
            "Một bảng màu áp dụng cho bảng nổi và toàn ứng dụng."
        ))
        root.addView(paletteCard())
        root.addView(iconPackCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun paletteCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@ThemeSettingsActivity, "Bảng màu"))
        addView(hint("Chọn bảng màu để đổi màu nhấn (nút bấm, thanh trượt), nền các bảng nổi và status bar."))

        val current = AppThemes.current(this@ThemeSettingsActivity)
        AppThemes.ALL.forEach { theme ->
            addView(themeRow(theme, theme.key == current.key))
        }

        addView(accentPicker())

        addView(hint(
            "Áp dụng bảng màu sẽ ghi đè màu nền bảng bạn đã tự chỉnh trong " +
                "Bảng tiện ích và Hiệu ứng."
        ))
    }

    /** Only the palette tile stays here: tap it to pick any custom accent. */
    private fun accentPicker(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(GlassUi.divider(this@ThemeSettingsActivity),
            LinearLayout.LayoutParams(0, dp(1).coerceAtLeast(1), 1f).apply {
                marginEnd = dp(14)
            })
        val accent = AppThemes.current(this@ThemeSettingsActivity).accent
        addView(GlassUi.paletteButton(
            this@ThemeSettingsActivity,
            accent,
            "Màu nhấn tùy chỉnh"
        ) { color -> applyAccent(color) }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun applyAccent(color: Int) {
        AppThemes.applyAccent(this, color)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        render()
        Toast.makeText(this, "Đã áp dụng màu nhấn", Toast.LENGTH_SHORT).show()
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

    private fun iconPackCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@ThemeSettingsActivity, "Icon pack"))
        addView(hint("Thay icon app trong bảng tiện ích bằng gói icon đã cài (định dạng ADW/Nova)."))
        val enabled = AppPrefs.iconPackEnabled(this@ThemeSettingsActivity)
        val packPackage = AppPrefs.iconPackPackage(this@ThemeSettingsActivity)
        addView(LinearLayout(this@ThemeSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Áp dụng icon pack", 14, GlassUi.TEXT_PRIMARY))
            addView(GlassUi.switch(this@ThemeSettingsActivity, enabled) { on ->
                AppPrefs.setIconPackEnabled(this@ThemeSettingsActivity, on)
                if (on && AppPrefs.iconPackPackage(this@ThemeSettingsActivity).isEmpty()) {
                    choosePack()
                } else {
                    render()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.END
            })
        })
        val packLabel = installedPackLabel(packPackage)
        if (packPackage.isNotEmpty() && enabled) {
            val previews = LinearLayout(this@ThemeSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(2))
            }
            IconPacks.packIconEntries(this@ThemeSettingsActivity, packPackage)
                .take(6)
                .forEach { (_, name) ->
                    val icon = IconPacks.packIconBitmap(
                        this@ThemeSettingsActivity, packPackage, name, dp(34)
                    ) ?: return@forEach
                    previews.addView(ImageView(this@ThemeSettingsActivity).apply {
                        setImageBitmap(icon)
                    }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
                }
            if (previews.childCount > 0) addView(previews)
        }
        addView(LinearLayout(this@ThemeSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GlassUi.glassSurface(this@ThemeSettingsActivity, 14)
            addView(LucideGlyphView(this@ThemeSettingsActivity, "panels-top-left", GlassUi.ACCENT),
                LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(label(packLabel, 13, GlassUi.TEXT_PRIMARY).apply {
                setPadding(dp(10), 0, dp(10), 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LucideGlyphView(this@ThemeSettingsActivity, "chevron-right", GlassUi.TEXT_MUTED),
                LinearLayout.LayoutParams(dp(16), dp(16)))
            isClickable = true
            isFocusable = true
            setOnClickListener { choosePack() }
        })
        addView(LinearLayout(this@ThemeSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(LinearLayout(this@ThemeSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Mặt nạ & lớp phủ", 14, GlassUi.TEXT_PRIMARY))
                addView(label("Ghép iconback/iconmask/iconupon cho app không có icon trong gói", 11, GlassUi.TEXT_MUTED).apply {
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(GlassUi.switch(
                this@ThemeSettingsActivity,
                AppPrefs.iconMaskEnabled(this@ThemeSettingsActivity)
            ) { on ->
                AppPrefs.setIconMaskEnabled(this@ThemeSettingsActivity, on)
                render()
            })
        })
        addView(GlassUi.actionButton(
            this@ThemeSettingsActivity,
            "Icon riêng cho app / lối tắt"
        ) {
            startActivity(Intent(this@ThemeSettingsActivity, IconOverrideActivity::class.java))
        })
        addView(hint(
            "Icon riêng được lưu trong máy. Nhấn vào một app hoặc lối tắt để thay bằng ảnh " +
                "hoặc một icon trong gói."
        ))
    }

    private fun installedPackLabel(packageName: String): String {
        if (packageName.isEmpty()) return "Chưa chọn gói icon"
        return IconPacks.installedPacks(this).firstOrNull { it.packageName == packageName }?.label
            ?: packageName
    }

    private fun choosePack() {
        val packs = IconPacks.installedPacks(this)
        if (packs.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy gói icon nào được cài", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = packs.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chọn icon pack")
            .setItems(labels) { _, which ->
                AppPrefs.setIconPackPackage(this, packs[which].packageName)
                AppPrefs.setIconPackEnabled(this, true)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
