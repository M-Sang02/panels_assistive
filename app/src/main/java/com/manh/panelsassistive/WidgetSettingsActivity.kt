package com.manh.panelsassistive

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
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
            "Âm lượng, độ sáng, đồng hồ và nhạc theo phong cách kính tối của ứng dụng. " +
                "Nút ứng dụng bên phải mỗi tên widget chọn app mở khi chạm widget."
        ))
        root.addView(widgetCard(
            "volume-2",
            "Âm lượng dọc (1×2)",
            "Cột dọc mảnh, kính trắng gần như trong suốt. Nửa trên tăng, nửa dưới giảm; " +
                "icon loa nhỏ và % ở giữa, dấu +/− ở hai đầu.",
            GlassVolumeVerticalWidget::class.java
        ))
        root.addView(widgetCard(
            "volume-2",
            "Âm lượng ngang (2×1)",
            "Thanh ngang mảnh dạng viên thuốc, icon loa nhỏ và % ở giữa. Chạm nửa trái để " +
                "giảm, nửa phải để tăng — cập nhật ngay khi bấm phím âm lượng.",
            GlassVolumeHorizontalWidget::class.java
        ))
        root.addView(widgetCard(
            "sun",
            "Độ sáng dọc (1×2)",
            "Cột dọc tăng/giảm độ sáng màn hình, kèm icon mặt trời nhỏ và %. Cần cấp quyền " +
                "Chỉnh sửa cài đặt hệ thống ở lần chạm đầu tiên.",
            GlassBrightnessVerticalWidget::class.java
        ))
        root.addView(widgetCard(
            "sun-medium",
            "Độ sáng ngang (2×1)",
            "Thanh ngang tăng/giảm độ sáng, icon mặt trời nhỏ và % ở giữa. Chạm nửa trái " +
                "để giảm, nửa phải để tăng.",
            GlassBrightnessHorizontalWidget::class.java
        ))
        root.addView(widgetCard(
            "clock",
            "Đồng hồ (2×2)",
            "Chữ trắng trên nền kính trắng rất trong giống thẻ nhạc; tự cập nhật theo múi " +
                "giờ và định dạng 12/24 giờ của máy, kèm ngày âm lịch Việt Nam. Chạm vào " +
                "widget sẽ mở app được gán bên phải tên widget; không gán thì không mở gì.",
            GlassClockWidget::class.java,
            assignedPackage = AppPrefs.widgetShortcut(this).substringBefore('/'),
            badgeGlyph = "clock",
            appHint = "Chọn app mở khi chạm đồng hồ"
        ) { chooseApp() })
        root.addView(widgetCard(
            "music",
            "Nhạc (2×2 → 4×2 → 4×4)",
            "Ảnh bìa tròn nhỏ cân giữa, tên bài và nghệ sĩ một dòng căn giữa, cụm " +
                "Prev / Play / Next gọn ở dưới. Ghim ở dạng 2×2, kéo rộng thành 4×2 " +
                "hoặc phóng to 4×4 — bố cục đổi theo kích thước, nút luôn hiện rõ.",
            GlassMusicWidget::class.java,
            assignedPackage = AppPrefs.musicWidgetPackage(this),
            badgeGlyph = if (AppPrefs.musicWidgetNoApp(this)) "ban" else "music",
            appHint = "Chọn ứng dụng nhạc dùng chung"
        ) { chooseMusicApp() })
        root.addView(widgetCard(
            "disc-album",
            "Nhạc ảnh bìa (2×2)",
            "Một widget nhạc thứ hai, độc lập: ảnh bìa sắc nét (cắt giữa, không làm mờ) " +
                "làm nền, lớp kính trắng rất nhẹ phía trên và đáy ảnh tan dần vào hình nền; " +
                "tên bài và nghệ sĩ ở trên, cụm Previous / Play / Next tròn ở dưới. " +
                "Bo góc 32dp đồng bộ widget nhạc, viền trắng mảnh. Chạm vào dòng tên bài " +
                "hoặc nghệ sĩ để ẩn cả hai dòng (giữ nguyên khoảng trống), chạm lại đúng " +
                "vùng đó để hiện lại; dùng chung app nhạc đã gán và \"Play music now\".",
            GlassMusicCoverWidget::class.java,
            assignedPackage = AppPrefs.musicWidgetPackage(this),
            badgeGlyph = if (AppPrefs.musicWidgetNoApp(this)) "ban" else "music",
            appHint = "Chọn ứng dụng nhạc dùng chung"
        ) { chooseMusicApp() })
        root.addView(widgetCard(
            "image",
            "Ảnh / mã QR (2×2 → 2×3 → 3×2 …)",
            "Kính trong giống widget nhạc khi chưa có nội dung. Chạm để mở cửa sổ nổi: " +
                "chọn ảnh mã QR của bạn, cắt vuông 1:1 (khung bo góc, có xoay và lật ảnh) " +
                "rồi thêm thông tin chuyển khoản (không bắt buộc), hoặc chọn một/nhiều ảnh. " +
                "Khi đã có nội dung, chạm mở màn hình xem: phóng to tuỳ ý, kéo ngang để ảnh " +
                "mờ dần rồi đổi khi đang 100%, thông tin QR là trang cuối; ảnh xem cuối cùng " +
                "sẽ hiện ra widget; chạm giữ để chia sẻ, sửa hoặc sao chép thông tin. Ảnh nền " +
                "trong suốt được nền kính bám theo hình ảnh.",
            GlassImageWidget::class.java
        ))
        root.addView(musicCard())
        root.addView(widgetColorCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun widgetCard(
        icon: String,
        title: String,
        description: String,
        provider: Class<*>,
        assignedPackage: String? = null,
        badgeGlyph: String = "smartphone",
        appHint: String = "Chọn ứng dụng",
        onPickApp: (() -> Unit)? = null
    ): LinearLayout = GlassUi.glassCard(this).apply {
        addView(LinearLayout(this@WidgetSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@WidgetSettingsActivity, icon, sizeDp = 38, glyphDp = 20))
            addView(GlassUi.cardTitle(this@WidgetSettingsActivity, title).apply {
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (onPickApp != null) {
                addView(appBadge(assignedPackage, badgeGlyph, appHint, onPickApp))
            }
        })
        addView(hint(description))
        addView(GlassUi.actionButton(
            this@WidgetSettingsActivity,
            "Thêm $title",
            prominent = true
        ) { pin(provider) })
    }

    /**
     * Small icon tile on the right of a widget name: the assigned app's icon,
     * or a glyph when nothing is assigned. One tap opens the shared picker.
     */
    private fun appBadge(
        packageName: String?,
        glyph: String,
        hint: String,
        onClick: () -> Unit
    ): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(26, 224, 236, 255))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.argb(48, 255, 255, 255))
        }
        isClickable = true
        isFocusable = true
        contentDescription = hint
        val icon = GlassDialogs.appIcon(this@WidgetSettingsActivity, packageName)
        if (icon != null) {
            addView(ImageView(this@WidgetSettingsActivity).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        } else {
            addView(
                LucideGlyphView(this@WidgetSettingsActivity, glyph, GlassUi.TEXT_SECONDARY),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            )
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) }
    }

    /** MediaSession access, the one shared music app, and "play now". */
    private fun musicCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Ứng dụng nhạc"))
        addView(hint(
            "Một lựa chọn áp dụng cho mọi widget nhạc: ghim 2, 5 hay 10 widget thì tất cả " +
                "cùng mở một app. Widget đọc MediaSession để hiện tên bài, ảnh bìa và " +
                "điều khiển phát/tạm dừng; cấp truy cập thông báo để điều khiển đúng app " +
                "đang phát, nếu chưa cấp widget vẫn hiện trạng thái chờ và vẫn điều khiển " +
                "bằng phím media."
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
        val noApp = AppPrefs.musicWidgetNoApp(this@WidgetSettingsActivity)
        addView(LinearLayout(this@WidgetSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GlassUi.glassSurface(this@WidgetSettingsActivity, 14)
            addView(
                LucideGlyphView(this@WidgetSettingsActivity, "music", GlassUi.ACCENT),
                LinearLayout.LayoutParams(dp(18), dp(18))
            )
            addView(label("Ứng dụng nhạc dùng chung", 13, GlassUi.TEXT_PRIMARY).apply {
                setPadding(dp(10), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(
                when {
                    noApp -> "Không mở ứng dụng"
                    shared.isEmpty() -> "Tự động"
                    else -> WidgetMedia.label(this@WidgetSettingsActivity, shared)
                },
                12,
                GlassUi.TEXT_MUTED
            ))
        })
        addView(GlassUi.actionButton(this@WidgetSettingsActivity, "Chọn ứng dụng nhạc") {
            chooseMusicApp()
        })
        addView(GlassUi.actionButton(
            this@WidgetSettingsActivity,
            "Phát nhạc ngay",
            prominent = true
        ) {
            when {
                noApp -> Toast.makeText(
                    this@WidgetSettingsActivity,
                    "Đang chọn \"Không mở ứng dụng\". Chọn một app nhạc để phát.",
                    Toast.LENGTH_SHORT
                ).show()
                shared.isEmpty() -> chooseMusicApp { picked ->
                    if (picked.isNotEmpty()) WidgetMedia.playNow(this@WidgetSettingsActivity, picked)
                }
                else -> {
                    WidgetMedia.playNow(this@WidgetSettingsActivity, shared)
                    Toast.makeText(
                        this@WidgetSettingsActivity,
                        "Đang mở ${WidgetMedia.label(this@WidgetSettingsActivity, shared)} và gửi lệnh phát.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    /** Shared picker: auto, "no app", then every launcher app with its icon. */
    private fun chooseMusicApp(onPicked: (String) -> Unit = {}) {
        val assigned = AppPrefs.musicWidgetPackage(this)
        val noApp = AppPrefs.musicWidgetNoApp(this)
        val items = buildList {
            add(GlassDialogs.Item(
                id = AUTO,
                label = "Tự động (app đang phát)",
                caption = "Ưu tiên ứng dụng đang phát trên thông báo",
                glyph = "music",
                selected = !noApp && assigned.isEmpty()
            ))
            add(GlassDialogs.Item(
                id = AppPrefs.WIDGET_NO_APP,
                label = "Không mở ứng dụng",
                caption = "Widget chỉ hiển thị thông tin bài hát",
                glyph = "ban",
                selected = noApp
            ))
            WidgetMedia.launcherApps(this@WidgetSettingsActivity).forEach { (name, packageName) ->
                add(GlassDialogs.Item(
                    id = packageName,
                    label = name,
                    iconPackage = packageName,
                    selected = packageName == assigned
                ))
            }
        }
        GlassDialogs.show(
            this,
            "Chọn ứng dụng nhạc",
            items,
            "Áp dụng cho mọi widget nhạc"
        ) { item ->
            val value = if (item.id == AUTO) "" else item.id
            AppPrefs.setMusicWidgetPackage(this, value)
            GlassWidgets.refreshAll(this)
            render()
            onPicked(if (value == AppPrefs.WIDGET_NO_APP) "" else value)
        }
    }

    /**
     * Widget-only accent. Independent from "Giao diện": the cards keep their
     * white frost and only the music Play/Pause disc takes this colour, so the
     * widget never inherits a pale theme tint.
     */
    private fun widgetColorCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@WidgetSettingsActivity, "Màu của widget"))
        addView(hint(
            "Chọn màu nhấn riêng cho widget, tách khỏi bảng màu Giao diện. Màu này tô " +
                "nút Phát / Tạm dừng của cả hai widget nhạc; nền widget vẫn là kính trắng."
        ))
        val current = AppPrefs.widgetAccent(this@WidgetSettingsActivity)
        addView(GlassUi.paletteRow(
            this@WidgetSettingsActivity,
            AppPrefs.WIDGET_ACCENT_PALETTE,
            current,
            "Màu của widget"
        ) { color -> applyWidgetColor(color) })
    }

    private fun applyWidgetColor(color: Int) {
        AppPrefs.setWidgetAccent(this, color)
        GlassWidgets.refreshAll(this)
        render()
        Toast.makeText(this, "Đã áp dụng màu cho widget", Toast.LENGTH_SHORT).show()
    }

    private fun chooseApp() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .mapNotNull { info ->
                try {
                    Triple(
                        info.loadLabel(packageManager).toString(),
                        ComponentName(info.packageName, info.name),
                        info.packageName
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.first.lowercase() }
        if (entries.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy app nào", Toast.LENGTH_SHORT).show()
            return
        }
        val current = AppPrefs.widgetShortcut(this)
        val items = buildList {
            add(GlassDialogs.Item(
                id = "",
                label = "Không gán",
                caption = "Chạm đồng hồ không mở ứng dụng nào",
                glyph = "ban",
                selected = current.isEmpty()
            ))
            entries.forEach { entry ->
                val flat = entry.second.flattenToString()
                add(GlassDialogs.Item(
                    id = flat,
                    label = entry.first,
                    iconPackage = entry.third,
                    selected = flat == current
                ))
            }
        }
        GlassDialogs.show(
            this,
            "Chọn app mở nhanh",
            items,
            "Chạm vào widget Đồng hồ để mở app này"
        ) { item ->
            AppPrefs.setWidgetShortcut(this, item.id.takeIf { it.isNotEmpty() })
            GlassWidgets.refreshAll(this)
            render()
        }
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
        val music = provider == GlassMusicWidget::class.java ||
            provider == GlassMusicCoverWidget::class.java
        if (music && !WidgetMedia.notificationAccessEnabled(this)) {
            promptMusicAccess { requestPin(manager, provider) }
            return
        }
        requestPin(manager, provider)
    }

    /**
     * The first music widget asks for notification access: the song title,
     * artwork and "play now" come from the playing app's notification.
     */
    private fun promptMusicAccess(onContinue: () -> Unit) {
        val sheet = GlassDialogs.sheet(
            this,
            "Bật quyền truy cập thông báo",
            "Widget nhạc đọc thông báo của ứng dụng đang phát để hiện tên bài, ảnh bìa và " +
                "chạy \"Phát nhạc ngay\". Chưa cấp quyền, widget vẫn hiển thị trạng thái chờ."
        )
        sheet.content.addView(GlassUi.actionButton(
            this,
            "Cấp quyền truy cập thông báo",
            prominent = true
        ) {
            sheet.dismiss()
            WidgetMedia.openNotificationAccess(this)
        })
        sheet.content.addView(GlassUi.actionButton(this, "Thêm widget luôn") {
            sheet.dismiss()
            onContinue()
        })
        sheet.show()
    }

    private fun requestPin(manager: AppWidgetManager, provider: Class<*>) {
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

    companion object {
        private const val AUTO = "@auto"
    }
}
