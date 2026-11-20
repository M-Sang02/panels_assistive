package com.manh.panelsassistive

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RecordingSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        render()
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
            getString(R.string.quick_recording_section),
            "Ghi âm nền bằng cử chỉ trên bóng nổi."
        ))
        root.addView(sourcesCard())
        root.addView(controlsCard())
        root.addView(permissionCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sourcesCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("audio-lines", "Nguồn âm thanh"))
        addView(hint(getString(R.string.quick_recording_source_description)))
        val ctx = this@RecordingSettingsActivity
        var phoneRow: Pair<LinearLayout, LucideGlyphView>? = null
        var externalRow: Pair<LinearLayout, LucideGlyphView>? = null
        fun refresh() {
            val phone = AppPrefs.recordingPhoneSourceEnabled(ctx)
            val external = AppPrefs.recordingExternalSourceEnabled(ctx)
            phoneRow?.let { (row, check) ->
                row.background = GlassUi.tabSurface(ctx, phone)
                check.visibility = if (phone) View.VISIBLE else View.GONE
            }
            externalRow?.let { (row, check) ->
                row.background = GlassUi.tabSurface(ctx, external)
                check.visibility = if (external) View.VISIBLE else View.GONE
            }
        }
        fun toggleSource(phone: Boolean) {
            val enabled = if (phone) {
                AppPrefs.recordingPhoneSourceEnabled(ctx)
            } else {
                AppPrefs.recordingExternalSourceEnabled(ctx)
            }
            val otherEnabled = if (phone) {
                AppPrefs.recordingExternalSourceEnabled(ctx)
            } else {
                AppPrefs.recordingPhoneSourceEnabled(ctx)
            }
            // Keep at least one source active so the recorder still has input.
            if (enabled && !otherEnabled) return
            if (phone) {
                AppPrefs.setRecordingPhoneSourceEnabled(ctx, !enabled)
            } else {
                AppPrefs.setRecordingExternalSourceEnabled(ctx, !enabled)
            }
            refresh()
        }
        phoneRow = sourceRow(
            "smartphone",
            getString(R.string.recording_phone_source),
            "Micro tích hợp trên điện thoại."
        ) { toggleSource(true) }
        externalRow = sourceRow(
            "headphones",
            getString(R.string.recording_external_source),
            "Tai nghe hoặc micro ngoài được kết nối."
        ) { toggleSource(false) }
        addView(phoneRow!!.first)
        addView(externalRow!!.first)
        refresh()
    }

    private fun controlsCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardHeader("sliders-horizontal", getString(R.string.recording_control_labels)))
        addView(hint(getString(R.string.recording_control_labels_description)))
        addView(styledSwitch(
            "Hiện nhãn Pause/Stop",
            AppPrefs.recordingControlLabels(this@RecordingSettingsActivity)
        ) { checked ->
            AppPrefs.setRecordingControlLabels(this@RecordingSettingsActivity, checked)
            BackgroundRecordingService.refreshControls(this@RecordingSettingsActivity)
        })
        addView(GlassUi.actionButton(
            this@RecordingSettingsActivity,
            "Gán cử chỉ ghi âm nhanh"
        ) {
            startActivity(Intent(this@RecordingSettingsActivity, GestureSettingsActivity::class.java))
        })
        addView(GlassUi.actionButton(
            this@RecordingSettingsActivity,
            "Mở bản ghi đã lưu"
        ) {
            startActivity(Intent(this@RecordingSettingsActivity, RecordingPlaybackActivity::class.java).apply {
                action = RecordingPlaybackActivity.ACTION_SHOW_RECORDINGS
            })
        })
        addView(hint(getString(R.string.recording_drag_controls)))
    }

    private fun permissionCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        addView(cardHeader(if (granted) "shield-check" else "mic-off", "Quyền ghi âm"))
        val statusRow = LinearLayout(this@RecordingSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        statusRow.addView(LucideGlyphView(
            this@RecordingSettingsActivity,
            if (granted) "check" else "mic-off",
            if (granted) GlassUi.ACCENT else GlassUi.TEXT_SECONDARY
        ), LinearLayout.LayoutParams(dp(16), dp(16)))
        statusRow.addView(label(
            if (granted) "Đã cấp quyền micro." else "Chưa cấp quyền micro.",
            13,
            if (granted) GlassUi.ACCENT else GlassUi.TEXT_SECONDARY
        ).apply { setPadding(dp(8), 0, 0, 0) })
        addView(statusRow)
        if (!granted) {
            addView(GlassUi.actionButton(
                this@RecordingSettingsActivity,
                "Cấp quyền ghi âm",
                prominent = true
            ) { requestRecordingPermission() })
        }
    }

    private fun cardHeader(icon: String, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@RecordingSettingsActivity, icon, sizeDp = 36, glyphDp = 20))
            addView(
                label(title, 16, GlassUi.TEXT_PRIMARY, true).apply { setPadding(dp(10), 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

    private fun sourceRow(
        icon: String,
        title: String,
        description: String,
        onClick: () -> Unit
    ): Pair<LinearLayout, LucideGlyphView> {
        val check = LucideGlyphView(this, "check", GlassUi.ACCENT)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                LucideGlyphView(this@RecordingSettingsActivity, icon, GlassUi.TEXT_SECONDARY),
                LinearLayout.LayoutParams(dp(20), dp(20))
            )
            addView(LinearLayout(this@RecordingSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                addView(label(title, 14, GlassUi.TEXT_PRIMARY, true))
                addView(label(description, 12, GlassUi.TEXT_MUTED).apply {
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(check, LinearLayout.LayoutParams(dp(18), dp(18)))
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        return row to check
    }

    private fun requestRecordingPermission() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_RECORDING_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORDING_PERMISSION) render()
    }

    private fun styledSwitch(
        title: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) = GlassUi.switch(this, checked, onChanged).apply {
        text = title
        setTextColor(GlassUi.TEXT_PRIMARY)
        setPadding(0, dp(6), 0, dp(6))
    }

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

    companion object {
        private const val REQUEST_RECORDING_PERMISSION = 301
    }
}
