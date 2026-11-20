package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsScreen(private val activity: Activity) {
    private val accent = GlassUi.ACCENT
    private val primaryText = GlassUi.TEXT_PRIMARY
    private val secondaryText = GlassUi.TEXT_SECONDARY
    private val mutedText = GlassUi.TEXT_MUTED

    fun createView(): ScrollView {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            background = GlassUi.pageBackground()
        }

        content.addView(label(activity.getString(R.string.brand_name), 11, accent, true).apply {
            letterSpacing = 0.18f
        })
        content.addView(label(activity.getString(R.string.settings_title), 28, primaryText, true).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        content.addView(label(activity.getString(R.string.settings_subtitle), 14, secondaryText))

        content.addView(sectionTitle(R.string.assistive_tools_section))
        content.addView(card().apply {
            addView(cardHeader("circle-dot", activity.getString(R.string.float_ball_title)))
            addView(label(activity.getString(R.string.float_ball_description), 14, secondaryText).apply {
                setPadding(0, dp(8), 0, dp(12))
            })
            addView(actionButton(R.string.float_ball_static_image) {
                (activity as? MainActivity)?.openFloatBallStaticPicker()
            })
            addView(label(activity.getString(R.string.float_ball_static_image_hint), 12, mutedText).apply {
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(actionButton(R.string.float_ball_gif_image) {
                (activity as? MainActivity)?.openFloatBallGifPicker()
            })
            val cropButton = actionButton(R.string.crop_float_ball_icon) {
                val mode = AppPrefs.floatBallIconMode(activity)
                (activity as? MainActivity)?.openFloatBallIconCrop(mode)
            }
            cropButton.visibility = if (
                (AppPrefs.floatBallIconMode(activity) == AppPrefs.FloatBallIconMode.GIF && AppPrefs.floatBallGifUri(activity) != null) ||
                (AppPrefs.floatBallIconMode(activity) == AppPrefs.FloatBallIconMode.STATIC && AppPrefs.floatBallStaticUris(activity).isNotEmpty())
            ) View.VISIBLE else View.GONE
            addView(cropButton)
            addView(label(activity.getString(R.string.float_ball_gif_hint), 12, mutedText).apply {
                setPadding(0, dp(4), 0, dp(4))
            })
            val gifControls = gifAnimationControls().apply {
                visibility = if (AppPrefs.floatBallIconMode(activity) == AppPrefs.FloatBallIconMode.GIF) {
                    View.VISIBLE
                } else View.GONE
            }
            val slideshowControls = slideshowControls().apply {
                visibility = if (
                    AppPrefs.floatBallIconMode(activity) == AppPrefs.FloatBallIconMode.STATIC &&
                    AppPrefs.floatBallStaticUris(activity).size >= 2
                ) View.VISIBLE else View.GONE
            }
            addView(gifControls)
            addView(slideshowControls)
            addView(actionButton(R.string.reset_float_ball_icon) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.reset_float_ball_icon)
                    .setMessage(R.string.reset_float_ball_icon_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.reset_float_ball_icon) { _, _ ->
                        AppPrefs.clearFloatBallIcon(activity)
                        updateFloatBall()
                    }
                    .show()
            })
            addView(switchRow(
                R.string.float_ball_toggle,
                R.string.float_ball_permission_hint,
                AppPrefs.floatBallEnabled(activity),
                ::setFloatBallEnabled
            ))
            addView(switchRow(
                R.string.start_on_boot,
                R.string.start_on_boot_description,
                AppPrefs.startOnBoot(activity)
            ) { enabled ->
                AppPrefs.setStartOnBoot(activity, enabled)
            })
            addView(label(activity.getString(R.string.float_ball_size), 14, secondaryText).apply {
                setPadding(0, dp(14), 0, 0)
            })
            addView(sizeControl())
            addView(label(activity.getString(R.string.float_ball_opacity), 14, secondaryText).apply {
                setPadding(0, dp(12), 0, 0)
            })
            addView(opacityControl())
            addView(label(activity.getString(R.string.float_ball_corner), 14, secondaryText).apply {
                setPadding(0, dp(12), 0, 0)
            })
            addView(cornerControl())
            val idleControls = idleDimControls().apply {
                visibility = if (AppPrefs.dimWhenIdle(activity)) View.VISIBLE else View.GONE
            }
            addView(switchRow(
                R.string.dim_when_idle,
                R.string.dim_when_idle_description,
                AppPrefs.dimWhenIdle(activity)
            ) { enabled ->
                AppPrefs.setDimWhenIdle(activity, enabled)
                idleControls.visibility = if (enabled) View.VISIBLE else View.GONE
                updateFloatBall()
            })
            addView(idleControls)
            val hapticOptions = hapticControls().apply {
                visibility = if (AppPrefs.hapticsEnabled(activity)) View.VISIBLE else View.GONE
            }
            addView(switchRow(
                R.string.haptic_feedback,
                R.string.haptic_feedback_description,
                AppPrefs.hapticsEnabled(activity)
            ) { enabled ->
                AppPrefs.setHapticsEnabled(activity, enabled)
                hapticOptions.visibility = if (enabled) View.VISIBLE else View.GONE
            })
            addView(hapticOptions)
            addView(switchRow(
                R.string.snap_to_edge,
                R.string.snap_to_edge_description,
                AppPrefs.snapToEdge(activity)
            ) { AppPrefs.setSnapToEdge(activity, it) })
            val edgeControls = floatBallEdgeControls()
            edgeControls.visibility =
                if (AppPrefs.floatBallEdgeHideEnabled(activity)) View.VISIBLE else View.GONE
            addView(switchRow(
                R.string.float_ball_edge_hide,
                R.string.float_ball_edge_hide_description,
                AppPrefs.floatBallEdgeHideEnabled(activity)
            ) { enabled ->
                AppPrefs.setFloatBallEdgeHideEnabled(activity, enabled)
                edgeControls.visibility = if (enabled) View.VISIBLE else View.GONE
                updateFloatBall()
            })
            addView(edgeControls)
        })

        content.addView(card().apply {
            addView(cardHeader("palette", "Nền bảng"))
            addView(label("Tùy chỉnh màu nền, độ trong suốt, làm mờ và bo góc cho các bảng dùng chung.", 14, secondaryText).apply {
                setPadding(0, dp(8), 0, dp(12))
            })
            addView(actionButton(R.string.background_panel_settings) {
                activity.startActivity(Intent(activity, BackgroundSettingsActivity::class.java))
            })
        })

        content.addView(card().apply {
            addView(cardHeader("square-stack", activity.getString(R.string.widget_popup_title)))
            addView(label(activity.getString(R.string.widget_popup_description), 14, secondaryText).apply {
                setPadding(0, dp(8), 0, dp(12))
            })
            addView(actionButton(R.string.edit_widget_panels) {
                activity.startActivity(
                    Intent(activity, WidgetPanelSettingsActivity::class.java)
                        .putExtra(WidgetPanelSettingsActivity.EXTRA_LANDSCAPE_LAYOUT, false)
                )
            })
            val widgetControls = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (AppPrefs.widgetPopupEnabled(activity)) View.VISIBLE else View.GONE
            }
            widgetControls.addView(switchRow(
                R.string.separate_landscape_layout,
                R.string.separate_landscape_layout_description,
                AppPrefs.landscapeWidgetLayoutEnabled(activity)
            ) {
                AppPrefs.setLandscapeWidgetLayoutEnabled(activity, it)
            })
            addView(switchRow(
                R.string.widget_popup_enabled,
                R.string.widget_popup_enabled_description,
                AppPrefs.widgetPopupEnabled(activity)
            ) { enabled ->
                AppPrefs.setWidgetPopupEnabled(activity, enabled)
                widgetControls.visibility = if (enabled) View.VISIBLE else View.GONE
                if (!enabled && FloatBallService.running) {
                    activity.startService(Intent(activity, FloatBallService::class.java).apply {
                        action = FloatBallService.ACTION_HIDE_POPUP
                    })
                }
            })
            addView(widgetControls)
        })

        content.addView(sectionTitle(R.string.quick_recording_section))
        content.addView(card().apply {
            addView(label(
                activity.getString(R.string.quick_recording_source_description),
                14,
                secondaryText
            ).apply { setPadding(0, 0, 0, dp(8)) })
            val phoneSource = Switch(activity).apply {
                text = activity.getString(R.string.recording_phone_source)
                setTextColor(primaryText)
                isChecked = AppPrefs.recordingPhoneSourceEnabled(activity)
            }
            val externalSource = Switch(activity).apply {
                text = activity.getString(R.string.recording_external_source)
                setTextColor(primaryText)
                isChecked = AppPrefs.recordingExternalSourceEnabled(activity)
            }
            phoneSource.setOnCheckedChangeListener { button, checked ->
                if (!checked && !externalSource.isChecked) {
                    button.isChecked = true
                    return@setOnCheckedChangeListener
                }
                AppPrefs.setRecordingPhoneSourceEnabled(activity, checked)
            }
            externalSource.setOnCheckedChangeListener { button, checked ->
                if (!checked && !phoneSource.isChecked) {
                    button.isChecked = true
                    return@setOnCheckedChangeListener
                }
                AppPrefs.setRecordingExternalSourceEnabled(activity, checked)
            }
            addView(phoneSource)
            addView(externalSource)
            addView(switchRow(
                R.string.recording_control_labels,
                R.string.recording_control_labels_description,
                AppPrefs.recordingControlLabels(activity)
            ) {
                AppPrefs.setRecordingControlLabels(activity, it)
                BackgroundRecordingService.refreshControls(activity)
            })
        })

        content.addView(sectionTitle(R.string.gestures_section))
        content.addView(card().apply {
            addGestureSelector(Gesture.SINGLE_TAP, R.string.single_tap)
            addGestureSelector(Gesture.DOUBLE_TAP, R.string.double_tap)
            addGestureSelector(Gesture.TRIPLE_TAP, R.string.triple_tap)
            addGestureSelector(Gesture.LONG_PRESS, R.string.long_press)
        })

        content.addView(sectionTitle(R.string.assistant_replacement_section))
        content.addView(card().apply {
            addView(label(activity.getString(R.string.assistant_replacement_description), 14, secondaryText))
            addView(actionButton(R.string.open_default_assistant_settings) {
                (activity as? MainActivity)?.openAssistantSettings()
            })
            val assistantControls = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (AppPrefs.assistantReplacementEnabled(activity)) View.VISIBLE else View.GONE
            }
            assistantControls.addView(label(
                activity.getString(
                    if ((activity as? MainActivity)?.isAssistantRoleHeld() == true) {
                        R.string.assistant_is_default
                    } else {
                        R.string.assistant_not_default
                    }
                ),
                12,
                accent
            ).apply { setPadding(0, dp(8), 0, dp(2)) })
            assistantControls.addView(label(activity.getString(R.string.assistant_replacement_action), 14, primaryText, true).apply {
                setPadding(0, dp(12), 0, dp(4))
            })
            assistantControls.addView(actionSpinner(
                AppPrefs.assistantReplacementAction(activity),
                includeAssistant = false,
                pickerTarget = WidgetPickerActivity.ACTION_PICKER_ASSISTANT
            ) {
                AppPrefs.setAssistantReplacementAction(activity, it)
                if (it == ActionDispatcher.BACKGROUND_RECORDING) {
                    (activity as? MainActivity)?.requestRecordingPermission()
                }
            })
            addView(switchRow(
                R.string.assistant_replacement_title,
                R.string.assistant_replacement_hint,
                AppPrefs.assistantReplacementEnabled(activity)
            ) { enabled ->
                AppPrefs.setAssistantReplacementEnabled(activity, enabled)
                assistantControls.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) (activity as? MainActivity)?.requestAssistantRole()
            })
            addView(assistantControls)
        })

        content.addView(sectionTitle(R.string.permissions_section))
        content.addView(card().apply {
            addView(label(activity.getString(R.string.overlay_permission_title), 16, primaryText, true))
            addView(label(
                activity.getString(
                    if (Settings.canDrawOverlays(activity)) R.string.overlay_permission_granted
                    else R.string.overlay_permission_required
                ),
                14,
                secondaryText
            ).apply { setPadding(0, dp(6), 0, dp(8)) })
            if (!Settings.canDrawOverlays(activity)) {
                addView(actionButton(R.string.grant_overlay_permission, ::requestOverlayPermission))
            }
            addView(label(activity.getString(R.string.accessibility_optional_title), 16, primaryText, true).apply {
                setPadding(0, dp(14), 0, 0)
            })
            addView(label(activity.getString(
                if (AccessibilityActionService.isEnabled(activity)) R.string.accessibility_enabled
                else R.string.accessibility_disabled
            ), 14, secondaryText).apply { setPadding(0, dp(6), 0, dp(8)) })
            addView(actionButton(R.string.open_accessibility_settings) {
                ActionDispatcher.openAccessibilitySettings(activity)
            })
            addView(actionButton(R.string.open_battery_optimization_settings) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                } else {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${activity.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
            addView(label(activity.getString(R.string.accessibility_optional_description), 13, mutedText).apply {
                setPadding(0, dp(8), 0, 0)
            })
        })

        content.addView(sectionTitle(R.string.quick_settings_section))
        content.addView(card().apply {
            addView(label(activity.getString(R.string.quick_settings_description), 14, secondaryText))
            addView(actionButton(R.string.add_panels_tile) {
                QuickSettingsTiles.requestAdd(activity, accessibilityTile = false)
            })
            addView(actionButton(R.string.add_accessibility_tile) {
                QuickSettingsTiles.requestAdd(activity, accessibilityTile = true)
            })
        })

        content.addView(sectionTitle(R.string.settings_backup_section))
        content.addView(card().apply {
            addView(actionButton(R.string.export_settings) {
                (activity as? MainActivity)?.exportSettingsFile()
            })
            addView(actionButton(R.string.import_settings) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.import_settings)
                    .setMessage(R.string.import_settings_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.import_settings) { _, _ ->
                        (activity as? MainActivity)?.importSettingsFile()
                    }
                    .show()
            })
        })

        content.addView(sectionTitle(R.string.project_status_section))
        content.addView(infoCard(
            R.string.implemented_title,
            R.string.implemented_description
        ))

        return ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
    }

    private fun LinearLayout.addGestureSelector(gesture: Gesture, title: Int) {
        addView(label(activity.getString(title), 14, primaryText, true).apply {
            setPadding(0, dp(10), 0, dp(5))
        })
        val defaultAction = when (gesture) {
            Gesture.SINGLE_TAP -> ActionDispatcher.WIDGET_POPUP
            Gesture.DOUBLE_TAP -> ActionDispatcher.FLASHLIGHT
            Gesture.TRIPLE_TAP -> ActionDispatcher.EXPANSION_PANEL
            Gesture.LONG_PRESS -> ActionDispatcher.TOGGLE_FLOAT_BALL
            Gesture.SWIPE_UP -> ActionDispatcher.WIDGET_POPUP
        }
        val savedAction = AppPrefs.action(activity, gesture, defaultAction)
        addView(actionSpinner(savedAction, pickerTarget = gesture.preferenceKey) { action ->
            AppPrefs.setAction(activity, gesture, action)
            if (action == ActionDispatcher.BACKGROUND_RECORDING) {
                (activity as? MainActivity)?.requestRecordingPermission()
            }
        })
    }

    private fun actionSpinner(
        selectedAction: String,
        includeAssistant: Boolean = true,
        pickerTarget: String? = null,
        onSelected: (String) -> Unit
    ): View {
        val actions = actionChoices(includeAssistant)
        val selectedType = ActionDispatcher.selectedActionType(selectedAction)
        val selected = actions.firstOrNull { it.id == selectedType } ?: actions.first()
        val selectedName = ActionDispatcher.selectedActionLabel(selectedAction) ?: selected.label
        val selectedLabel = TextView(activity).apply {
            text = selectedName
            textSize = 14f
            setTextColor(primaryText)
            setPadding(dp(10), 0, 0, 0)
        }
        lateinit var button: LinearLayout
        button = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GlassUi.glassSurface(activity, 14)
            isClickable = true
            isFocusable = true
            addView(ActionGlyphView(activity, selected.id, accent), LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(selectedLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener {
                val choices = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                lateinit var dialog: AlertDialog
                actions.forEach { choice ->
                    choices.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        isClickable = true
                        isFocusable = true
                        addView(ActionGlyphView(activity, choice.id, accent), LinearLayout.LayoutParams(dp(28), dp(28)))
                        addView(TextView(activity).apply {
                            text = choice.label
                            textSize = 15f
                            setTextColor(primaryText)
                            setPadding(dp(12), 0, 0, 0)
                        })
                        setOnClickListener {
                            dialog.dismiss()
                            if (choice.id == ActionDispatcher.OPEN_SELECTED_APP ||
                                choice.id == ActionDispatcher.OPEN_SELECTED_SHORTCUT
                            ) {
                                val target = pickerTarget ?: return@setOnClickListener
                                activity.startActivity(
                                    Intent(activity, WidgetPickerActivity::class.java)
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
                                return@setOnClickListener
                            }
                            if (choice.requiresAccessibility && !AccessibilityActionService.isEnabled(activity)) {
                                AlertDialog.Builder(activity)
                                    .setTitle(R.string.accessibility_required_title)
                                    .setMessage(R.string.accessibility_required_message)
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(R.string.open_accessibility_settings) { _, _ ->
                                        ActionDispatcher.openAccessibilitySettings(activity)
                                    }
                                    .show()
                            } else {
                                onSelected(choice.id)
                                button.removeViewAt(0)
                                button.addView(
                                    ActionGlyphView(activity, choice.id, accent),
                                    0,
                                    LinearLayout.LayoutParams(dp(24), dp(24))
                                )
                                selectedLabel.text = choice.label
                            }
                        }
                    })
                }
                dialog = AlertDialog.Builder(activity)
                    .setTitle(R.string.select_action)
                    .setView(ScrollView(activity).apply { addView(choices) })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                dialog.show()
            }
        }
        return button
    }

    private fun actionChoices(includeAssistant: Boolean = true): List<ActionChoice> = buildList {
        add(ActionChoice(ActionDispatcher.NONE, activity.getString(R.string.action_none)))
        addAll(ActionDispatcher.quickActions(activity, includeAssistant))
        add(ActionChoice(
            ActionDispatcher.OPEN_SELECTED_APP,
            activity.getString(R.string.action_open_selected_app)
        ))
        add(ActionChoice(
            ActionDispatcher.OPEN_SELECTED_SHORTCUT,
            activity.getString(R.string.action_open_selected_shortcut)
        ))
        add(ActionChoice(ActionDispatcher.EXPANSION_PANEL, activity.getString(R.string.action_expansion_panel)))
        add(ActionChoice(ActionDispatcher.BRIGHTNESS_SLIDER, activity.getString(R.string.action_brightness_slider)))
        add(ActionChoice(ActionDispatcher.MEDIA_VOLUME, activity.getString(R.string.action_media_volume)))
        add(ActionChoice(ActionDispatcher.TOGGLE_FLOAT_BALL, activity.getString(R.string.action_toggle_float_ball)))
        add(ActionChoice(ActionDispatcher.ACCESSIBILITY_SETTINGS, activity.getString(R.string.action_accessibility_settings)))
        add(ActionChoice(ActionDispatcher.OPEN_APP, activity.getString(R.string.action_open_settings)))
    }

    private fun updateFloatBall() {
        if (FloatBallService.running) {
            activity.startService(Intent(activity, FloatBallService::class.java).apply {
                action = FloatBallService.ACTION_UPDATE_FLOAT_BALL
            })
        }
    }

    private fun switchRow(
        title: Int,
        description: Int,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(activity.getString(title), 14, primaryText, true))
            addView(label(activity.getString(description), 12, mutedText).apply {
                setPadding(0, dp(3), dp(12), 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(GlassUi.switch(activity, checked) { enabled -> onChanged(enabled) })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        }
    }

    private fun sizeControl(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizeInput = android.widget.EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(primaryText)
            setSingleLine(true)
            setEms(3)
            gravity = Gravity.CENTER
            background = GlassUi.fieldBackground(activity, 12)
            setHintTextColor(mutedText)
            setText(AppPrefs.floatBallSize(activity).toString())
            setSelectAllOnFocus(true)
            hint = "20-80"
        }
        val slider = GlassUi.slider(activity).apply {
            max = 60
            progress = AppPrefs.floatBallSize(activity) - 20
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        fun commitSizeInput() {
            val size = sizeInput.text.toString().toIntOrNull()
            if (size == null || size !in 20..80) {
                sizeInput.error = activity.getString(R.string.float_ball_size_error)
                return
            }
            sizeInput.error = null
            AppPrefs.setFloatBallSize(activity, size)
            slider.progress = size - 20
            updateFloatBall()
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val size = progress + 20
                AppPrefs.setFloatBallSize(activity, size)
                if (!sizeInput.hasFocus()) sizeInput.setText(size.toString())
                updateFloatBall()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        sizeInput.setOnEditorActionListener { _, _, _ ->
            commitSizeInput()
            sizeInput.clearFocus()
            true
        }
        sizeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitSizeInput()
        }
        row.addView(slider)
        row.addView(sizeInput, LinearLayout.LayoutParams(dp(64), dp(48)))
        return row
    }

    private fun opacityControl(): SeekBar = GlassUi.slider(activity).apply {
        max = 85
        progress = AppPrefs.floatBallOpacity(activity) - 15
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AppPrefs.setFloatBallOpacity(activity, progress + 15)
                updateFloatBall()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun cornerControl(): SeekBar = GlassUi.slider(activity).apply {
        max = 100
        progress = AppPrefs.floatBallCorner(activity)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    AppPrefs.setFloatBallCorner(activity, progress)
                    updateFloatBall()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun floatBallEdgeControls(): LinearLayout {
        var editOrientation = AppPrefs.floatBallEditOrientation(activity)
        var leftSide = AppPrefs.floatBallFixedSide(activity) == "left"
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun tab(label: String, selected: () -> Boolean, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setTextColor(if (selected()) accent else mutedText)
                background = if (selected()) {
                    GlassUi.tabSurface(activity, true)
                } else {
                    GlassUi.tabSurface(activity, false)
                }
                setOnClickListener { onClick() }
            }
        lateinit var portraitTab: TextView
        lateinit var landscapeTab: TextView
        lateinit var bothTab: TextView
        lateinit var rightTab: TextView
        lateinit var leftTab: TextView
        lateinit var offsetValue: TextView
        lateinit var offsetSlider: SeekBar

        fun refresh() {
            val portraitSelected = editOrientation == "portrait"
            val landscapeSelected = editOrientation == "landscape"
            val bothSelected = editOrientation == "both"
            portraitTab.setTextColor(if (portraitSelected) accent else mutedText)
            portraitTab.background = if (portraitSelected) GlassUi.tabSurface(activity, true) else GlassUi.tabSurface(activity, false)
            landscapeTab.setTextColor(if (landscapeSelected) accent else mutedText)
            landscapeTab.background = if (landscapeSelected) GlassUi.tabSurface(activity, true) else GlassUi.tabSurface(activity, false)
            bothTab.setTextColor(if (bothSelected) accent else mutedText)
            bothTab.background = if (bothSelected) GlassUi.tabSurface(activity, true) else GlassUi.tabSurface(activity, false)
            val landscape = landscapeSelected
            rightTab.setTextColor(if (!leftSide) accent else mutedText)
            rightTab.background = if (!leftSide) GlassUi.tabSurface(activity, true) else GlassUi.tabSurface(activity, false)
            leftTab.setTextColor(if (leftSide) accent else mutedText)
            leftTab.background = if (leftSide) GlassUi.tabSurface(activity, true) else GlassUi.tabSurface(activity, false)
            val value = if (bothSelected) {
                AppPrefs.floatBallEdgeOffset(activity, false, leftSide)
            } else {
                AppPrefs.floatBallEdgeOffset(activity, landscape, leftSide)
            }
            if (offsetSlider.progress != value) offsetSlider.progress = value
            offsetValue.text = activity.getString(R.string.float_ball_edge_offset_value, value)
        }

        portraitTab = tab(activity.getString(R.string.float_ball_portrait), { editOrientation == "portrait" }) {
            editOrientation = "portrait"
            AppPrefs.setFloatBallEditOrientation(activity, editOrientation)
            refresh()
        }
        landscapeTab = tab(activity.getString(R.string.float_ball_landscape), { editOrientation == "landscape" }) {
            editOrientation = "landscape"
            AppPrefs.setFloatBallEditOrientation(activity, editOrientation)
            refresh()
        }
        bothTab = tab(activity.getString(R.string.float_ball_both), { editOrientation == "both" }) {
            editOrientation = "both"
            AppPrefs.setFloatBallEditOrientation(activity, editOrientation)
            refresh()
        }
        tabs.addView(portraitTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        tabs.addView(landscapeTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        tabs.addView(bothTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        controls.addView(tabs)

        val sideTabs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        rightTab = tab(activity.getString(R.string.float_ball_right_side), { !leftSide }) {
            leftSide = false
            AppPrefs.setFloatBallFixedSide(activity, "right")
            refresh()
        }
        leftTab = tab(activity.getString(R.string.float_ball_left_side), { leftSide }) {
            leftSide = true
            AppPrefs.setFloatBallFixedSide(activity, "left")
            refresh()
        }
        sideTabs.addView(leftTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        sideTabs.addView(rightTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        controls.addView(sideTabs)

        offsetValue = label("", 13, secondaryText)
        controls.addView(offsetValue)
        offsetSlider = GlassUi.slider(activity).apply {
            max = 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (editOrientation == "both") {
                        AppPrefs.setFloatBallEdgeOffset(activity, false, leftSide, progress)
                        AppPrefs.setFloatBallEdgeOffset(activity, true, leftSide, progress)
                    } else {
                        AppPrefs.setFloatBallEdgeOffset(
                            activity,
                            editOrientation == "landscape",
                            leftSide,
                            progress
                        )
                    }
                    offsetValue.text = activity.getString(R.string.float_ball_edge_offset_value, progress)
                    updateFloatBall()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controls.addView(offsetSlider)
        controls.addView(switchRow(
            R.string.float_ball_auto_mirror,
            R.string.float_ball_auto_mirror_description,
            AppPrefs.floatBallAutoMirror(activity)
        ) { enabled ->
            AppPrefs.setFloatBallAutoMirror(activity, enabled)
            updateFloatBall()
        })
        refresh()
        return controls
    }

    private fun idleDimControls(): LinearLayout {
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val opacityValue = label(
            activity.getString(R.string.idle_opacity_value, AppPrefs.idleOpacity(activity)),
            13,
            secondaryText
        )
        controls.addView(label(activity.getString(R.string.idle_opacity), 14, secondaryText))
        controls.addView(opacityValue)
        controls.addView(GlassUi.slider(activity).apply {
            max = 75
            progress = AppPrefs.idleOpacity(activity) - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val opacity = progress + 5
                    AppPrefs.setIdleOpacity(activity, opacity)
                    opacityValue.text = activity.getString(R.string.idle_opacity_value, opacity)
                    updateFloatBall()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        val delayValue = label(
            activity.getString(R.string.idle_delay_value, AppPrefs.idleDelaySeconds(activity)),
            13,
            secondaryText
        )
        controls.addView(label(activity.getString(R.string.idle_delay), 14, secondaryText))
        controls.addView(delayValue)
        controls.addView(GlassUi.slider(activity).apply {
            max = 59
            progress = AppPrefs.idleDelaySeconds(activity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val seconds = progress + 1
                    AppPrefs.setIdleDelaySeconds(activity, seconds)
                    delayValue.text = activity.getString(R.string.idle_delay_value, seconds)
                    updateFloatBall()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        controls.addView(switchRow(
            R.string.freeze_animation_when_idle,
            R.string.freeze_animation_when_idle_description,
            AppPrefs.freezeAnimationWhenIdle(activity)
        ) {
            AppPrefs.setFreezeAnimationWhenIdle(activity, it)
            updateFloatBall()
        })
        return controls
    }

    private fun gifAnimationControls(): LinearLayout {
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val idleOptions = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        controls.addView(switchRow(
            R.string.float_ball_play_gif,
            R.string.float_ball_play_gif_description,
            AppPrefs.floatBallGifPlayEnabled(activity)
        ) {
            AppPrefs.setFloatBallGifPlayEnabled(activity, it)
            idleOptions.visibility = if (
                it && AppPrefs.floatBallGifPauseWhenIdle(activity)
            ) View.VISIBLE else View.GONE
            updateFloatBall()
        })
        controls.addView(switchRow(
            R.string.float_ball_gif_pause_idle,
            R.string.float_ball_gif_pause_idle_description,
            AppPrefs.floatBallGifPauseWhenIdle(activity)
        ) {
            AppPrefs.setFloatBallGifPauseWhenIdle(activity, it)
            idleOptions.visibility = if (
                AppPrefs.floatBallGifPlayEnabled(activity) && it
            ) View.VISIBLE else View.GONE
            updateFloatBall()
        })
        val idleValue = label(
            activity.getString(
                R.string.float_ball_gif_idle_seconds,
                AppPrefs.floatBallGifIdleSeconds(activity)
            ),
            13,
            secondaryText
        )
        idleOptions.addView(idleValue)
        idleOptions.addView(GlassUi.slider(activity).apply {
            max = 25
            progress = AppPrefs.floatBallGifIdleSeconds(activity) - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val seconds = progress + 5
                    AppPrefs.setFloatBallGifIdleSeconds(activity, seconds)
                    idleValue.text =
                        activity.getString(R.string.float_ball_gif_idle_seconds, seconds)
                    updateFloatBall()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        idleOptions.visibility = if (
            AppPrefs.floatBallGifPlayEnabled(activity) &&
            AppPrefs.floatBallGifPauseWhenIdle(activity)
        ) View.VISIBLE else View.GONE
        controls.addView(idleOptions)
        return controls
    }

    private fun slideshowControls(): LinearLayout {
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(switchRow(
            R.string.float_ball_slideshow,
            R.string.float_ball_slideshow_description,
            AppPrefs.floatBallSlideshowEnabled(activity)
        ) {
            AppPrefs.setFloatBallSlideshowEnabled(activity, it)
            updateFloatBall()
        })
        val intervalValue = label(
            activity.getString(
                R.string.float_ball_slideshow_interval,
                AppPrefs.floatBallSlideshowIntervalSeconds(activity)
            ),
            13,
            secondaryText
        )
        controls.addView(intervalValue)
        controls.addView(GlassUi.slider(activity).apply {
            max = 295
            progress = AppPrefs.floatBallSlideshowIntervalSeconds(activity) - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val seconds = progress + 5
                    AppPrefs.setFloatBallSlideshowIntervalSeconds(activity, seconds)
                    intervalValue.text =
                        activity.getString(R.string.float_ball_slideshow_interval, seconds)
                    updateFloatBall()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        controls.addView(switchRow(
            R.string.float_ball_slideshow_pause_screen_off,
            R.string.float_ball_slideshow_pause_screen_off_description,
            AppPrefs.floatBallSlideshowPauseWhenScreenOff(activity)
        ) {
            AppPrefs.setFloatBallSlideshowPauseWhenScreenOff(activity, it)
            updateFloatBall()
        })
        return controls
    }

    private fun hapticControls(): LinearLayout {
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val strengthValue = label(
            activity.getString(R.string.haptic_strength_value, AppPrefs.hapticStrength(activity)),
            13,
            secondaryText
        )
        controls.addView(label(activity.getString(R.string.haptic_strength), 14, secondaryText))
        controls.addView(strengthValue)
        controls.addView(GlassUi.slider(activity).apply {
            max = 99
            progress = AppPrefs.hapticStrength(activity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val strength = progress + 1
                    AppPrefs.setHapticStrength(activity, strength)
                    strengthValue.text = activity.getString(R.string.haptic_strength_value, strength)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        return controls
    }

    private fun blurControl(): SeekBar = GlassUi.slider(activity).apply {
        max = 80
        progress = AppPrefs.popupBlurRadius(activity)
        isEnabled = AppPrefs.popupBlurEnabled(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AppPrefs.setPopupBlurRadius(activity, progress)
                FloatBallService.refreshPopupAppearance(activity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setFloatBallEnabled(enabled: Boolean) {
        if (!enabled) {
            FloatBallService.stop(activity)
        } else if (Settings.canDrawOverlays(activity)) {
            FloatBallService.start(activity)
        } else {
            AppPrefs.setPendingOverlayEnable(activity, true)
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    private fun actionButton(title: Int, action: () -> Unit): TextView =
        GlassUi.actionButton(activity, activity.getString(title), onAction = action).apply {
            minHeight = dp(46)
        }

    private fun sectionTitle(title: Int): TextView =
        GlassUi.sectionHeader(activity, activity.getString(title))

    private fun cardHeader(icon: String, title: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(activity, icon, sizeDp = 36, glyphDp = 20))
            addView(
                GlassUi.cardTitle(activity, title).apply { setPadding(dp(12), 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

    private fun infoCard(title: Int, description: Int): LinearLayout =
        card().apply {
            addView(label(activity.getString(title), 16, primaryText, true))
            addView(label(activity.getString(description), 14, secondaryText).apply {
                setPadding(0, dp(7), 0, 0)
            })
        }

    private fun card(): LinearLayout = GlassUi.glassCard(activity)

    private fun label(
        text: String,
        sizeSp: Int,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(activity).apply {
        this.text = text
        textSize = sizeSp.toFloat()
        setTextColor(color)
        gravity = Gravity.START
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = GlassUi.dp(activity, value)
}

