package com.manh.panelsassistive

import android.app.Activity
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
            "Màu nền, màu nhấn và màu chi tiết dùng chung cho ứng dụng lẫn bảng nổi."
        ))
        root.addView(paletteCard())
        root.addView(accentCard())
        root.addView(sliderColorCard())
        root.addView(textColorCard())
        root.addView(iconColorCard())
        root.addView(resetCard())
        root.addView(iconPackCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun paletteCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("mountain", "Màu nền"))
        addView(hint("Đổi nền trang, nền bảng nổi và màu thanh trạng thái. Màu nhấn được chọn riêng bên dưới."))

        val current = AppThemes.current(this@ThemeSettingsActivity)
        AppThemes.ALL.forEach { theme ->
            addView(themeRow(theme, theme.key == current.key))
        }

        addView(hint("Bảng màu chỉ đổi nền; màu nhấn đã chọn sẽ được giữ nguyên."))
    }

    private fun accentCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("palette", "Màu nhấn"))
        addView(hint("Màu của nút, lựa chọn và điểm nhấn. Chạm ô vuông cuối dãy để chọn màu tùy ý."))
        addView(GlassUi.paletteRow(
            this@ThemeSettingsActivity,
            AppThemes.ACCENT_PALETTE,
            AppThemes.current(this@ThemeSettingsActivity).accent,
            "Màu nhấn"
        ) { color -> applyAccent(color) })
    }

    private fun sliderColorCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("sliders-horizontal", "Màu thanh trượt"))
        addView(hint("Thanh trượt trong ứng dụng, bảng mở rộng và thanh độ sáng nổi."))
        addView(GlassUi.paletteRow(
            this@ThemeSettingsActivity,
            SLIDER_PALETTE,
            GlassUi.SLIDER_COLOR,
            "Màu thanh trượt"
        ) { color -> applyDetail(color, AppPrefs::setThemeSliderColor) })
    }

    private fun textColorCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("type", "Màu chữ"))
        addView(hint("Chữ chính đổi theo màu này; chữ phụ tự giảm độ sáng để vẫn dễ đọc."))
        addView(GlassUi.paletteRow(
            this@ThemeSettingsActivity,
            TEXT_PALETTE,
            GlassUi.TEXT_PRIMARY,
            "Màu chữ"
        ) { color -> applyDetail(color, AppPrefs::setThemeTextColor) })
    }

    private fun iconColorCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("shapes", "Màu icon"))
        addView(hint("Icon Lucide và icon hành động trong ứng dụng lẫn bảng nổi."))
        addView(GlassUi.paletteRow(
            this@ThemeSettingsActivity,
            ICON_PALETTE,
            GlassUi.ICON_COLOR,
            "Màu icon"
        ) { color -> applyDetail(color, AppPrefs::setThemeIconColor) })
    }

    private fun resetCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("rotate-ccw", "Khôi phục"))
        addView(hint("Xoá màu nhấn tùy chỉnh cùng màu thanh trượt, chữ và icon đã chọn."))
        addView(GlassUi.actionButton(
            this@ThemeSettingsActivity,
            "Đưa màu về mặc định"
        ) {
            AppPrefs.resetThemeColors(this@ThemeSettingsActivity)
            GlassUi.applyTheme(this@ThemeSettingsActivity)
            refreshChrome()
            render()
            Toast.makeText(this@ThemeSettingsActivity, "Đã khôi phục màu mặc định", Toast.LENGTH_SHORT).show()
        })
    }

    private fun applyAccent(color: Int) {
        AppThemes.applyAccent(this, color)
        refreshChrome()
        render()
        Toast.makeText(this, "Đã áp dụng màu nhấn", Toast.LENGTH_SHORT).show()
    }

    private fun applyDetail(color: Int, setter: (android.content.Context, Int) -> Unit) {
        setter(this, color)
        GlassUi.applyTheme(this)
        refreshChrome()
        render()
        Toast.makeText(this, "Đã áp dụng màu", Toast.LENGTH_SHORT).show()
    }

    private fun refreshChrome() {
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        FloatBallService.refreshPopupAppearance(this)
    }

    private fun cardHeader(icon: String, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@ThemeSettingsActivity, icon, sizeDp = 36, glyphDp = 20))
            addView(
                GlassUi.cardTitle(this@ThemeSettingsActivity, title).apply {
                    setPadding(dp(12), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
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
                addView(label("Nền " + hex(theme.pageMid), 11, GlassUi.TEXT_MUTED).apply {
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
        val current = AppPrefs.iconPackPackage(this)
        val items = packs.map { pack ->
            GlassDialogs.Item(
                id = pack.packageName,
                label = pack.label,
                caption = pack.packageName,
                iconPackage = pack.packageName,
                selected = pack.packageName == current
            )
        }
        GlassDialogs.show(this, "Chọn icon pack", items, "Gói icon áp dụng cho app và lối tắt") { item ->
            AppPrefs.setIconPackPackage(this, item.id)
            AppPrefs.setIconPackEnabled(this, true)
            render()
        }
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

    private val SLIDER_PALETTE: IntArray
        get() = AppThemes.ACCENT_PALETTE + intArrayOf(0xFFFFFFFF.toInt())

    private val ICON_PALETTE: IntArray
        get() = AppThemes.ACCENT_PALETTE + intArrayOf(0xFFFFFFFF.toInt())

    private val TEXT_PALETTE = intArrayOf(
        0xFFF4F6FA.toInt(),
        0xFFDCE9F7.toInt(),
        0xFFFFF1D9.toInt(),
        0xFFE2FAF1.toInt(),
        0xFFF0E7FF.toInt(),
        0xFFFFE5EC.toInt(),
        0xFFC9D6E4.toInt(),
        0xFFFFFFFF.toInt()
    )

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
