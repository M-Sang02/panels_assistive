package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
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
            "Âm lượng, độ sáng, đồng hồ và nhạc theo phong cách kính tối của ứng dụng."
        ))
        root.addView(widgetCard(
            "volume-2",
            "Âm lượng dọc (1×2)",
            "Cột dọc mảnh, kính trắng gần như trong suốt. Nửa trên tăng, nửa dưới giảm; " +
                "nhãn \"Âm lượng\" và % ở giữa, dấu +/− ở hai đầu.",
            GlassVolumeVerticalWidget::class.java
        ))
        root.addView(widgetCard(
            "audio-lines",
            "Âm lượng ngang (2×1)",
            "Thanh ngang mảnh dạng viên thuốc. Chạm nửa trái để giảm, nửa phải để tăng — " +
                "cập nhật ngay cả khi bạn bấm nút âm lượng vật lý.",
            GlassVolumeHorizontalWidget::class.java
        ))
        root.addView(widgetCard(
            "sun",
            "Độ sáng dọc (1×2)",
            "Cột dọc tăng/giảm độ sáng màn hình, kèm nhãn \"Độ sáng\" và %. Cần cấp quyền " +
                "Chỉnh sửa cài đặt hệ thống ở lần chạm đầu tiên.",
            GlassBrightnessVerticalWidget::class.java
        ))
        root.addView(widgetCard(
            "sun-medium",
            "Độ sáng ngang (2×1)",
            "Thanh ngang tăng/giảm độ sáng, nhãn và % ở giữa. Chạm nửa trái để giảm, " +
                "nửa phải để tăng.",
            GlassBrightnessHorizontalWidget::class.java
        ))
        root.addView(widgetCard(
            "clock",
            "Đồng hồ (2×2)",
            "Chữ trắng trên nền kính trắng rất trong giống thẻ nhạc; tự cập nhật theo múi " +
                "giờ và định dạng 12/24 giờ của máy.",
            GlassClockWidget::class.java
        ))
        root.addView(widgetCard(
            "music",
            "Nhạc (2×2 → 4×2 → 4×4)",
            "Ảnh bìa tròn cân giữa, tên bài và nghệ sĩ căn giữa, hàng nút Prev / Play / " +
                "Next chia đều bên dưới. Ghim ở dạng 2×2, kéo rộng thành 4×2 hoặc phóng " +
                "to 4×4 — nút tự co giãn, không bao giờ bị ẩn khi thu nhỏ.",
            GlassMusicWidget::class.java
        ))
        root.addView(widgetCard(
            "disc-album",
            "Nhạc ảnh bìa (2×2)",
            "Một widget nhạc thứ hai, độc lập: ảnh bìa sắc nét (cắt giữa, không làm mờ) " +
                "làm nền, lớp kính trắng rất nhẹ phía trên và đáy ảnh tan dần vào hình nền; " +
                "tên bài và nghệ sĩ ở trên, cụm Previous / Play / Next tròn ở dưới. " +
                "Bo góc 24dp, viền trắng mảnh; dùng chung app nhạc đã gán và " +
                "\"Play music now\".",
            GlassMusicCoverWidget::class.java
        ))
        root.addView(musicCard())
        root.addView(quickOpenCard())
        root.addView(themeHint())
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
            "Chạm vào thân widget Đồng hồ để mở app được gán. Widget âm lượng và độ sáng " +
                "chỉ chỉnh mức, không mở app. Widget nhạc ưu tiên mở app đang phát; khi " +
                "im lặng sẽ mở app được gán riêng cho widget đó, rồi mới dùng app dưới đây."
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

        val rows = mutableListOf<Pair<String, Int>>()
        val manager = AppWidgetManager.getInstance(this@WidgetSettingsActivity)
        manager.getAppWidgetIds(
            ComponentName(this@WidgetSettingsActivity, GlassMusicWidget::class.java)
        ).forEachIndexed { index, id -> rows.add("Widget nhạc ${index + 1}" to id) }
        manager.getAppWidgetIds(
            ComponentName(this@WidgetSettingsActivity, GlassMusicCoverWidget::class.java)
        ).forEachIndexed { index, id -> rows.add("Widget nhạc ảnh bìa ${index + 1}" to id) }
        if (rows.isNotEmpty()) {
            addView(GlassUi.divider(this@WidgetSettingsActivity))
            addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Gán riêng từng widget"))
            addView(hint(
                "Bạn đang ghim ${rows.size} widget nhạc (cả hai kiểu). Chạm vào một dòng " +
                    "để chọn app riêng cho widget đó, hoặc dùng app mặc định ở trên."
            ))
            rows.forEach { (label, id) -> addView(instanceRow(label, id)) }
        }
    }

    private fun instanceRow(rowLabel: String, widgetId: Int): LinearLayout {
        val active = AppPrefs.musicWidgetPackage(this, widgetId)
        val detail = when {
            AppPrefs.musicWidgetNoApp(this, widgetId) -> "Không mở app"
            active.isNotEmpty() -> WidgetMedia.label(this, active)
            else -> "Tự động theo app đang phát"
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
                    onClear = { AppPrefs.clearMusicWidgetPackage(this@WidgetSettingsActivity, widgetId) },
                    onNoApp = {
                        AppPrefs.setMusicWidgetPackage(
                            this@WidgetSettingsActivity,
                            widgetId,
                            AppPrefs.WIDGET_NO_APP
                        )
                    }
                ) { packageName ->
                    AppPrefs.setMusicWidgetPackage(this@WidgetSettingsActivity, widgetId, packageName)
                }
            }
            addView(label(rowLabel, 13, GlassUi.TEXT_PRIMARY),
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
        onNoApp: (() -> Unit)? = null,
        onPick: (String) -> Unit
    ) {
        val apps = WidgetMedia.launcherApps(this)
        val names = buildList {
            add(clearLabel)
            if (onNoApp != null) add("Không mở app (chỉ hiển thị bài hát)")
            addAll(apps.map { it.first })
        }
        val appOffset = if (onNoApp != null) 2 else 1
        AlertDialog.Builder(this)
            .setTitle("Chọn ứng dụng nhạc")
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which == 0 -> onClear()
                    onNoApp != null && which == 1 -> onNoApp()
                    else -> onPick(apps[which - appOffset].second)
                }
                GlassWidgets.refreshAll(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun themeHint(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Màu chữ widget"))
        addView(hint(
            "Mọi widget dùng chung kính trắng gần như trong suốt: sáng nhẹ ở góc trên rồi " +
                "tan vào hình nền, viền trắng mảnh 0,9dp, chữ và icon trắng. Màu chủ đạo chỉ " +
                "tô duy nhất nút Play của hai widget nhạc — không pha màu vào nền âm lượng, " +
                "độ sáng hay đồng hồ."
        ))
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
            "Widget dùng kính trắng rất trong: sáng nhẹ ở góc trên và tan nhanh thành trong " +
                "suốt, không pha xanh hay màu nhấn. Viền trắng mảnh 0,9dp, bo góc lớn; " +
                "chữ/icon trắng có bóng mềm. Nhấn giữ widget để đổi kích thước; icon tăng/giảm " +
                "luôn bám mép ngoài khi kéo dài."
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
}
