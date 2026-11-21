package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class WidgetSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
    }

    // Re-render on return so the notification-access status reflects the toggle.
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
            "Widget màn hình chính",
            "Pin, âm lượng, đồng hồ và nhạc theo phong cách kính của ứng dụng."
        ))
        root.addView(widgetCard(
            "volume-2",
            "Âm lượng dọc (1×2)",
            "Một cột dọc gọn cho mép màn hình. Nút + / − chỉnh âm lượng nhạc.",
            GlassVolumeVerticalWidget::class.java
        ))
        root.addView(widgetCard(
            "audio-lines",
            "Âm lượng ngang (2×1)",
            "Dạng thanh ngang một hàng, hợp đặt dưới đồng hồ hoặc cạnh widget khác.",
            GlassVolumeHorizontalWidget::class.java
        ))
        root.addView(widgetCard(
            "battery-charging",
            "Pin (2×2)",
            "Kính trong suốt, tia sét chìm, nhãn Pin yếu/Đang sạc và phần trăm pin lớn.",
            GlassBatteryWidget::class.java
        ))
        root.addView(widgetCard(
            "clock",
            "Đồng hồ (2×2)",
            "Giờ và thứ trong ngày, tự cập nhật theo múi giờ và định dạng 12/24 giờ của máy.",
            GlassClockWidget::class.java
        ))
        root.addView(widgetCard(
            "music",
            "Nhạc (2×2 → 4×2 → 4×4)",
            "Thẻ kính mờ với ảnh bìa, tên bài, nghệ sĩ và hàng nút chia đều. " +
                "Ghim ở dạng 2×2, kéo rộng thành 4×2 hoặc phóng to 4×4 — nút tự co giãn, " +
                "không bao giờ bị ẩn khi thu nhỏ.",
            GlassMusicWidget::class.java
        ))
        root.addView(musicCard())
        root.addView(quickOpenCard())
        root.addView(colorCard())
        root.addView(styleCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun widgetCard(
        icon: String,
        title: String,
        description: String,
        provider: Class<*>
    ): LinearLayout = GlassUi.glassCard(this).apply {
        addView(LinearLayout(this@WidgetSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@WidgetSettingsActivity, icon, sizeDp = 38, glyphDp = 20))
            addView(GlassUi.cardTitle(this@WidgetSettingsActivity, title).apply {
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(hint(description))
        addView(GlassUi.actionButton(
            this@WidgetSettingsActivity,
            "Thêm $title",
            prominent = true
        ) { pin(provider) })
    }

    private fun quickOpenCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Mở nhanh từ widget"))
        addView(hint(
            "Chạm vào thân widget Pin, Đồng hồ hoặc Âm lượng để mở app được gán. " +
                "Widget nhạc ưu tiên mở app đang phát; khi im lặng sẽ mở app được gán " +
                "riêng cho widget đó, rồi mới dùng app dưới đây."
        ))
        addView(LinearLayout(this@WidgetSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GlassUi.glassSurface(this@WidgetSettingsActivity, 14)
            addView(
                LucideGlyphView(this@WidgetSettingsActivity, "app-window", GlassUi.ACCENT),
                LinearLayout.LayoutParams(dp(18), dp(18))
            )
            addView(label("App mở nhanh", 13, GlassUi.TEXT_PRIMARY).apply {
                setPadding(dp(10), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(assignedLabel(), 12, GlassUi.TEXT_MUTED))
        })
        addView(GlassUi.actionButton(this@WidgetSettingsActivity, "Chọn app") { chooseApp() })
        if (AppPrefs.widgetShortcut(this@WidgetSettingsActivity).isNotEmpty()) {
            addView(GlassUi.actionButton(this@WidgetSettingsActivity, "Bỏ gán") {
                AppPrefs.setWidgetShortcut(this@WidgetSettingsActivity, null)
                GlassWidgets.refreshAll(this@WidgetSettingsActivity)
                render()
            })
        }
    }

    /** MediaSession access, default music app, per-instance assignment, play now. */
    private fun musicCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Ứng dụng nhạc"))
        addView(hint(
            "Widget đọc MediaSession để hiện tên bài, ảnh bìa và điều khiển phát/tạm dừng. " +
                "Cấp truy cập thông báo để điều khiển đúng app đang phát; nếu chưa cấp, " +
                "widget vẫn hiện trạng thái chờ và vẫn điều khiển bằng phím media."
        ))
        val granted = WidgetMedia.notificationAccessEnabled(this@WidgetSettingsActivity)
        addView(LinearLayout(this@WidgetSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GlassUi.glassSurface(this@WidgetSettingsActivity, 14)
            addView(
                LucideGlyphView(
                    this@WidgetSettingsActivity,
                    "bell",
                    if (granted) GlassUi.ACCENT else GlassUi.TEXT_MUTED
                ),
                LinearLayout.LayoutParams(dp(18), dp(18))
            )
            addView(label("Truy cập thông báo", 13, GlassUi.TEXT_PRIMARY).apply {
                setPadding(dp(10), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(if (granted) "Đã cấp" else "Chưa cấp", 12, GlassUi.TEXT_MUTED))
        })
        addView(GlassUi.actionButton(
            this@WidgetSettingsActivity,
            if (granted) "Mở quyền truy cập thông báo" else "Cấp quyền truy cập thông báo"
        ) { WidgetMedia.openNotificationAccess(this@WidgetSettingsActivity) })

        addView(GlassUi.divider(this@WidgetSettingsActivity))
        val shared = AppPrefs.musicWidgetPackage(this@WidgetSettingsActivity)
        addView(LinearLayout(this@WidgetSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GlassUi.glassSurface(this@WidgetSettingsActivity, 14)
            addView(
                LucideGlyphView(this@WidgetSettingsActivity, "music", GlassUi.ACCENT),
                LinearLayout.LayoutParams(dp(18), dp(18))
            )
            addView(label("App nhạc mặc định", 13, GlassUi.TEXT_PRIMARY).apply {
                setPadding(dp(10), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(
                if (shared.isEmpty()) "Tự động" else WidgetMedia.label(this@WidgetSettingsActivity, shared),
                12,
                GlassUi.TEXT_MUTED
            ))
        })
        addView(GlassUi.actionButton(this@WidgetSettingsActivity, "Chọn app nhạc mặc định") {
            chooseMusicApp(
                clearLabel = "Tự động (app đang phát)",
                onClear = { AppPrefs.setMusicWidgetPackage(this@WidgetSettingsActivity, null) }
            ) { packageName ->
                AppPrefs.setMusicWidgetPackage(this@WidgetSettingsActivity, packageName)
            }
        })
        addView(GlassUi.actionButton(
            this@WidgetSettingsActivity,
            "Phát nhạc ngay",
            prominent = true
        ) {
            val packageName = AppPrefs.musicWidgetPackage(this@WidgetSettingsActivity)
            if (packageName.isEmpty()) {
                chooseMusicApp(
                    clearLabel = "Tự động (app đang phát)",
                    onClear = { AppPrefs.setMusicWidgetPackage(this@WidgetSettingsActivity, null) }
                ) { picked ->
                    AppPrefs.setMusicWidgetPackage(this@WidgetSettingsActivity, picked)
                    WidgetMedia.playNow(this@WidgetSettingsActivity, picked)
                }
            } else {
                WidgetMedia.playNow(this@WidgetSettingsActivity, packageName)
                Toast.makeText(
                    this@WidgetSettingsActivity,
                    "Đang mở ${WidgetMedia.label(this@WidgetSettingsActivity, packageName)} và gửi lệnh phát.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        val ids = AppWidgetManager.getInstance(this@WidgetSettingsActivity)
            .getAppWidgetIds(
                ComponentName(this@WidgetSettingsActivity, GlassMusicWidget::class.java)
            )
        if (ids.isNotEmpty()) {
            addView(GlassUi.divider(this@WidgetSettingsActivity))
            addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Gán riêng từng widget"))
            addView(hint(
                "Bạn đang ghim ${ids.size} widget nhạc. Chạm vào một dòng để chọn app riêng " +
                    "cho widget đó, hoặc dùng app mặc định ở trên."
            ))
            ids.forEachIndexed { index, id -> addView(instanceRow(index + 1, id)) }
        }
    }

    private fun instanceRow(index: Int, widgetId: Int): LinearLayout {
        val active = AppPrefs.musicWidgetPackage(this, widgetId)
        val detail = if (active.isNotEmpty()) {
            WidgetMedia.label(this, active)
        } else {
            "Tự động theo app đang phát"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GlassUi.glassSurface(this@WidgetSettingsActivity, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                chooseMusicApp(
                    clearLabel = "Dùng app mặc định",
                    onClear = { AppPrefs.clearMusicWidgetPackage(this@WidgetSettingsActivity, widgetId) }
                ) { packageName ->
                    AppPrefs.setMusicWidgetPackage(this@WidgetSettingsActivity, widgetId, packageName)
                }
            }
            addView(label("Widget nhạc $index", 13, GlassUi.TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(detail, 12, GlassUi.TEXT_MUTED))
        }.apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(6)
            layoutParams = params
        }
    }

    private fun chooseMusicApp(
        clearLabel: String,
        onClear: () -> Unit,
        onPick: (String) -> Unit
    ) {
        val apps = WidgetMedia.launcherApps(this)
        val names = listOf(clearLabel) + apps.map { it.first }
        AlertDialog.Builder(this)
            .setTitle("Chọn ứng dụng nhạc")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) onClear() else onPick(apps[which - 1].second)
                GlassWidgets.refreshAll(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun colorCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Màu chữ widget"))
        addView(hint("Đổi màu phần trăm âm lượng, nhãn pin và đồng hồ trên widget màn hình chính."))
        val current = AppPrefs.widgetTextColor(this@WidgetSettingsActivity)
        val row = LinearLayout(this@WidgetSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        SWATCHES.forEach { color ->
            val dot = android.view.View(this@WidgetSettingsActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == current) {
                        setStroke(dp(3), GlassUi.ACCENT)
                    } else {
                        setStroke(dp(1), Color.argb(96, 255, 255, 255))
                    }
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AppPrefs.setWidgetTextColor(this@WidgetSettingsActivity, color)
                    GlassWidgets.refreshAll(this@WidgetSettingsActivity)
                    render()
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(12)
            })
        }
        addView(row)
        addView(GlassUi.actionButton(this@WidgetSettingsActivity, "Màu tùy chỉnh") {
            PanelColorPicker.show(
                this@WidgetSettingsActivity,
                current,
                "Màu chữ widget"
            ) { color ->
                AppPrefs.setWidgetTextColor(this@WidgetSettingsActivity, color)
                GlassWidgets.refreshAll(this@WidgetSettingsActivity)
            }
        })
        addView(hint("Mẹo: chọn màu tối khi thanh âm lượng đầy trắng để chữ vẫn rõ."))
    }

    private fun assignedLabel(): String {
        val flat = AppPrefs.widgetShortcut(this)
        if (flat.isEmpty()) return "Chưa gán"
        val component = ComponentName.unflattenFromString(flat) ?: return flat
        return try {
            packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString()
        } catch (_: Exception) {
            component.packageName
        }
    }

    private fun chooseApp() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .mapNotNull { info ->
                try {
                    info.loadLabel(packageManager).toString() to
                        ComponentName(info.packageName, info.name)
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.first.lowercase() }
        if (entries.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy app nào", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Chọn app mở nhanh")
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                AppPrefs.setWidgetShortcut(this, entries[which].second.flattenToString())
                GlassWidgets.refreshAll(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun styleCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Giao diện của widget"))
        addView(hint(
            "Widget dùng nền kính trong suốt, bo góc mềm; bảng màu chỉ đổi màu nút bấm. " +
                "Nhấn giữ widget trên màn hình chính để đổi kích thước; thêm tiện ích vào bảng " +
                "trong Cài đặt → Bảng tiện ích."
        ))
        addView(GlassUi.actionButton(
            this@WidgetSettingsActivity,
            "Mở Giao diện"
        ) {
            startActivity(Intent(this@WidgetSettingsActivity, ThemeSettingsActivity::class.java))
        })
    }

    private fun pin(provider: Class<*>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(
                this,
                "Máy chưa hỗ trợ thêm widget trực tiếp. Nhấn giữ màn hình chính → Widget → Panels Assistive.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(
                this,
                "Launcher này không hỗ trợ thêm trực tiếp. Hãy nhấn giữ màn hình chính → Widget → Panels Assistive.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val requested = try {
            manager.requestPinAppWidget(ComponentName(this, provider), null, null)
        } catch (error: IllegalStateException) {
            android.util.Log.w("WidgetSettings", "Pin request failed", error)
            false
        }
        Toast.makeText(
            this,
            if (requested) "Xác nhận thêm widget trên màn hình chính." else "Không thể thêm widget lúc này.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun hint(text: String): TextView = label(text, 12, GlassUi.TEXT_MUTED).apply {
        setPadding(0, dp(6), 0, dp(4))
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

    private companion object {
        val SWATCHES = intArrayOf(
            0xFF182434.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF7DBCFF.toInt(),
            0xFFFFC2D9.toInt(),
            0xFF9BE7C4.toInt()
        )
    }
}
