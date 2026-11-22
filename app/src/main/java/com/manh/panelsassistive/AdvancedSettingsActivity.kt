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
import android.widget.Toast
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

    private fun exportBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "panels-assistive-settings.json")
        try {
            startActivityForResult(intent, REQUEST_EXPORT_SETTINGS)
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Unable to open the export picker", error)
            Toast.makeText(this, R.string.settings_file_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
        try {
            startActivityForResult(intent, REQUEST_IMPORT_SETTINGS)
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Unable to open the import picker", error)
            Toast.makeText(this, R.string.settings_file_error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT_SETTINGS -> try {
                val serialized = AppPrefs.exportSettings(this)
                val output = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Unable to open the selected file.")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(serialized) }
                Toast.makeText(this, R.string.settings_exported, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Unable to export settings", error)
                Toast.makeText(this, R.string.settings_file_error, Toast.LENGTH_LONG).show()
            }
            REQUEST_IMPORT_SETTINGS -> try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Unable to open the selected file.")
                val serialized = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                AppPrefs.importSettings(this, serialized)
                if (AppPrefs.floatBallEnabled(this)) {
                    if (Settings.canDrawOverlays(this)) {
                        FloatBallService.start(this)
                    } else {
                        AppPrefs.setPendingOverlayEnable(this, true)
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                } else {
                    FloatBallService.stop(this)
                }
                Toast.makeText(this, R.string.settings_imported, Toast.LENGTH_SHORT).show()
                render()
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Unable to import settings", error)
                Toast.makeText(this, R.string.settings_file_error, Toast.LENGTH_LONG).show()
            }
        }
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
        root.addView(widgetPromptCard())
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
        addView(GlassUi.actionRow(
            this@AdvancedSettingsActivity,
            getString(R.string.open_accessibility_settings),
            getString(R.string.open_battery_optimization_settings),
            onFirst = { ActionDispatcher.openAccessibilitySettings(this@AdvancedSettingsActivity) },
            onSecond = {
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
            }
        ))
        addView(label(getString(R.string.accessibility_optional_description), 12, GlassUi.TEXT_MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun freeformCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val activity = this@AdvancedSettingsActivity
        addView(cardHeader("app-window", "Cửa sổ tự do"))
        addView(label(
            "Mở app từ bảng trong cửa sổ nổi. Bên trong có kiểu mở, chế độ OEM cho " +
                "ColorOS / HarmonyOS / OriginOS / FlymeOS và chỉnh kích thước, vị trí.",
            13,
            GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, dp(8), 0, dp(6)) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Bật cửa sổ tự do", 14, GlassUi.TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(GlassUi.switch(activity, AppPrefs.freeformApps(activity)) { on ->
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
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            isFocusable = true
            addView(label("Cài đặt cửa sổ tự do", 14, GlassUi.TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LucideGlyphView(activity, "chevron-right", GlassUi.TEXT_MUTED),
                LinearLayout.LayoutParams(dp(18), dp(18)))
            setOnClickListener {
                startActivity(Intent(activity, FreeformSettingsActivity::class.java))
            }
        })
    }

    private fun tilesCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("layout-grid", getString(R.string.quick_settings_section)))
        addView(label(getString(R.string.quick_settings_description), 13, GlassUi.TEXT_SECONDARY).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        addView(GlassUi.actionRow(
            this@AdvancedSettingsActivity,
            getString(R.string.add_panels_tile),
            getString(R.string.add_accessibility_tile),
            onFirst = {
                QuickSettingsTiles.requestAdd(this@AdvancedSettingsActivity, accessibilityTile = false)
            },
            onSecond = {
                QuickSettingsTiles.requestAdd(this@AdvancedSettingsActivity, accessibilityTile = true)
            }
        ))
    }

    /** Re-enables the short "Add this widget?" question after it was skipped. */
    private fun widgetPromptCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val activity = this@AdvancedSettingsActivity
        addView(cardHeader("layout-grid", "Widget màn hình chính"))
        addView(label(
            "Hỏi một câu ngắn trước khi tạo widget và xin quyền hiển thị. " +
                "Tắt để lần sau bấm Thêm là tạo và cho phép luôn.",
            13,
            GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, dp(8), 0, dp(6)) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Hỏi trước khi thêm tiện ích", 14, GlassUi.TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(GlassUi.switch(activity, AppPrefs.widgetConfirmEnabled(activity)) { on ->
                AppPrefs.setWidgetConfirmEnabled(activity, on)
            })
        })
    }

    private fun backupCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("download", getString(R.string.settings_backup_section)))
        addView(GlassUi.actionRow(
            this@AdvancedSettingsActivity,
            getString(R.string.export_settings),
            getString(R.string.import_settings),
            prominentFirst = true,
            onFirst = { exportBackup() },
            onSecond = {
                AlertDialog.Builder(this@AdvancedSettingsActivity)
                    .setTitle(R.string.import_settings)
                    .setMessage(R.string.import_settings_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.import_settings) { _, _ -> importBackup() }
                    .show()
            }
        ))
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

    private companion object {
        private const val TAG = "AdvancedSettingsActivity"
        private const val REQUEST_EXPORT_SETTINGS = 45
        private const val REQUEST_IMPORT_SETTINGS = 46
    }
}
