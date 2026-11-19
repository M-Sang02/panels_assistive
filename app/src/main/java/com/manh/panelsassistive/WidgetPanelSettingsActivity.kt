package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class WidgetPanelSettingsActivity : Activity() {
    private val foreground = Color.rgb(244, 247, 252)
    private val secondary = Color.rgb(170, 187, 205)
    private val pageColor = Color.rgb(7, 18, 33)
    private val cardColor = Color.argb(38, 210, 230, 250)
    private var landscape = false
    private lateinit var host: AppWidgetHost
    private lateinit var panelPicker: Spinner
    private lateinit var previewGrid: FrameLayout
    private lateinit var previewCenterSurface: FrameLayout
    private lateinit var previewScroll: HorizontalScrollView
    private var selectingPanel = false
    private var editingPreview = false
    private val panelRefreshHandler = Handler(Looper.getMainLooper())
    private val panelRefreshRunnable = Runnable {
        FloatBallService.refreshPanelLayout(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        landscape = intent.getBooleanExtra(EXTRA_LANDSCAPE_LAYOUT, false)
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        host = AppWidgetHost(applicationContext, FloatBallService.WIDGET_HOST_ID)
        try {
            host.startListening()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start widget previews", error)
            Toast.makeText(this, R.string.widget_host_unavailable, Toast.LENGTH_LONG).show()
        }
        render()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (landscape &&
            newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        ) {
            finish()
            startActivity(Intent(this, WidgetPanelSettingsActivity::class.java))
            return
        }
        if (!landscape &&
            newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            AppPrefs.landscapeWidgetLayoutEnabled(this)
        ) {
            landscape = true
        }
        render()
    }

    override fun onDestroy() {
        panelRefreshHandler.removeCallbacks(panelRefreshRunnable)
        try {
            host.stopListening()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to stop widget preview host", error)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::previewGrid.isInitialized) render()
    }

    private fun render() {
        val waitingForRotation = landscape &&
            resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        content.addView(text(
            getString(if (landscape) R.string.landscape_panel_editor else R.string.widget_panel_editor),
            25,
            foreground,
            true
        ).apply { setPadding(0, 0, 0, dp(8)) })
        if (waitingForRotation) {
            content.addView(text(getString(R.string.rotate_to_edit_landscape), 15, secondary))
            content.addView(button(R.string.back_to_portrait_panels) {
                finish()
                startActivity(Intent(this, WidgetPanelSettingsActivity::class.java))
            })
            setContentView(glassPage(content))
            return
        }
        if (landscape && !AppPrefs.landscapeWidgetLayoutEnabled(this)) {
            content.addView(text(getString(R.string.enable_landscape_panels_first), 15, secondary))
            setContentView(glassPage(content))
            return
        }
        if (AppPrefs.landscapeWidgetLayoutEnabled(this)) content.addView(orientationTabs())
        content.addView(panelManagement())
        content.addView(settingsCard())
        content.addView(text(getString(R.string.panel_preview), 18, foreground, true).apply {
            setPadding(0, dp(18), 0, dp(8))
        })
        previewGrid = FrameLayout(this).apply {
            clipToPadding = false
            clipChildren = false
        }
        previewScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            previewCenterSurface = FrameLayout(this@WidgetPanelSettingsActivity)
            addView(previewCenterSurface)
        }
        content.addView(previewScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ImageButton(this@WidgetPanelSettingsActivity).apply {
                setImageResource(android.R.drawable.ic_menu_view)
                setColorFilter(this@WidgetPanelSettingsActivity.foreground)
                contentDescription = getString(R.string.preview_panel_outside_app)
                background = roundedBackground(cardColor, dp(14))
                setOnClickListener { previewPopup() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(6)
            })
            addView(ImageButton(this@WidgetPanelSettingsActivity).apply {
                setImageResource(android.R.drawable.ic_input_add)
                setColorFilter(this@WidgetPanelSettingsActivity.foreground)
                contentDescription = getString(R.string.add_panel_item)
                background = roundedBackground(cardColor, dp(14))
                setOnClickListener { showAddItemDialog(nextAvailablePosition()) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(6), 0, dp(6), 0)
            })
            addView(ImageButton(this@WidgetPanelSettingsActivity).apply {
                setImageResource(android.R.drawable.ic_menu_edit)
                setColorFilter(this@WidgetPanelSettingsActivity.foreground)
                contentDescription = getString(
                    if (editingPreview) R.string.finish_editing_panel else R.string.edit_panel_items
                )
                isSelected = editingPreview
                background = roundedBackground(
                    if (editingPreview) Color.argb(150, 44, 104, 161) else cardColor,
                    dp(14)
                )
                setOnClickListener {
                    editingPreview = !editingPreview
                    contentDescription = getString(
                        if (editingPreview) R.string.finish_editing_panel else R.string.edit_panel_items
                    )
                    isSelected = editingPreview
                    background = roundedBackground(
                        if (editingPreview) Color.argb(150, 44, 104, 161) else cardColor,
                        dp(14)
                    )
                    renderPreview()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(6)
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        setContentView(glassPage(scroll))
        renderPanelPicker()
        renderPreview()
    }

    private fun glassPage(content: View): View = FrameLayout(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(8, 24, 43), Color.rgb(14, 33, 54), Color.rgb(7, 18, 33))
        )
        addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun orientationTabs(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(button(R.string.portrait_panels) {
            finish()
            startActivity(Intent(this@WidgetPanelSettingsActivity, WidgetPanelSettingsActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(button(R.string.landscape_panels) {
            finish()
            startActivity(
                Intent(this@WidgetPanelSettingsActivity, WidgetPanelSettingsActivity::class.java)
                    .putExtra(EXTRA_LANDSCAPE_LAYOUT, true)
            )
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
    }

    private fun panelManagement(): LinearLayout = glassCard().apply {
        panelPicker = Spinner(this@WidgetPanelSettingsActivity).apply {
            setSelection(0)
        }
        addView(panelPicker)
        addView(LinearLayout(this@WidgetPanelSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(R.string.add_panel, ::addPanel), LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button(R.string.rename_panel, ::renamePanel), LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button(R.string.delete_panel, ::deletePanel), LinearLayout.LayoutParams(0, dp(48), 1f))
        })
    }

    private fun settingsCard(): LinearLayout = glassCard().apply {
        addView(Switch(this@WidgetPanelSettingsActivity).apply {
            text = getString(R.string.show_panel_labels)
            setTextColor(this@WidgetPanelSettingsActivity.foreground)
            isChecked = AppPrefs.showPanelLabels(this@WidgetPanelSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.setShowPanelLabels(this@WidgetPanelSettingsActivity, checked)
                renderPreview()
                schedulePanelRefresh()
            }
        })
        addView(Switch(this@WidgetPanelSettingsActivity).apply {
            text = getString(R.string.close_panel_after_tap)
            setTextColor(this@WidgetPanelSettingsActivity.foreground)
            isChecked = AppPrefs.closePanelAfterTap(this@WidgetPanelSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.setClosePanelAfterTap(this@WidgetPanelSettingsActivity, checked)
            }
        })
        val blur = Switch(this@WidgetPanelSettingsActivity).apply {
            text = getString(R.string.popup_blur)
            setTextColor(this@WidgetPanelSettingsActivity.foreground)
            isChecked = AppPrefs.popupBlurEnabled(this@WidgetPanelSettingsActivity)
        }
        addView(blur)
        lateinit var blurScope: TextView
        blurScope = button(R.string.popup_blur_scope) {
            val screenScope = AppPrefs.popupBlurScope(this@WidgetPanelSettingsActivity) ==
                AppPrefs.BLUR_SCOPE_SCREEN
            AlertDialog.Builder(this@WidgetPanelSettingsActivity)
                .setTitle(R.string.popup_blur_scope)
                .setSingleChoiceItems(
                    arrayOf(
                        getString(R.string.popup_blur_scope_screen),
                        getString(R.string.popup_blur_scope_panel)
                    ),
                    if (screenScope) 0 else 1
                ) { dialog, which ->
                    AppPrefs.setPopupBlurScope(
                        this@WidgetPanelSettingsActivity,
                        if (which == 0) AppPrefs.BLUR_SCOPE_SCREEN else AppPrefs.BLUR_SCOPE_PANEL
                    )
                    blurScope.text = getString(
                        if (which == 0) R.string.popup_blur_scope_screen
                        else R.string.popup_blur_scope_panel
                    )
                    FloatBallService.refreshPopupAppearance(this@WidgetPanelSettingsActivity)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }.apply {
            text = getString(
                if (AppPrefs.popupBlurScope(this@WidgetPanelSettingsActivity) ==
                    AppPrefs.BLUR_SCOPE_SCREEN
                ) R.string.popup_blur_scope_screen else R.string.popup_blur_scope_panel
            )
            visibility = if (AppPrefs.popupBlurEnabled(this@WidgetPanelSettingsActivity)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        addView(blurScope)
        val blurRadius = valueControl(
            getString(R.string.blur_intensity),
            AppPrefs.popupBlurRadius(this@WidgetPanelSettingsActivity),
            0,
            80,
            enabled = AppPrefs.popupBlurEnabled(this@WidgetPanelSettingsActivity)
        ) { value ->
            AppPrefs.setPopupBlurRadius(this@WidgetPanelSettingsActivity, value)
            FloatBallService.refreshPopupAppearance(this@WidgetPanelSettingsActivity)
        }.apply {
            visibility = if (AppPrefs.popupBlurEnabled(this@WidgetPanelSettingsActivity)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        blur.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setPopupBlurEnabled(this@WidgetPanelSettingsActivity, checked)
            FloatBallService.refreshPopupAppearance(this@WidgetPanelSettingsActivity)
            blurRadius.isEnabled = checked
            blurScope.visibility = if (checked) View.VISIBLE else View.GONE
            blurRadius.visibility = if (checked) View.VISIBLE else View.GONE
        }
        addView(blurRadius)

        addView(valueControl(
            getString(R.string.panel_opacity),
            selectedPage().backgroundOpacity,
            0,
            100
        ) { value -> updatePage { it.copy(backgroundOpacity = value) } })

        addView(text(getString(R.string.panel_color), 14, secondary).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        addView(colorPalette())
        addView(valueControl(
            getString(R.string.grid_cell_width),
            selectedPage().cellWidthDp,
            30,
            120
        ) { value -> updatePage { it.copy(cellWidthDp = value) } })
        addView(valueControl(
            getString(R.string.grid_row_height),
            selectedPage().rowHeightDp,
            30,
            200
        ) { value -> updatePage { it.copy(rowHeightDp = value) } })
        addView(valueControl(
            getString(R.string.panel_rows),
            selectedPage().rows,
            1,
            10
        ) { value -> updatePage { it.copy(rows = value) } })
        addView(valueControl(
            getString(R.string.panel_columns),
            selectedPage().columns,
            2,
            20
        ) { value -> updatePage { it.copy(columns = value) } })
        addView(text(getString(R.string.panel_area_size), 14, secondary).apply {
            setPadding(0, dp(14), 0, dp(6))
        })
        addView(panelAreaControl())
    }

    private fun panelAreaControl(): View {
        val page = selectedPage()
        fun input(value: Int, hint: Int) = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setHint(hint)
            setHintTextColor(secondary)
            setTextColor(this@WidgetPanelSettingsActivity.foreground)
            gravity = Gravity.CENTER
            background = roundedBackground(cardColor, dp(14))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val columns = input(page.areaColumns, R.string.panel_area_columns_hint)
        val rows = input(page.areaRows, R.string.panel_area_rows_hint)
        val padding = input(page.areaPaddingDp, R.string.panel_area_padding_hint)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@WidgetPanelSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(columns, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(text("×", 16, this@WidgetPanelSettingsActivity.foreground).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(10), 0, dp(10), 0)
                })
                addView(rows, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
            addView(LinearLayout(this@WidgetPanelSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                addView(text("+", 16, this@WidgetPanelSettingsActivity.foreground).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 0, dp(10), 0)
                })
                addView(padding, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(text("dp", 13, secondary).apply {
                    setPadding(dp(8), 0, dp(10), 0)
                })
                addView(button(R.string.panel_apply) {
                    val areaColumns = columns.text.toString().toIntOrNull()
                    val areaRows = rows.text.toString().toIntOrNull()
                    val areaPadding = padding.text.toString().toIntOrNull()
                    if (areaColumns?.let { it in 2..30 } != true ||
                        areaRows?.let { it in 1..30 } != true ||
                        areaPadding?.let { it in 0..100 } != true
                    ) {
                        Toast.makeText(this@WidgetPanelSettingsActivity, R.string.panel_area_invalid, Toast.LENGTH_SHORT).show()
                        return@button
                    }
                    updatePage {
                        it.copy(
                            areaColumns = areaColumns!!,
                            areaRows = areaRows!!,
                            areaPaddingDp = areaPadding!!
                        )
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
        }
    }

    private fun updatePage(transform: (WidgetPanelPage) -> WidgetPanelPage) {
        AppPrefs.updatePanel(this, landscape, transform(selectedPage()))
        if (::previewGrid.isInitialized) renderPreview()
        schedulePanelRefresh()
    }

    private fun schedulePanelRefresh() {
        panelRefreshHandler.removeCallbacks(panelRefreshRunnable)
        panelRefreshHandler.postDelayed(panelRefreshRunnable, 250L)
    }

    private fun valueControl(
        title: String,
        initial: Int,
        minimum: Int,
        maximum: Int,
        enabled: Boolean = true,
        onValue: (Int) -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isEnabled = enabled
            setPadding(0, dp(7), 0, dp(4))
        }
        var current = initial.coerceIn(minimum, maximum)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text(title, 14, secondary), LinearLayout.LayoutParams(0, dp(36), 1f))
        val input = EditText(this).apply {
            setText(current.toString())
            setTextColor(this@WidgetPanelSettingsActivity.foreground)
            textSize = 14f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            background = roundedBackground(Color.argb(52, 220, 236, 250), dp(10))
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(38))
            isEnabled = enabled
        }
        header.addView(input)
        container.addView(header)
        val seek = SeekBar(this).apply {
            max = maximum - minimum
            progress = current - minimum
            isEnabled = enabled
        }
        var editing = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                current = minimum + progress
                if (!editing) {
                    editing = true
                    input.setText(current.toString())
                    input.setSelection(input.text.length)
                    editing = false
                }
                onValue(current)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (title == getString(R.string.panel_opacity)) renderPreview()
            }
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (editing) return
                val value = s?.toString()?.toIntOrNull() ?: return
                if (value in minimum..maximum) {
                    current = value
                    seek.progress = current - minimum
                    onValue(current)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                current = (input.text.toString().toIntOrNull() ?: current).coerceIn(minimum, maximum)
                editing = true
                input.setText(current.toString())
                editing = false
                seek.progress = current - minimum
                onValue(current)
            }
        }
        container.addView(seek)
        return container
    }

    private fun colorPalette(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val colors = intArrayOf(
            0xFF152438.toInt(), 0xFF182A3A.toInt(), 0xFF1E3042.toInt(),
            0xFF202735.toInt(), 0xFF172E36.toInt(), 0xFF292238.toInt(),
            0xFF26313B.toInt()
        )
        colors.forEach { color ->
            addView(View(this@WidgetPanelSettingsActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        dp(2),
                        if (selectedPage().backgroundColor == color) {
                            this@WidgetPanelSettingsActivity.foreground
                        } else {
                            Color.argb(80, 255, 255, 255)
                        }
                    )
                }
                isClickable = true
                contentDescription = getString(R.string.panel_color)
                setOnClickListener {
                    updatePage { it.copy(backgroundColor = color) }
                    render()
                }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                setMargins(0, dp(4), dp(8), dp(8))
            })
        }
        addView(ImageButton(this@WidgetPanelSettingsActivity).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(this@WidgetPanelSettingsActivity.foreground)
            background = roundedBackground(cardColor, dp(12))
            contentDescription = getString(R.string.custom_panel_color)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setOnClickListener { showCustomColorPicker() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            setMargins(dp(2), dp(2), 0, dp(2))
        })
    }

    private fun showCustomColorPicker() {
        val initial = selectedPage().backgroundColor
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initial, hsv)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val plane = CustomColorPlane(this, hsv[0], hsv[1], hsv[2]) { color ->
            updatePage { it.copy(backgroundColor = color) }
        }
        container.addView(plane, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(220)
        ))
        val hue = SeekBar(this).apply {
            max = 359
            progress = hsv[0].toInt().coerceIn(0, 359)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) plane.setHue(progress.toFloat())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(hue, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply { topMargin = dp(8) })
        AlertDialog.Builder(this)
            .setTitle(R.string.custom_panel_color)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun renderPanelPicker() {
        val pages = AppPrefs.panelPages(this, landscape)
        val selectedId = AppPrefs.currentPanelId(this, landscape)
        selectingPanel = true
        panelPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pages.map { it.name })
        panelPicker.setSelection(pages.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
        panelPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (selectingPanel) {
                    selectingPanel = false
                    return
                }
                AppPrefs.setCurrentPanel(this@WidgetPanelSettingsActivity, landscape, pages[position].id)
                render()
            }
        }
    }

    private fun renderPreview() {
        if (!::previewGrid.isInitialized) return
        previewGrid.removeAllViews()
        val page = selectedPage()
        val opacity = (page.backgroundOpacity * 255 / 100).coerceIn(0, 255)
        previewGrid.background = GradientDrawable().apply {
            setColor((page.backgroundColor and 0x00FFFFFF) or (opacity shl 24))
            cornerRadius = dp(26).toFloat()
            setStroke(1, Color.argb(opacity.coerceAtMost(110), 230, 240, 250))
        }
        val columns = page.columns.coerceIn(2, 20)
        val rows = maxOf(page.rows, page.items.maxOfOrNull {
            (it.position.coerceAtLeast(0) / columns) + it.spanY
        } ?: 1).coerceIn(1, 30)
        val grid = GridLayout(this).apply {
            columnCount = columns
            rowCount = rows
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = if (editingPreview) {
                PanelEditDialogs.guideBackground(
                    this@WidgetPanelSettingsActivity,
                    columns,
                    page.cellWidthDp,
                    page.rowHeightDp
                )
            } else {
                null
            }
        }
        val occupied = mutableSetOf<Int>()
        page.items.forEach { item ->
            val position = item.position.coerceAtLeast(0)
            val row = position / columns
            val column = position % columns
            if (row >= rows) return@forEach
            for (dy in 0 until item.spanY.coerceIn(1, rows - row)) {
                for (dx in 0 until item.spanX.coerceIn(1, columns - column)) {
                    occupied.add((row + dy) * columns + column + dx)
                }
            }
            val tile = previewTile(item, position)
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, item.spanY.coerceIn(1, rows - row)),
                GridLayout.spec(column, item.spanX.coerceIn(1, columns - column))
            ).apply {
                width = dp(page.cellWidthDp * item.spanX.coerceIn(1, columns - column))
                height = dp(page.rowHeightDp * item.spanY.coerceIn(1, rows - row))
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(tile, params)
        }
        for (position in 0 until rows * columns) {
            if (position in occupied) continue
            val row = position / columns
            val column = position % columns
            grid.addView(View(this).apply {
                if (editingPreview) {
                    contentDescription = getString(R.string.panel_grid_cell, row + 1, column + 1)
                }
            }, GridLayout.LayoutParams(
                GridLayout.spec(row),
                GridLayout.spec(column)
            ).apply {
                width = dp(page.cellWidthDp)
                height = dp(page.rowHeightDp)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }

        val viewportWidthDp = PanelViewportMetrics.widthDp(page.areaColumns, page.cellWidthDp)
        val viewportHeightDp = PanelViewportMetrics.heightDp(page.areaRows, page.rowHeightDp)
        val surfaceWidth = dp(maxOf(
            viewportWidthDp,
            PanelViewportMetrics.widthDp(columns, page.cellWidthDp)
        ))
        val surfaceHeight = dp(maxOf(
            viewportHeightDp,
            PanelViewportMetrics.heightDp(rows, page.rowHeightDp)
        ))
        val gridSurface = FrameLayout(this).apply {
            addView(grid, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        val vertical = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(gridSurface, FrameLayout.LayoutParams(surfaceWidth, surfaceHeight))
        }
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(vertical, FrameLayout.LayoutParams(surfaceWidth, dp(viewportHeightDp)))
        }
        val panelWidth = dp(PanelViewportMetrics.panelWidthDp(page))
        val panelHeight = dp(PanelViewportMetrics.panelHeightDp(page))
        previewGrid.layoutParams = FrameLayout.LayoutParams(panelWidth, panelHeight)
        previewGrid.addView(horizontal, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            val inset = dp(page.areaPaddingDp.coerceIn(0, 100))
            setMargins(inset, inset, inset, inset)
        })
        val centerWidth = maxOf(panelWidth, resources.displayMetrics.widthPixels - dp(36))
        previewCenterSurface.removeAllViews()
        previewCenterSurface.layoutParams = FrameLayout.LayoutParams(
            centerWidth,
            panelHeight
        )
        previewCenterSurface.addView(previewGrid, FrameLayout.LayoutParams(
            panelWidth,
            panelHeight,
            Gravity.CENTER
        ))
        previewScroll.layoutParams = previewScroll.layoutParams.apply {
            height = panelHeight
        }
        horizontal.post {
            horizontal.scrollTo(
                ((vertical.width - horizontal.width) / 2).coerceAtLeast(0),
                0
            )
            vertical.post {
                vertical.scrollTo(
                    0,
                    ((gridSurface.height - vertical.height) / 2).coerceAtLeast(0)
                )
            }
        }
        PanelEditDialogs.reattachUndoNotice(previewGrid)
    }

    private fun previewTile(item: PanelItem, position: Int): View {
        val page = selectedPage()
        val tile = FrameLayout(this).apply {
            background = roundedBackground(Color.argb(24, 255, 255, 255), dp(16))
        }
        if (item.type == PanelItemType.WIDGET) {
            val widgetId = item.value.toIntOrNull()
            val info = widgetId?.let { AppWidgetManager.getInstance(this).getAppWidgetInfo(it) }
            if (info != null) {
                try {
                    val widgetView = host.createView(this, widgetId, info)
                    val widthDp = page.cellWidthDp * item.spanX
                    val heightDp = page.rowHeightDp * item.spanY
                    widgetView.updateAppWidgetSize(
                        null,
                        widthDp,
                        heightDp,
                        widthDp,
                        heightDp
                    )
                    tile.addView(widgetView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Unable to preview widget $widgetId", error)
                }
            }
        } else {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val iconSize = dp(minOf(40, page.cellWidthDp - 12).coerceAtLeast(18))
            if (item.type == PanelItemType.ACTION) {
                cell.addView(
                    ActionGlyphView(this, item.value, Color.rgb(125, 188, 255)),
                    LinearLayout.LayoutParams(iconSize, iconSize)
                )
            } else {
                val icon = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    try {
                        val drawable = when (item.type) {
                            PanelItemType.APP -> ComponentName.unflattenFromString(item.value)
                                ?.let { packageManager.getActivityIcon(it) }
                            PanelItemType.SHORTCUT -> Intent.parseUri(item.value, 0)
                                .resolveActivity(packageManager)
                                ?.let { packageManager.getActivityIcon(it) }
                            else -> null
                        }
                        setImageDrawable(drawable ?: packageManager.getDefaultActivityIcon())
                    } catch (error: Exception) {
                        Log.w(TAG, "Unable to load preview icon", error)
                        setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                }
                cell.addView(icon)
            }
            if (AppPrefs.showPanelLabels(this)) {
                cell.addView(text(item.label, 11, foreground).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(4), dp(2), 0)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            tile.addView(cell, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        val showControls = Runnable {
            if (!editingPreview) return@Runnable
            if (tile.findViewWithTag<View>("remove") == null) {
                tile.addView(iconAction(false, R.string.remove_panel_item) {
                    removeItem(item)
                }.apply { tag = "remove" }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
                tile.addView(iconAction(true, R.string.resize_panel_item) {
                    showPreviewItemSizeDialog(item)
                }.apply {
                    tag = "resize"
                }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
                tile.addView(iconAction(false, R.string.move_panel_item, move = true) {
                    PanelEditDialogs.showMove(this, item.label) { dx, dy ->
                        movePreviewItemBy(item, dx, dy)
                    }
                }.apply {
                    tag = "move"
                }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.START))
                tile.foreground = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(2), Color.rgb(125, 188, 255))
                }
            }
        }
        if (editingPreview) showControls.run()
        tile.setOnTouchListener { _, _ -> editingPreview }
        if (editingPreview && item.type == PanelItemType.WIDGET) {
            tile.getChildAt(0)?.let(::blockPreviewTouches)
        }
        tile.contentDescription = item.label
        tile.tag = position
        return tile
    }

    private fun blockPreviewTouches(view: View) {
        view.setOnTouchListener { _, _ -> editingPreview }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                blockPreviewTouches(view.getChildAt(index))
            }
        }
    }

    private fun iconAction(
        resize: Boolean,
        description: Int,
        move: Boolean = false,
        action: () -> Unit
    ): View =
        FrameLayout(this).apply {
            background = roundedBackground(Color.argb(150, 7, 18, 33), dp(10))
            contentDescription = getString(description)
            isClickable = true
            isFocusable = true
            addView(ActionGlyphView(
                this@WidgetPanelSettingsActivity,
                resize,
                move,
                this@WidgetPanelSettingsActivity.foreground
            ).apply {
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            })
            setOnClickListener { action() }
        }

    private class CustomColorPlane(
        context: Context,
        private var hue: Float,
        private var saturation: Float,
        private var value: Float,
        private val onColor: (Int) -> Unit
    ) : View(context) {
        private val paint = Paint()
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun setHue(value: Float) {
            hue = value
            invalidate()
            emitColor()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val step = dp(6).coerceAtLeast(4)
            var y = 0
            while (y < height) {
                val yFraction = if (height > 0) y.toFloat() / height else 0f
                var x = 0
                while (x < width) {
                    val xFraction = if (width > 0) x.toFloat() / width else 0f
                    val valueFraction = 1f - yFraction
                    paint.color = Color.HSVToColor(floatArrayOf(hue, xFraction, valueFraction))
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + step).toFloat().coerceAtMost(width.toFloat()),
                        (y + step).toFloat().coerceAtMost(height.toFloat()),
                        paint
                    )
                    x += step
                }
                y += step
            }
            val markerX = saturation * width
            val markerY = (1f - value) * height
            canvas.drawCircle(markerX, markerY, dp(7).toFloat(), marker)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                event.actionMasked == android.view.MotionEvent.ACTION_MOVE
            ) {
                saturation = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                value = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                emitColor()
                return true
            }
            return super.onTouchEvent(event)
        }

        private fun emitColor() {
            onColor(Color.HSVToColor(floatArrayOf(hue, saturation, value)))
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private class ActionGlyphView(
        context: Context,
        private val resize: Boolean,
        private val move: Boolean,
        color: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width, height) / 24f
            if (scale <= 0f) return
            canvas.save()
            canvas.translate((width - scale * 24f) / 2f, (height - scale * 24f) / 2f)
            canvas.scale(scale, scale)
            if (move) {
                canvas.drawLine(12f, 4f, 12f, 20f, paint)
                canvas.drawLine(4f, 12f, 20f, 12f, paint)
                canvas.drawLine(12f, 4f, 9f, 7f, paint)
                canvas.drawLine(12f, 4f, 15f, 7f, paint)
                canvas.drawLine(12f, 20f, 9f, 17f, paint)
                canvas.drawLine(12f, 20f, 15f, 17f, paint)
                canvas.drawLine(4f, 12f, 7f, 9f, paint)
                canvas.drawLine(4f, 12f, 7f, 15f, paint)
                canvas.drawLine(20f, 12f, 17f, 9f, paint)
                canvas.drawLine(20f, 12f, 17f, 15f, paint)
            } else if (resize) {
                canvas.drawLine(6f, 18f, 18f, 6f, paint)
                canvas.drawLine(13f, 6f, 18f, 6f, paint)
                canvas.drawLine(18f, 6f, 18f, 11f, paint)
                canvas.drawLine(6f, 18f, 11f, 18f, paint)
            } else {
                canvas.drawLine(6f, 6f, 18f, 18f, paint)
                canvas.drawLine(18f, 6f, 6f, 18f, paint)
            }
            canvas.restore()
        }
    }

    private fun showPreviewItemSizeDialog(item: PanelItem) {
        val page = selectedPage()
        val current = page.items.firstOrNull { it.id == item.id } ?: return
        val column = current.position.coerceAtLeast(0) % page.columns
        PanelEditDialogs.showSize(
            this,
            current.spanX,
            current.spanY,
            (page.columns - column).coerceAtLeast(1)
        ) { spanX, spanY ->
            val overlaps = page.items.any { other ->
                if (other.id == current.id) return@any false
                val otherPosition = other.position.coerceAtLeast(0)
                val otherColumn = otherPosition % page.columns
                val otherRow = otherPosition / page.columns
                column < otherColumn + other.spanX &&
                    column + spanX > otherColumn &&
                    current.position / page.columns < otherRow + other.spanY &&
                    current.position / page.columns + spanY > otherRow
            }
            if (overlaps) return@showSize false
            val items = page.items.map {
                if (it.id == current.id) it.copy(spanX = spanX, spanY = spanY) else it
            }
            AppPrefs.updatePanel(this, landscape, page.copy(items = items))
            renderPreview()
            true
        }
    }

    private fun movePreviewItemBy(item: PanelItem, dx: Int, dy: Int) {
        val page = selectedPage()
        val current = page.items.firstOrNull { it.id == item.id } ?: return
        val columns = page.columns.coerceAtLeast(1)
        val source = current.position.coerceAtLeast(0)
        val sourceColumn = source % columns
        val sourceRow = source / columns
        val targetColumn = (sourceColumn + dx).coerceIn(0, (columns - current.spanX).coerceAtLeast(0))
        val targetRow = (sourceRow + dy).coerceIn(0, 19 - current.spanY.coerceAtLeast(1))
        val destination = targetRow * columns + targetColumn
        if (destination == source) return
        val overlaps = page.items.any { other ->
            if (other.id == current.id) return@any false
            val otherPosition = other.position.coerceAtLeast(0)
            val otherColumn = otherPosition % columns
            val otherRow = otherPosition / columns
            targetColumn < otherColumn + other.spanX &&
                targetColumn + current.spanX > otherColumn &&
                targetRow < otherRow + other.spanY &&
                targetRow + current.spanY > otherRow
        }
        if (overlaps) {
            Toast.makeText(this, R.string.panel_move_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        val items = page.items.map {
            if (it.id == current.id) it.copy(position = destination) else it
        }
        AppPrefs.updatePanel(this, landscape, page.copy(items = items))
        renderPreview()
    }

    private fun removeItem(item: PanelItem) {
        val page = selectedPage()
        AppPrefs.removePanelItem(this, landscape, page.id, item.id)
        renderPreview()
        PanelEditDialogs.showUndoNotice(
            previewGrid,
            getString(R.string.panel_item_removed),
            onUndo = {
                AppPrefs.addPanelItem(this, landscape, page.id, item)
                renderPreview()
            },
            onExpire = {
                if (item.type == PanelItemType.WIDGET) {
                    val id = item.value.toIntOrNull()
                    val usedIds = AppPrefs.widgetIds(this, false) + AppPrefs.widgetIds(this, true)
                    if (id != null && id.toString() !in usedIds) host.deleteAppWidgetId(id)
                }
            }
        )
    }

    private fun nextAvailablePosition(): Int {
        val page = selectedPage()
        val occupied = page.items.flatMap { item ->
            val position = item.position.coerceAtLeast(0)
            val row = position / page.columns
            val column = position % page.columns
            (0 until item.spanY.coerceIn(1, 10)).flatMap { dy ->
                (0 until item.spanX.coerceIn(1, page.columns - column)).map { dx ->
                    (row + dy) * page.columns + column + dx
                }
            }
        }.toSet()
        return (0 until page.columns * page.rows).firstOrNull { it !in occupied }
            ?: (occupied.maxOrNull()?.plus(1) ?: 0)
    }

    private fun showAddItemDialog(position: Int) {
        val options = arrayOf(
            getString(R.string.choose_widget),
            getString(R.string.choose_app),
            getString(R.string.choose_shortcut)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_panel_item)
            .setItems(options) { _, choice ->
                val type = when (choice) {
                    0 -> PanelItemType.WIDGET
                    1 -> PanelItemType.APP
                    else -> PanelItemType.SHORTCUT
                }
                startActivity(
                    Intent(this, WidgetPickerActivity::class.java)
                        .putExtra(WidgetPickerActivity.EXTRA_LANDSCAPE_LAYOUT, landscape)
                        .putExtra(WidgetPickerActivity.EXTRA_PANEL_ID, selectedPage().id)
                        .putExtra(WidgetPickerActivity.EXTRA_ITEM_TYPE, type)
                        .putExtra(WidgetPickerActivity.EXTRA_POSITION, position)
                )
            }
            .show()
    }

    private fun previewPopup() {
        FloatBallService.start(this, FloatBallService.ACTION_SHOW_POPUP, selectedPage().id)
    }

    private fun renamePanel() {
        editPanelName(R.string.rename_panel, selectedPage().name) { name ->
            AppPrefs.updatePanel(this, landscape, selectedPage().copy(name = name))
            render()
        }
    }

    private fun deletePanel() {
        if (AppPrefs.panelPages(this, landscape).size <= 1) {
            Toast.makeText(this, R.string.keep_one_panel, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_panel)
            .setMessage(getString(R.string.delete_panel_confirmation, selectedPage().name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_panel) { _, _ ->
                val page = selectedPage()
                page.items.filter { it.type == PanelItemType.WIDGET }.forEach { item ->
                    item.value.toIntOrNull()?.let { host.deleteAppWidgetId(it) }
                }
                AppPrefs.removePanel(this, landscape, page.id)
                render()
            }
            .show()
    }

    private fun addPanel() {
        editPanelName(R.string.add_panel) { name ->
            AppPrefs.addPanel(this, landscape, name)
            render()
        }
    }

    private fun editPanelName(title: Int, initial: String = "", onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            hint = getString(R.string.panel_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) onSave(name)
            }
            .show()
    }

    private fun selectedPage(): WidgetPanelPage {
        val pages = AppPrefs.panelPages(this, landscape)
        val id = AppPrefs.currentPanelId(this, landscape)
        return pages.firstOrNull { it.id == id } ?: pages.first()
    }

    private fun glassCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.argb(42, 230, 242, 255))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun button(title: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = getString(title)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(this@WidgetPanelSettingsActivity.foreground)
            background = roundedBackground(Color.argb(52, 210, 232, 252), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LANDSCAPE_LAYOUT = "com.manh.panelsassistive.extra.EDITOR_LANDSCAPE"
        private const val TAG = "WidgetPanelEditor"
    }
}
