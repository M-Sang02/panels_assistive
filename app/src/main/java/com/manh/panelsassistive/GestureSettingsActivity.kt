package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class GestureSettingsActivity : Activity() {
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
            "Thao tác và cử chỉ",
            "Gán hành động cho chạm bóng nổi và nút trợ lý."
        ))
        root.addView(gestureCard())
        root.addView(quickTileCard())
        root.addView(shortcutCard())
        root.addView(assistantCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun gestureCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@GestureSettingsActivity, getString(R.string.gestures_section)))
        addView(hint("Chạm, chạm đôi, chạm ba và nhấn giữ trên bóng nổi."))
        addGestureSelector(Gesture.SINGLE_TAP, R.string.single_tap)
        addGestureSelector(Gesture.DOUBLE_TAP, R.string.double_tap)
        addGestureSelector(Gesture.TRIPLE_TAP, R.string.triple_tap)
        addGestureSelector(Gesture.LONG_PRESS, R.string.long_press)
    }

    private fun LinearLayout.addGestureSelector(gesture: Gesture, title: Int) {
        addView(label(getString(title), 14, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(10), 0, dp(5))
        })
        val defaultAction = when (gesture) {
            Gesture.SINGLE_TAP -> ActionDispatcher.WIDGET_POPUP
            Gesture.DOUBLE_TAP -> ActionDispatcher.FLASHLIGHT
            Gesture.TRIPLE_TAP -> ActionDispatcher.EXPANSION_PANEL
            Gesture.LONG_PRESS -> ActionDispatcher.TOGGLE_FLOAT_BALL
            Gesture.SWIPE_UP -> ActionDispatcher.WIDGET_POPUP
        }
        val savedAction = AppPrefs.action(this@GestureSettingsActivity, gesture, defaultAction)
        addView(actionSpinner(savedAction, pickerTarget = gesture.preferenceKey) { action ->
            AppPrefs.setAction(this@GestureSettingsActivity, gesture, action)
            if (action == ActionDispatcher.BACKGROUND_RECORDING) requestRecordingPermission()
        })
    }

    private fun quickTileCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@GestureSettingsActivity, "Cài đặt nhanh"))
        addView(hint(
            "Tạo một ô trong bảng cài đặt nhanh. Icon của ô lấy theo hành động 1; " +
                "chạm để chạy hành động 1, nhấn giữ để chạy hành động 2."
        ))
        addView(label("Hành động 1 · Chạm trên ô", 14, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(10), 0, dp(5))
        })
        addView(actionSpinner(
            AppPrefs.quickTilePrimaryAction(this@GestureSettingsActivity),
            pickerTarget = WidgetPickerActivity.ACTION_PICKER_QUICK_TILE_PRIMARY
        ) { action ->
            AppPrefs.setQuickTilePrimaryAction(this@GestureSettingsActivity, action)
            if (action == ActionDispatcher.BACKGROUND_RECORDING) requestRecordingPermission()
            QuickSettingsTiles.updateListeningServices(this@GestureSettingsActivity)
        })
        addView(label("Hành động 2 · Nhấn giữ trên ô", 14, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(12), 0, dp(5))
        })
        addView(actionSpinner(
            AppPrefs.quickTileSecondaryAction(this@GestureSettingsActivity),
            pickerTarget = WidgetPickerActivity.ACTION_PICKER_QUICK_TILE_SECONDARY
        ) { action ->
            AppPrefs.setQuickTileSecondaryAction(this@GestureSettingsActivity, action)
            if (action == ActionDispatcher.BACKGROUND_RECORDING) requestRecordingPermission()
        })
        addView(GlassUi.actionButton(
            this@GestureSettingsActivity,
            "Thêm ô vào cài đặt nhanh",
            prominent = true
        ) { QuickSettingsTiles.requestAddQuickAction(this@GestureSettingsActivity) })
        addView(hint(
            "Android không cho ô cài đặt nhanh nhận nhấn giữ trực tiếp; " +
                "nhấn giữ ô sẽ mở màn hình ẩn chạy hành động 2 (một số ROM có thể mở cài đặt ô thay thế)."
        ))
    }

    private fun shortcutCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@GestureSettingsActivity, "Lối tắt màn hình chính"))
        addView(hint(
            "Chọn một tính năng như khi gán thao tác bóng nổi, sau đó ghim lối tắt ra màn " +
                "hình chính — launcher sẽ hỏi xác nhận trước khi thêm. Nhấn giữ icon ứng " +
                "dụng trên launcher để thấy sẵn lối tắt Bật/tắt bóng nổi."
        ))
        addView(GlassUi.actionButton(
            this@GestureSettingsActivity,
            "Tạo lối tắt tính năng",
            prominent = true
        ) { chooseShortcutAction() })
    }

    private fun chooseShortcutAction() {
        val actions = ActionDispatcher.selectableActions(this)
            .filter { it.id != ActionDispatcher.NONE }
        val items = actions.map { choice ->
            GlassDialogs.Item(
                id = choice.id,
                label = choice.label,
                actionId = choice.id
            )
        }
        GlassDialogs.show(
            this,
            "Chọn tính năng cho lối tắt",
            items,
            "Tính năng chạy khi chạm lối tắt"
        ) { picked ->
            val choice = actions.firstOrNull { it.id == picked.id } ?: return@show
            pinShortcut(choice)
        }
    }

    /** Dynamic launcher shortcut that runs one action, pinned on confirmation. */
    private fun pinShortcut(choice: ActionChoice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Lối tắt cần Android 8 trở lên.", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            Toast.makeText(
                this,
                "Launcher này không hỗ trợ ghim lối tắt trực tiếp.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val shortcut = ShortcutInfo.Builder(
            this,
            "action_" + choice.id + "_" + System.currentTimeMillis()
        )
            .setShortLabel(choice.label)
            .setLongLabel(choice.label)
            .setIcon(Icon.createWithBitmap(GlassUi.shortcutIcon(this, choice.id)))
            .setIntent(Intent(this, QuickTileActionsActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_SHORTCUT_ACTION, choice.id)
            })
            .build()
        val requested = runCatching {
            manager.requestPinShortcut(shortcut, null)
        }.getOrDefault(false)
        Toast.makeText(
            this,
            if (requested) "Xác nhận thêm lối tắt trên màn hình chính."
            else "Không tạo được lối tắt lúc này.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun assistantCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(GlassUi.cardTitle(this@GestureSettingsActivity, getString(R.string.assistant_replacement_section)))
        addView(hint(getString(R.string.assistant_replacement_description)))
        val held = isAssistantRoleHeld()
        val replacementEnabled = AppPrefs.assistantReplacementEnabled(this@GestureSettingsActivity)
        addView(label(
            getString(
                if (held) R.string.assistant_is_default
                else R.string.assistant_not_default
            ),
            12,
            GlassUi.ACCENT
        ).apply { setPadding(0, dp(4), 0, dp(2)) })
        if (replacementEnabled && !held) {
            addView(hint(
                "Hệ thống đang trả cử chỉ trợ lý về Google dù cài đặt vẫn hiện Panels. " +
                    "Thường gặp sau khi khởi động lại, cập nhật ứng dụng hoặc khi ROM tối ưu " +
                    "pin. Bấm Khôi phục để gán lại Panels làm trợ lý mặc định."
            ))
            addView(GlassUi.actionButton(
                this@GestureSettingsActivity,
                "Khôi phục trợ lý",
                prominent = true
            ) { requestAssistantRole() })
        }
        addView(GlassUi.actionButton(
            this@GestureSettingsActivity,
            getString(R.string.open_default_assistant_settings)
        ) { ActionDispatcher.openAssistantSettings(this@GestureSettingsActivity) })
        val assistantControls = LinearLayout(this@GestureSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (replacementEnabled) View.VISIBLE else View.GONE
        }
        assistantControls.addView(label(getString(R.string.assistant_replacement_action), 14, GlassUi.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        assistantControls.addView(actionSpinner(
            AppPrefs.assistantReplacementAction(this@GestureSettingsActivity),
            includeAssistant = false,
            pickerTarget = WidgetPickerActivity.ACTION_PICKER_ASSISTANT
        ) { action ->
            AppPrefs.setAssistantReplacementAction(this@GestureSettingsActivity, action)
            if (action == ActionDispatcher.BACKGROUND_RECORDING) requestRecordingPermission()
        })
        addView(GlassUi.switch(
            this@GestureSettingsActivity,
            replacementEnabled
        ) { enabled ->
            AppPrefs.setAssistantReplacementEnabled(this@GestureSettingsActivity, enabled)
            assistantControls.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) requestAssistantRole()
        }.apply {
            text = getString(R.string.assistant_replacement_title)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        addView(hint(getString(R.string.assistant_replacement_hint)))
        addView(assistantControls)
    }

    private fun actionSpinner(
        selectedAction: String,
        includeAssistant: Boolean = true,
        pickerTarget: String? = null,
        onSelected: (String) -> Unit
    ): View {
        val actions = actionChoices(includeAssistant)
        val selectedType = ActionDispatcher.selectedActionType(selectedAction)
        val selected = actions.firstOrNull { it.id == selectedType }
            ?: actions.firstOrNull {
                it.id == ActionDispatcher.NAV_GROUP && selectedType in ActionDispatcher.NAV_IDS
            }
            ?: actions.first()
        val selectedName = ActionDispatcher.selectedActionLabel(selectedAction)
            ?: ActionDispatcher.navLabel(this, selectedType)
            ?: ActionDispatcher.hideBallLabel(this, selectedAction)
            ?: selected.label
        val selectedIcon = if (selectedType in ActionDispatcher.NAV_IDS) selectedType else selected.id
        val selectedLabel = TextView(this).apply {
            text = selectedName
            textSize = 14f
            setTextColor(GlassUi.TEXT_PRIMARY)
            setPadding(dp(10), 0, 0, 0)
        }
        lateinit var button: LinearLayout
        button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GlassUi.glassSurface(this@GestureSettingsActivity, 14)
            isClickable = true
            isFocusable = true
            addView(ActionGlyphView(this@GestureSettingsActivity, selectedIcon, GlassUi.ACCENT), LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(selectedLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener {
                var currentId = selectedType
                fun commit(choice: ActionChoice) {
                    when {
                        choice.id == ActionDispatcher.OPEN_SELECTED_APP ||
                            choice.id == ActionDispatcher.OPEN_SELECTED_SHORTCUT -> {
                            val target = pickerTarget ?: return
                            startActivity(
                                Intent(this@GestureSettingsActivity, WidgetPickerActivity::class.java)
                                    .putExtra(
                                        WidgetPickerActivity.EXTRA_ITEM_TYPE,
                                        if (choice.id == ActionDispatcher.OPEN_SELECTED_APP) {
                                            PanelItemType.APP
                                        } else {
                                            PanelItemType.SHORTCUT
                                        }
                                    )
                                    .putExtra(WidgetPickerActivity.EXTRA_ACTION_PICKER_TARGET, target)
                            )
                        }
                        choice.requiresAccessibility &&
                            !AccessibilityActionService.isEnabled(this@GestureSettingsActivity) -> {
                            AlertDialog.Builder(this@GestureSettingsActivity)
                                .setTitle(R.string.accessibility_required_title)
                                .setMessage(R.string.accessibility_required_message)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.open_accessibility_settings) { _, _ ->
                                    ActionDispatcher.openAccessibilitySettings(this@GestureSettingsActivity)
                                }
                                .show()
                        }
                        else -> {
                            currentId = choice.id
                            onSelected(choice.id)
                            button.removeViewAt(0)
                            button.addView(
                                ActionGlyphView(this@GestureSettingsActivity, choice.id, GlassUi.ACCENT),
                                0,
                                LinearLayout.LayoutParams(dp(24), dp(24))
                            )
                            selectedLabel.text = choice.label
                        }
                    }
                }
                val items = actions.map { choice ->
                    GlassDialogs.Item(
                        id = choice.id,
                        label = choice.label,
                        actionId = choice.id.takeIf { it != ActionDispatcher.NONE },
                        glyph = if (choice.id == ActionDispatcher.NONE) "ban" else null,
                        selected = choice.id == currentId ||
                            (choice.id == ActionDispatcher.NAV_GROUP && currentId in ActionDispatcher.NAV_IDS) ||
                            (choice.id == ActionDispatcher.HIDE_FLOAT_BALL &&
                                currentId.startsWith("${ActionDispatcher.HIDE_FLOAT_BALL}:"))
                    )
                }
                GlassDialogs.show(
                    this@GestureSettingsActivity,
                    getString(R.string.select_action),
                    items,
                    "Gán hành động cho thao tác này"
                ) { picked ->
                    val choice = actions.firstOrNull { it.id == picked.id } ?: return@show
                    if (choice.id == ActionDispatcher.HIDE_FLOAT_BALL) {
                        ActionDispatcher.pickHideBallDuration(this@GestureSettingsActivity) { minutes ->
                            commit(
                                ActionChoice(
                                    ActionDispatcher.hideBallId(minutes),
                                    ActionDispatcher.hideBallLabel(this@GestureSettingsActivity, minutes)
                                )
                            )
                        }
                    } else if (choice.id == ActionDispatcher.NAV_GROUP) {
                        val navs = ActionDispatcher.navChoices(this@GestureSettingsActivity)
                        GlassDialogs.show(
                            this@GestureSettingsActivity,
                            choice.label,
                            navs.map {
                                GlassDialogs.Item(
                                    id = it.id,
                                    label = it.label,
                                    actionId = it.id,
                                    selected = it.id == currentId
                                )
                            },
                            "Chọn một nút điều hướng"
                        ) { navPicked ->
                            navs.firstOrNull { it.id == navPicked.id }?.let { commit(it) }
                        }
                    } else {
                        commit(choice)
                    }
                }
            }
        }
        return button
    }

    private fun actionChoices(includeAssistant: Boolean = true): List<ActionChoice> =
        ActionDispatcher.pickerActions(this@GestureSettingsActivity, includeAssistant)

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

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                try {
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                        REQUEST_ASSISTANT_ROLE
                    )
                    return
                } catch (error: android.content.ActivityNotFoundException) {
                    android.util.Log.w("GestureSettings", "Assistant role picker could not be opened", error)
                } catch (error: SecurityException) {
                    android.util.Log.w("GestureSettings", "Assistant role picker is not accessible", error)
                }
            }
        }
        ActionDispatcher.openAssistantSettings(this)
    }

    private fun isAssistantRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }
        val selectedService = Settings.Secure.getString(
            contentResolver,
            "voice_interaction_service"
        )
        return android.content.ComponentName.unflattenFromString(selectedService)
            ?.packageName == packageName
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
        private const val REQUEST_ASSISTANT_ROLE = 201
        private const val REQUEST_RECORDING_PERMISSION = 202
    }
}
