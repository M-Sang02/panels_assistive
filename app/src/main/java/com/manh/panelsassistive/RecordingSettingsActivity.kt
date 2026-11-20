package com.manh.panelsassistive

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
        addView(GlassUi.cardTitle(this@RecordingSettingsActivity, getString(R.string.quick_recording_section)))
        addView(hint(getString(R.string.quick_recording_source_description)))
        val phoneSource = Switch(this@RecordingSettingsActivity).apply {
            text = getString(R.string.recording_phone_source)
            setTextColor(GlassUi.TEXT_PRIMARY)
            setPadding(0, dp(6), 0, dp(6))
            isChecked = AppPrefs.recordingPhoneSourceEnabled(this@RecordingSettingsActivity)
        }
        val externalSource = Switch(this@RecordingSettingsActivity).apply {
            text = getString(R.string.recording_external_source)
            setTextColor(GlassUi.TEXT_PRIMARY)
            setPadding(0, dp(6), 0, dp(6))
            isChecked = AppPrefs.recordingExternalSourceEnabled(this@RecordingSettingsActivity)
        }
        phoneSource.setOnCheckedChangeListener { button, checked ->
            if (!checked && !externalSource.isChecked) {
                button.isChecked = true
                return@setOnCheckedChangeListener
            }
            AppPrefs.setRecordingPhoneSourceEnabled(this@RecordingSettingsActivity, checked)
        }
        externalSource.setOnCheckedChangeListener { button, checked ->
            if (!checked && !phoneSource.isChecked) {
                button.isChecked = true
                return@setOnCheckedChangeListener
            }
            AppPrefs.setRecordingExternalSourceEnabled(this@RecordingSettingsActivity, checked)
        }
        addView(phoneSource)
        addView(externalSource)
    }

    private fun controlsCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(
            this@RecordingSettingsActivity,
            getString(R.string.recording_control_labels)
        ))
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
        addView(hint(getString(R.string.recording_drag_controls)))
    }

    private fun permissionCard(): LinearLayout = GlassUi.glassCard(this).apply {
        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        addView(GlassUi.cardTitle(this@RecordingSettingsActivity, "Quyền ghi âm"))
        addView(label(
            if (granted) "Đã cấp quyền micro." else "Chưa cấp quyền micro.",
            13,
            if (granted) GlassUi.ACCENT else GlassUi.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(2)) })
        if (!granted) {
            addView(GlassUi.actionButton(
                this@RecordingSettingsActivity,
                "Cấp quyền ghi âm"
            ) { requestRecordingPermission() })
        }
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
    ): Switch = GlassUi.switch(this, checked, onChanged).apply {
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
