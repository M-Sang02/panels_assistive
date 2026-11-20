package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class AdvancedSettingsActivity : Activity() {
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        render()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
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
            getString(R.string.advanced_settings_title),
            "Quyền hệ thống, ô cài đặt nhanh, sao lưu và trạng thái dự án."
        ))
        root.addView(permissionsCard())
        root.addView(freeformCard())
        root.addView(tilesCard())
        root.addView(backupCard())
        root.addView(statusCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun permissionsCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("shield", getString(R.string.permissions_section)))
        addView(label(getString(R.string.overlay_permission_title), 15, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(10), 0, 0)
        })
        addView(label(
            getString(
                if (Settings.canDrawOverlays(this@AdvancedSettingsActivity)) {
                    R.string.overlay_permission_granted
                } else {
                    R.string.overlay_permission_required
                }
            ),
            13,
            GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, dp(4), 0, dp(4)) })
        if (!Settings.canDrawOverlays(this@AdvancedSettingsActivity)) {
            addView(GlassUi.actionButton(
                this@AdvancedSettingsActivity,
                getString(R.string.grant_overlay_permission)
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            })
        }
        addView(label(getString(R.string.accessibility_optional_title), 15, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(14), 0, 0)
        })
        addView(label(
            getString(
                if (AccessibilityActionService.isEnabled(this@AdvancedSettingsActivity)) {
                    R.string.accessibility_enabled
                } else {
                    R.string.accessibility_disabled
                }
            ),
            13,
            GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, dp(4), 0, dp(4)) })
        addView(GlassUi.actionButton(
            this@AdvancedSettingsActivity,
            getString(R.string.open_accessibility_settings)
        ) { ActionDispatcher.openAccessibilitySettings(this@AdvancedSettingsActivity) })
        addView(GlassUi.actionButton(
            this@AdvancedSettingsActivity,
            getString(R.string.open_battery_optimization_settings)
        ) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        })
        addView(label(getString(R.string.accessibility_optional_description), 12, GlassUi.TEXT_MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun freeformCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val activity = this@AdvancedSettingsActivity
        addView(cardHeader("app-window", "Cửa sổ tự do"))
        addView(label(
            "Mở app từ bảng tiện ích trong cửa sổ nổi thay vì toàn màn hình. " +
                "Chỉ dùng Shizuku khi bạn bật tính năng này.",
            13,
            GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, dp(8), 0, dp(4)) })
        val enabled = AppPrefs.freeformApps(activity)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Bật cửa sổ tự do", 14, GlassUi.TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(GlassUi.switch(activity, enabled) { on ->
                AppPrefs.setFreeformApps(activity, on)
                if (on) {
                    FloatingApps.enableFreeformSupport()
                    if (FloatingApps.shizukuInstalled(activity) && !FloatingApps.shizukuReady()) {
                        FloatingApps.requestShizukuPermission(activity)
                    }
                }
                render()
            })
        })
        if (!enabled) return@apply
        when {
            FloatingApps.shizukuReady() -> addView(label(
                "Shizuku đã kết nối — cửa sổ nổi chạy ổn định. Lần đầu bật có thể cần khởi động lại máy.",
                12,
                GlassUi.ACCENT
            ).apply { setPadding(0, dp(8), 0, 0) })
            FloatingApps.shizukuInstalled(activity) -> {
                addView(label(
                    "Shizuku đã cài nhưng chưa cấp quyền cho Panels.",
                    12,
                    GlassUi.TEXT_MUTED
                ).apply { setPadding(0, dp(8), 0, 0) })
                addView(GlassUi.actionButton(activity, "Cấp quyền Shizuku") {
                    FloatingApps.requestShizukuPermission(activity)
                    render()
                })
            }
            else -> {
                addView(label(
                    "Chưa cài Shizuku. App sẽ thử mở cửa sổ nổi trực tiếp; nếu ROM bỏ qua, " +
                        "cài Shizuku (giống XGesture) để chạy chắc chắn.",
                    12,
                    GlassUi.TEXT_MUTED
                ).apply { setPadding(0, dp(8), 0, 0) })
                addView(GlassUi.actionButton(activity, "Cài Shizuku") {
                    val uri = Uri.parse("market://details?id=${FloatingApps.SHIZUKU_PACKAGE}")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://play.google.com/store/apps/details?id=${FloatingApps.SHIZUKU_PACKAGE}"
                        )))
                    }
                })
            }
        }
    }

    private fun tilesCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("layout-grid", getString(R.string.quick_settings_section)))
        addView(label(getString(R.string.quick_settings_description), 13, GlassUi.TEXT_SECONDARY).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        addView(GlassUi.actionButton(
            this@AdvancedSettingsActivity,
            getString(R.string.add_panels_tile)
        ) { QuickSettingsTiles.requestAdd(this@AdvancedSettingsActivity, accessibilityTile = false) })
        addView(GlassUi.actionButton(
            this@AdvancedSettingsActivity,
            getString(R.string.add_accessibility_tile)
        ) { QuickSettingsTiles.requestAdd(this@AdvancedSettingsActivity, accessibilityTile = true) })
    }

    private fun backupCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("download", getString(R.string.settings_backup_section)))
        addView(GlassUi.actionButton(
            this@AdvancedSettingsActivity,
            getString(R.string.export_settings)
        ) { (this@AdvancedSettingsActivity as? MainActivity)?.exportSettingsFile() })
        addView(GlassUi.actionButton(
            this@AdvancedSettingsActivity,
            getString(R.string.import_settings)
        ) {
            AlertDialog.Builder(this@AdvancedSettingsActivity)
                .setTitle(R.string.import_settings)
                .setMessage(R.string.import_settings_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.import_settings) { _, _ ->
                    (this@AdvancedSettingsActivity as? MainActivity)?.importSettingsFile()
                }
                .show()
        })
    }

    private fun statusCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("info", getString(R.string.project_status_section)))
        addView(label(getString(R.string.implemented_title), 15, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(8), 0, 0)
        })
        addView(label(getString(R.string.implemented_description), 13, GlassUi.TEXT_SECONDARY).apply {
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun cardHeader(icon: String, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@AdvancedSettingsActivity, icon, sizeDp = 36, glyphDp = 20))
            addView(
                GlassUi.cardTitle(this@AdvancedSettingsActivity, title).apply {
                    setPadding(dp(12), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
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
