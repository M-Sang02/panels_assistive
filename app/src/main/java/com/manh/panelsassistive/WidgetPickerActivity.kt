package com.manh.panelsassistive

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.xmlpull.v1.XmlPullParser

class WidgetPickerActivity : Activity() {
    private val foreground = GlassUi.TEXT_PRIMARY
    private val secondary = GlassUi.TEXT_SECONDARY
    private val cardColor = Color.argb(24, 224, 236, 255)
    private lateinit var host: AppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var providers: List<AppWidgetProviderInfo> = emptyList()
    private var landscapeLayout: Boolean = false
    private var panelId: String = ""
    private var targetPosition: Int = -1
    private var expansionSlot: Int = -1
    private var actionPickerTarget: String? = null
    private var currentType: String = PanelItemType.WIDGET
    private var appActivities: List<ResolveInfo> = emptyList()
    private var shortcutActivities: List<ResolveInfo> = emptyList()
    private val expandedPackages = mutableSetOf<String>()
    private val visibleProviderCounts = mutableMapOf<String, Int>()
    private val categoryButtons = mutableListOf<Pair<String, Button>>()
    private val providerLabels = mutableMapOf<ComponentName, String>()
    private val appLabels = mutableMapOf<String, String>()
    private val previewExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var appShortcuts: List<AppShortcut>? = null
    private var shortcutsLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(applicationContext, FloatBallService.WIDGET_HOST_ID)
        appWidgetManager = AppWidgetManager.getInstance(this)
        landscapeLayout = intent.getBooleanExtra(EXTRA_LANDSCAPE_LAYOUT, false)
        panelId = intent.getStringExtra(EXTRA_PANEL_ID)
            ?: AppPrefs.currentPanelId(this, landscapeLayout)
        targetPosition = intent.getIntExtra(EXTRA_POSITION, -1)
        expansionSlot = intent.getIntExtra(EXTRA_EXPANSION_SLOT, -1)
        actionPickerTarget = intent.getStringExtra(EXTRA_ACTION_PICKER_TARGET)
        currentType = intent.getStringExtra(EXTRA_ITEM_TYPE) ?: PanelItemType.WIDGET
        if (expansionSlot < 0 && currentType == PanelItemType.ACTION) {
            currentType = PanelItemType.WIDGET
        }
        pendingWidgetId = savedInstanceState?.getInt(STATE_PENDING_WIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        providers = appWidgetManager.installedProviders.sortedBy { providerLabel(it).lowercase() }

        setContentView(createContent())
        renderSelection("")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PENDING_WIDGET_ID, pendingWidgetId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        previewExecutor.shutdownNow()
        if (isFinishing && FloatBallService.running) {
            startService(Intent(this, FloatBallService::class.java).apply {
                action = FloatBallService.ACTION_REFRESH_WIDGETS
            })
        }
        super.onDestroy()
    }

    @Deprecated("Uses the platform App Widget bind and configure activities")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_BIND -> {
                if (resultCode == RESULT_OK) {
                    finishBinding()
                } else {
                    discardPendingWidget()
                    Toast.makeText(this, R.string.widget_permission_not_granted, Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CONFIGURE -> {
                if (resultCode == RESULT_OK) {
                    savePendingWidget()
                } else {
                    discardPendingWidget()
                }
            }
            REQUEST_SHORTCUT -> {
                if (resultCode == RESULT_OK) saveShortcut(data)
            }
        }
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = GlassUi.pageBackground()
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.brand_name)
            textSize = 11f
            setTextColor(GlassUi.ACCENT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.18f
        })
        root.addView(TextView(this).apply {
            text = getString(when (currentType) {
                PanelItemType.ACTION -> R.string.choose_phone_shortcut
                PanelItemType.APP -> R.string.choose_app
                PanelItemType.SHORTCUT -> R.string.choose_shortcut
                else -> R.string.choose_widget
            })
            textSize = 22f
            setTextColor(this@WidgetPickerActivity.foreground)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(16))
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (expansionSlot >= 0) {
                addView(categoryButton(PanelItemType.ACTION, R.string.choose_phone_shortcut))
            } else {
                addView(categoryButton(PanelItemType.WIDGET, R.string.choose_widget))
            }
            addView(categoryButton(PanelItemType.APP, R.string.choose_app))
            addView(categoryButton(PanelItemType.SHORTCUT, R.string.choose_shortcut))
        })
        search = EditText(this).apply {
            hint = getString(when (currentType) {
                PanelItemType.APP -> R.string.search_apps
                PanelItemType.SHORTCUT -> R.string.search_shortcuts
                PanelItemType.ACTION -> R.string.choose_phone_shortcut
                else -> R.string.search_widgets
            })
            setSingleLine(true)
            setTextColor(this@WidgetPickerActivity.foreground)
            setHintTextColor(secondary)
            background = GlassUi.fieldBackground(this@WidgetPickerActivity, 18)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        search.addTextChangedListener(SimpleTextWatcher { renderSelection(it) })
        return root
    }

    private fun renderType(type: String) {
        currentType = type
        categoryButtons.forEach { (category, button) ->
            val selected = category == type
            button.setTextColor(if (selected) GlassUi.ACCENT else secondary)
            button.background = GlassUi.tabSurface(this, selected)
        }
        search.hint = getString(when (type) {
            PanelItemType.APP -> R.string.search_apps
            PanelItemType.SHORTCUT -> R.string.search_shortcuts
            PanelItemType.ACTION -> R.string.choose_phone_shortcut
            else -> R.string.search_widgets
        })
        search.setText("")
        renderSelection("")
    }

    private fun renderSelection(query: String) {
        list.removeAllViews()
        when (currentType) {
            PanelItemType.ACTION -> renderActions(query)
            PanelItemType.WIDGET -> {
                if (providers.isEmpty()) {
                    list.addView(message(getString(R.string.no_widgets_available)))
                } else {
                    renderProviders(query)
                }
            }
            PanelItemType.APP -> renderAppActivities(query)
            PanelItemType.SHORTCUT -> renderShortcutActivities(query)
        }
    }

    private fun renderActions(query: String) {
        // A panel item cannot hold the gesture-only "pick an app/shortcut"
        // targets or a do-nothing action; those are assigned from Tác vụ và cử chỉ.
        val actions = ActionDispatcher.pickerActions(this)
            .filter {
                it.id != ActionDispatcher.NONE &&
                    it.id != ActionDispatcher.OPEN_SELECTED_APP &&
                    it.id != ActionDispatcher.OPEN_SELECTED_SHORTCUT
            }
            .filter { it.label.contains(query, ignoreCase = true) }
        if (actions.isEmpty()) {
            list.addView(message(getString(R.string.no_shortcuts_available)))
            return
        }
        actions.forEach { action ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground()
                isClickable = true
                isFocusable = true
                addView(
                    ActionGlyphView(this@WidgetPickerActivity, action.id, GlassUi.ACCENT),
                    LinearLayout.LayoutParams(dp(42), dp(42))
                )
                addView(TextView(this@WidgetPickerActivity).apply {
                    text = action.label
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), 0, 0, 0)
                })
                setOnClickListener {
                    if (action.id == ActionDispatcher.NAV_GROUP) {
                        chooseNavAction(action.label)
                    } else if (action.id == ActionDispatcher.HIDE_FLOAT_BALL) {
                        chooseHideDuration()
                    } else {
                        saveAction(action.id, action.label)
                    }
                }
            }
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            list.addView(row)
        }
    }

    /** One row stands for back/home/recents; the button is chosen afterwards. */
    private fun chooseNavAction(title: String) {
        val navs = ActionDispatcher.navChoices(this)
        GlassDialogs.show(
            this,
            title,
            navs.map { GlassDialogs.Item(id = it.id, label = it.label, actionId = it.id) },
            "Chọn một nút điều hướng"
        ) { picked ->
            navs.firstOrNull { it.id == picked.id }?.let { saveAction(it.id, it.label) }
        }
    }

    /** The hide-ball entry is followed by a duration chooser, like the nav group. */
    private fun chooseHideDuration() {
        ActionDispatcher.pickHideBallDuration(this) { minutes ->
            saveAction(
                ActionDispatcher.hideBallId(minutes),
                ActionDispatcher.hideBallLabel(this, minutes)
            )
        }
    }

    private fun saveAction(actionId: String, label: String) {
        val slot = expansionSlot
        if (slot >= 0) {
            AppPrefs.setExpansionShortcut(
                this,
                slot,
                ExpansionShortcut(PanelItemType.ACTION, actionId, label)
            )
        } else {
            AppPrefs.addPanelItem(
                this,
                landscapeLayout,
                panelId,
                PanelItem(PanelItemType.ACTION, actionId, label, targetPosition),
                allowOverlap = AppPrefs.panelFreeMode(this)
            )
        }
        finishPicking()
    }

    private fun renderProviders(query: String) {
        val groups = providers.groupBy { it.provider.packageName }
            .map { (packageName, appProviders) ->
                WidgetGroup(packageName, appLabel(packageName, appProviders), appProviders)
            }
            .filter { group ->
                query.isBlank() ||
                    group.label.contains(query, ignoreCase = true) ||
                group.providers.any { providerLabel(it).contains(query, ignoreCase = true) }
            }
            .sortedBy { it.label.lowercase() }
        if (groups.isEmpty()) {
            list.addView(message(getString(R.string.no_widgets_match)))
            return
        }
        groups.forEach { group ->
            val matchingProviders = group.providers.filter {
                providerLabel(it).contains(query, ignoreCase = true)
            }
            val expandedBySearch = query.isNotBlank() &&
                (group.label.contains(query, ignoreCase = true) || matchingProviders.isNotEmpty())
            val expanded = expandedBySearch || group.packageName in expandedPackages
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(cardColor)
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (group.packageName in expandedPackages) {
                        expandedPackages.remove(group.packageName)
                    } else {
                        expandedPackages.add(group.packageName)
                    }
                    renderSelection(search.text.toString())
                }
            }
            val appIcon = try {
                packageManager.getApplicationIcon(group.packageName)
            } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
                packageManager.defaultActivityIcon
            }
            header.addView(ImageView(this).apply {
                setImageDrawable(appIcon)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            header.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@WidgetPickerActivity).apply {
                    text = group.label
                    textSize = 15f
                    setTextColor(this@WidgetPickerActivity.foreground)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@WidgetPickerActivity).apply {
                    text = resources.getQuantityString(
                        R.plurals.widget_provider_count,
                        group.providers.size,
                        group.providers.size
                    )
                    textSize = 12f
                    setTextColor(secondary)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(this).apply {
                text = if (expanded) "⌃" else "⌄"
                textSize = 20f
                setTextColor(secondary)
                gravity = Gravity.CENTER
                contentDescription = getString(
                    if (expanded) R.string.collapse_widget_group else R.string.expand_widget_group
                )
            }, LinearLayout.LayoutParams(dp(36), dp(40)))
            card.addView(header)
            if (expanded) {
                val visibleProviders = if (query.isBlank() || group.label.contains(query, ignoreCase = true)) {
                    group.providers
                } else {
                    matchingProviders
                }
                val limit = visibleProviderCounts.getOrPut(group.packageName) { PROVIDER_PAGE_SIZE }
                visibleProviders.take(limit).forEach { provider ->
                    card.addView(widgetProviderRow(provider))
                }
                if (visibleProviders.size > limit) {
                    card.addView(TextView(this).apply {
                        text = getString(R.string.show_more_widgets)
                        textSize = 14f
                        setTextColor(GlassUi.ACCENT)
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            visibleProviderCounts[group.packageName] = limit + PROVIDER_PAGE_SIZE
                            renderSelection(search.text.toString())
                        }
                    })
                }
            }
            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun widgetProviderRow(provider: AppWidgetProviderInfo): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { confirmWidget(provider) }
            val preview = ImageView(this@WidgetPickerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GlassUi.fieldBackground(this@WidgetPickerActivity, 12)
                tag = provider
            }
            addView(preview)
            addView(LinearLayout(this@WidgetPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@WidgetPickerActivity).apply {
                    text = providerLabel(provider)
                    textSize = 14f
                    setTextColor(this@WidgetPickerActivity.foreground)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@WidgetPickerActivity).apply {
                    text = getString(R.string.widget_dimensions, provider.minWidth, provider.minHeight)
                    textSize = 12f
                    setTextColor(secondary)
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            loadPreview(provider, preview)
        }

    private fun loadPreview(provider: AppWidgetProviderInfo, image: ImageView) {
        previewExecutor.execute {
            val drawable = try {
                provider.loadPreviewImage(this, resources.displayMetrics.densityDpi)
                    ?: provider.loadIcon(this, resources.displayMetrics.densityDpi)
                    ?: packageManager.getApplicationIcon(provider.provider.packageName)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to load preview for ${provider.provider}", error)
                try {
                    packageManager.getApplicationIcon(provider.provider.packageName)
                } catch (fallbackError: android.content.pm.PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Unable to load widget app icon for ${provider.provider}", fallbackError)
                    packageManager.defaultActivityIcon
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && image.isAttachedToWindow &&
                    image.tag == provider && drawable != null
                ) {
                    image.setImageDrawable(drawable)
                }
            }
        }
    }

    private fun appLabel(
        packageName: String,
        appProviders: List<AppWidgetProviderInfo>
    ): String = appLabels.getOrPut(packageName) {
        try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
            providerLabel(appProviders.first())
        }
    }

    private fun providerLabel(provider: AppWidgetProviderInfo): String =
        providerLabels.getOrPut(provider.provider) {
            provider.loadLabel(packageManager).toString()
        }

    /**
     * Short glass confirmation: the widget name, a "Không hiện lần sau"
     * switch and two round buttons — ✕ cancels, ✓ creates and allows. Once the
     * switch is used the question is skipped and Advanced settings can bring
     * it back.
     */
    private fun confirmWidget(provider: AppWidgetProviderInfo) {
        if (!AppPrefs.widgetConfirmEnabled(this)) {
            bindWidget(provider.provider)
            return
        }
        val sheet = GlassDialogs.sheet(
            this,
            getString(R.string.create_widget_title),
            providerLabel(provider),
            cancelLabel = null
        )
        var hideNextTime = false
        sheet.content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
            addView(TextView(this@WidgetPickerActivity).apply {
                text = getString(R.string.create_widget_skip)
                textSize = 13f
                setTextColor(secondary)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(GlassUi.switch(this@WidgetPickerActivity, false) { hideNextTime = it })
        })
        sheet.content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(
                dialogIconButton("x", prominent = false) { sheet.dismiss() },
                LinearLayout.LayoutParams(0, dp(52), 1f)
            )
            addView(
                dialogIconButton("check", prominent = true) {
                    if (hideNextTime) {
                        AppPrefs.setWidgetConfirmEnabled(this@WidgetPickerActivity, false)
                    }
                    sheet.dismiss()
                    bindWidget(provider.provider)
                },
                LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) }
            )
        })
        sheet.show()
    }

    private fun dialogIconButton(
        glyph: String,
        prominent: Boolean,
        onClick: () -> Unit
    ): FrameLayout = FrameLayout(this).apply {
        background = if (prominent) {
            GradientDrawable().apply {
                setColor(GlassUi.ACCENT)
                cornerRadius = dp(23).toFloat()
            }
        } else {
            GlassUi.glassSurface(this@WidgetPickerActivity, 23)
        }
        isClickable = true
        isFocusable = true
        contentDescription = if (prominent) {
            getString(R.string.create_widget_action)
        } else {
            getString(android.R.string.cancel)
        }
        addView(
            LucideGlyphView(
                this@WidgetPickerActivity,
                glyph,
                if (prominent) Color.rgb(6, 18, 31) else GlassUi.TEXT_SECONDARY
            ),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        )
        setOnClickListener { onClick() }
    }

    private fun renderAppActivities(query: String) {
        if (appActivities.isEmpty()) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            appActivities = packageManager.queryIntentActivities(launcher, 0)
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        }
        val matches = appActivities.filter {
            it.loadLabel(packageManager).toString().contains(query, ignoreCase = true)
        }
        if (matches.isEmpty()) {
            list.addView(message(getString(R.string.no_apps_match)))
            return
        }
        matches.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val component = ComponentName(activityInfo.packageName, activityInfo.name)
            list.addView(selectableRow(
                resolveInfo.loadLabel(packageManager).toString(),
                IconPacks.resolve(
                    this, PanelItemType.APP, component.flattenToString(),
                    resolveInfo.loadIcon(packageManager), dp(36)
                ) ?: resolveInfo.loadIcon(packageManager)
            ) {
                val label = resolveInfo.loadLabel(packageManager).toString()
                if (actionPickerTarget != null) {
                    if (!saveSelectedAction(
                            ActionDispatcher.OPEN_SELECTED_APP,
                            component.flattenToString(),
                            label
                        )
                    ) return@selectableRow
                } else if (expansionSlot >= 0) {
                    AppPrefs.setExpansionShortcut(
                        this,
                        expansionSlot,
                        ExpansionShortcut(PanelItemType.APP, component.flattenToString(), label)
                    )
                } else {
                    AppPrefs.addPanelItem(
                        this,
                        landscapeLayout,
                        panelId,
                        PanelItem(PanelItemType.APP, component.flattenToString(), label)
                            .copy(position = targetPosition),
                        allowOverlap = AppPrefs.panelFreeMode(this)
                    )
                }
                finishPicking()
            })
        }
    }

    private fun renderShortcutActivities(query: String) {
        // Quick actions declared by the installed apps (Chrome's new tab,
        // NCT's charts/topics/search…) sit above the legacy creator list.
        val appShortcutTitle = getString(R.string.app_shortcuts_title)
        val appShortcutHint = getString(R.string.app_shortcuts_hint)
        val showAppShortcuts = query.isBlank() ||
            appShortcutTitle.contains(query, ignoreCase = true) ||
            appShortcutHint.contains(query, ignoreCase = true)
        if (showAppShortcuts) {
            list.addView(appShortcutRow(appShortcutTitle, appShortcutHint) {
                loadAppShortcuts()
            })
        }
        val batteryShortcutLabel = getString(R.string.battery_optimization_shortcut)
        val showBatteryShortcut = batteryShortcutLabel.contains(query, ignoreCase = true)
        if (showBatteryShortcut) {
            list.addView(selectableRow(
                batteryShortcutLabel,
                getDrawable(R.drawable.ic_battery_optimization)
                    ?: packageManager.getDefaultActivityIcon()
            ) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                if (saveSelectedShortcut(intent, batteryShortcutLabel)) finishPicking()
            })
        }
        if (shortcutActivities.isEmpty()) {
            shortcutActivities = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_CREATE_SHORTCUT),
                0
            ).sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        }
        val matches = shortcutActivities.filter {
            it.loadLabel(packageManager).toString().contains(query, ignoreCase = true)
        }
        if (matches.isEmpty() && !showBatteryShortcut && !showAppShortcuts) {
            list.addView(message(getString(R.string.no_shortcuts_available)))
            return
        }
        matches.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            list.addView(selectableRow(
                resolveInfo.loadLabel(packageManager).toString(),
                resolveInfo.loadIcon(packageManager)
            ) {
                val createShortcut = Intent(Intent.ACTION_CREATE_SHORTCUT)
                    .setComponent(ComponentName(activityInfo.packageName, activityInfo.name))
                startActivityForResult(createShortcut, REQUEST_SHORTCUT)
            })
        }
    }

    private fun appShortcutRow(title: String, caption: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground()
            isClickable = true
            isFocusable = true
            addView(
                LucideGlyphView(this@WidgetPickerActivity, "grid-2x2", GlassUi.ACCENT),
                LinearLayout.LayoutParams(dp(42), dp(42))
            )
            addView(LinearLayout(this@WidgetPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@WidgetPickerActivity).apply {
                    text = title
                    textSize = 16f
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@WidgetPickerActivity).apply {
                    text = caption
                    textSize = 12f
                    setTextColor(secondary)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onClick() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

    /**
     * Lists the static shortcuts every installed app declares in its
     * shortcuts XML (new tab / incognito, charts / topics / search…). Reading
     * that resource works without the launcher or assistant role.
     */
    private fun loadAppShortcuts() {
        appShortcuts?.let { shortcuts ->
            if (shortcuts.isEmpty()) {
                Toast.makeText(this, R.string.no_shortcuts_available, Toast.LENGTH_SHORT).show()
            } else {
                openAppShortcutChooser(shortcuts)
            }
            return
        }
        if (shortcutsLoading) return
        shortcutsLoading = true
        Toast.makeText(this, "Đang tải lối tắt…", Toast.LENGTH_SHORT).show()
        previewExecutor.execute {
            val shortcuts = collectAppShortcuts()
            runOnUiThread {
                shortcutsLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                appShortcuts = shortcuts
                if (shortcuts.isEmpty()) {
                    Toast.makeText(this, R.string.no_shortcuts_available, Toast.LENGTH_SHORT).show()
                } else {
                    openAppShortcutChooser(shortcuts)
                }
            }
        }
    }

    private fun openAppShortcutChooser(shortcuts: List<AppShortcut>) {
        GlassDialogs.show(
            this,
            getString(R.string.app_shortcuts_title),
            shortcuts.mapIndexed { index, shortcut ->
                GlassDialogs.Item(
                    id = index.toString(),
                    label = shortcut.label,
                    caption = shortcut.appLabel,
                    iconPackage = shortcut.packageName
                )
            },
            getString(R.string.app_shortcuts_hint)
        ) { picked ->
            val shortcut = picked.id.toIntOrNull()?.let { shortcuts.getOrNull(it) } ?: return@show
            if (saveSelectedShortcut(shortcut.intent, shortcut.label)) finishPicking()
        }
    }

    private fun collectAppShortcuts(): List<AppShortcut> {
        val shortcuts = mutableListOf<AppShortcut>()
        val seen = mutableSetOf<String>()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
        packages.forEach packageLoop@{ packageName ->
            if (packageName == this.packageName) return@packageLoop
            val info = try {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
                )
            } catch (error: PackageManager.NameNotFoundException) {
                null
            } ?: return@packageLoop
            val appInfo = info.applicationInfo ?: return@packageLoop
            val appLabel = try {
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (error: Exception) {
                packageName
            }
            val appContext = try {
                createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
            } catch (error: Exception) {
                null
            } ?: return@packageLoop
            info.activities?.forEach activityLoop@{ activity ->
                val resourceId = activity.metaData?.getInt(SHORTCUTS_META_DATA) ?: 0
                if (resourceId == 0) return@activityLoop
                val parser = try {
                    appContext.resources.getXml(resourceId)
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to read shortcuts of $packageName", error)
                    null
                } ?: return@activityLoop
                try {
                    readShortcuts(parser, appContext.resources, packageName, appLabel)
                        .forEach { shortcut ->
                            if (seen.add(shortcut.intent.toUri(0))) shortcuts += shortcut
                        }
                } catch (error: Exception) {
                    Log.w(TAG, "Malformed shortcuts in $packageName", error)
                } finally {
                    parser.close()
                }
            }
        }
        return shortcuts
    }

    private fun readShortcuts(
        parser: XmlResourceParser,
        resources: Resources,
        packageName: String,
        appLabel: String
    ): List<AppShortcut> {
        val result = mutableListOf<AppShortcut>()
        var enabled = true
        var label: String? = null
        var shortcutId: String? = null
        var intent: Intent? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "shortcut" -> {
                        enabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                        shortcutId = parser.getAttributeValue(ANDROID_NS, "shortcutId")
                        label = shortcutLabel(parser, resources, "shortcutShortLabel")
                            ?: shortcutLabel(parser, resources, "shortcutLongLabel")
                        intent = null
                    }
                    "intent" -> intent = shortcutIntent(parser, packageName)
                    "categories", "category" -> parser.getAttributeValue(ANDROID_NS, "name")
                        ?.let { intent?.addCategory(it) }
                }
                XmlPullParser.END_TAG -> if (parser.name == "shortcut") {
                    val launchIntent = intent
                    if (enabled && launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        result += AppShortcut(
                            launchIntent,
                            label ?: shortcutId?.takeIf { it.isNotBlank() } ?: appLabel,
                            appLabel,
                            packageName
                        )
                    }
                }
            }
        }
        return result
    }

    private fun shortcutLabel(
        parser: XmlResourceParser,
        resources: Resources,
        attribute: String
    ): String? {
        val resourceId = parser.getAttributeResourceValue(ANDROID_NS, attribute, 0)
        if (resourceId != 0) {
            return try {
                resources.getString(resourceId)
            } catch (error: Exception) {
                null
            }
        }
        return parser.getAttributeValue(ANDROID_NS, attribute)?.takeIf { it.isNotBlank() }
    }

    private fun shortcutIntent(parser: XmlResourceParser, defaultPackage: String): Intent {
        val intent = Intent()
        parser.getAttributeValue(ANDROID_NS, "action")?.let { intent.action = it }
        val targetPackage = parser.getAttributeValue(ANDROID_NS, "targetPackage")
        val targetClass = parser.getAttributeValue(ANDROID_NS, "targetClass")
        when {
            !targetClass.isNullOrEmpty() ->
                intent.setClassName(
                    targetPackage?.takeIf { it.isNotEmpty() } ?: defaultPackage,
                    targetClass
                )
            !targetPackage.isNullOrEmpty() -> intent.setPackage(targetPackage)
        }
        parser.getAttributeValue(ANDROID_NS, "data")?.let { intent.data = Uri.parse(it) }
        return intent
    }

    private fun saveShortcut(data: Intent?) {
        val shortcutIntent = data?.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
        if (shortcutIntent == null) {
            Toast.makeText(this, R.string.shortcut_creation_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val label = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
            ?: getString(R.string.shortcut_default_name)
        if (saveSelectedShortcut(shortcutIntent, label)) finishPicking()
    }

    private fun saveSelectedShortcut(shortcutIntent: Intent, label: String): Boolean {
        val value = shortcutIntent.toUri(0)
        if (actionPickerTarget != null) {
            return saveSelectedAction(ActionDispatcher.OPEN_SELECTED_SHORTCUT, value, label)
        } else if (expansionSlot >= 0) {
            AppPrefs.setExpansionShortcut(
                this,
                expansionSlot,
                ExpansionShortcut(PanelItemType.SHORTCUT, value, label)
            )
        } else {
            AppPrefs.addPanelItem(
                this,
                landscapeLayout,
                panelId,
                PanelItem(PanelItemType.SHORTCUT, value, label, targetPosition),
                allowOverlap = AppPrefs.panelFreeMode(this)
            )
        }
        return true
    }

    private fun saveSelectedAction(type: String, value: String, label: String): Boolean {
        val target = actionPickerTarget ?: return false
        val action = ActionDispatcher.encodeSelectedAction(type, value, label)
        if (target == ACTION_PICKER_ASSISTANT) {
            AppPrefs.setAssistantReplacementAction(this, action)
            return true
        }
        if (target == ACTION_PICKER_QUICK_TILE_PRIMARY) {
            AppPrefs.setQuickTilePrimaryAction(this, action)
            QuickSettingsTiles.updateListeningServices(this)
            return true
        }
        if (target == ACTION_PICKER_QUICK_TILE_SECONDARY) {
            AppPrefs.setQuickTileSecondaryAction(this, action)
            return true
        }
        val gesture = Gesture.values().firstOrNull { it.preferenceKey == target }
        if (gesture == null) {
            Log.e(TAG, "Unknown gesture action-picker target: $target")
            Toast.makeText(this, R.string.action_picker_failed, Toast.LENGTH_SHORT).show()
            return false
        }
        AppPrefs.setAction(this, gesture, action)
        return true
    }

    private fun bindWidget(provider: ComponentName) {
        pendingWidgetId = host.allocateAppWidgetId()
        val bound = try {
            appWidgetManager.bindAppWidgetIdIfAllowed(pendingWidgetId, provider)
        } catch (error: SecurityException) {
            discardPendingWidget()
            Toast.makeText(this, R.string.widget_bind_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (bound) {
            finishBinding()
        } else {
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            startActivityForResult(bindIntent, REQUEST_BIND)
        }
    }

    private fun finishBinding() {
        val info = appWidgetManager.getAppWidgetInfo(pendingWidgetId)
        if (info == null) {
            discardPendingWidget()
            Toast.makeText(this, R.string.widget_bind_failed, Toast.LENGTH_LONG).show()
            return
        }
        val configureActivity = info.configure
        if (configureActivity != null) {
            val configureIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(configureActivity)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
            startActivityForResult(configureIntent, REQUEST_CONFIGURE)
        } else {
            savePendingWidget()
        }
    }

    private fun savePendingWidget() {
        if (pendingWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val info = appWidgetManager.getAppWidgetInfo(pendingWidgetId)
        val page = AppPrefs.panelPages(this, landscapeLayout).firstOrNull { it.id == panelId }
        if (info == null || page == null) {
            addPendingWidget(info, 1, 1)
            return
        }
        // Auto size stays within 2..6 cells; manual resize in the panel editor
        // can still go beyond that.
        val autoX = kotlin.math.ceil(info.minWidth.toDouble() / page.cellWidthDp).toInt()
            .coerceIn(2, 6)
        val autoY = kotlin.math.ceil(info.minHeight.toDouble() / page.rowHeightDp).toInt()
            .coerceIn(2, 6)
        val column = if (targetPosition >= 0) targetPosition % page.columns else 0
        val spanX = autoX.coerceAtMost((page.columns - column).coerceAtLeast(1))
        if (spanX < 2) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.widget_size_warning_title)
                .setMessage(R.string.widget_size_warning_message)
                .setPositiveButton(R.string.widget_size_warning_continue) { _, _ ->
                    addPendingWidget(info, spanX, autoY)
                }
                .setNegativeButton(R.string.widget_size_warning_cancel) { _, _ ->
                    discardPendingWidget()
                    finish()
                }
                .setCancelable(false)
                .show()
            return
        }
        addPendingWidget(info, spanX, autoY)
    }

    private fun addPendingWidget(info: android.appwidget.AppWidgetProviderInfo?, spanX: Int, spanY: Int) {
        AppPrefs.addPanelItem(
            this,
            landscapeLayout,
            panelId,
            PanelItem(
                PanelItemType.WIDGET,
                pendingWidgetId.toString(),
                info?.loadLabel(packageManager)?.toString().orEmpty(),
                position = targetPosition,
                spanX = spanX.toFloat(),
                spanY = spanY.toFloat()
            ),
            allowOverlap = AppPrefs.panelFreeMode(this)
        )
        finishPicking()
    }

    private fun finishPicking() {
        setResult(RESULT_OK)
        finish()
    }

    private fun selectableRow(title: String, icon: android.graphics.drawable.Drawable, action: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(ImageView(this@WidgetPickerActivity).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            })
            addView(TextView(this@WidgetPickerActivity).apply {
                text = title
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(12), 0, 0, 0)
            })
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

    private fun categoryButton(type: String, title: Int) =
        Button(this).also { button ->
            button.text = getString(title)
            button.isAllCaps = false
            button.textSize = 13f
            button.setOnClickListener { renderType(type) }
            button.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(2), 0, dp(2), dp(12))
            }
            categoryButtons.add(type to button)
            val selected = type == currentType
            button.setTextColor(if (selected) GlassUi.ACCENT else secondary)
            button.background = GlassUi.tabSurface(this, selected)
        }

    private fun discardPendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            host.deleteAppWidgetId(pendingWidgetId)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
    }

    private fun message(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(GlassUi.TEXT_SECONDARY)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(24), dp(16), dp(24))
    }

    private fun roundedBackground(color: Int = cardColor, radius: Int = dp(14)) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), Color.argb(38, 230, 242, 255))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChanged(s?.toString().orEmpty())
        }

        override fun afterTextChanged(s: Editable?) = Unit
    }

    private data class WidgetGroup(
        val packageName: String,
        val label: String,
        val providers: List<AppWidgetProviderInfo>
    )

    private data class AppShortcut(
        val intent: Intent,
        val label: String,
        val appLabel: String,
        val packageName: String
    )

    companion object {
        const val EXTRA_LANDSCAPE_LAYOUT = "com.manh.panelsassistive.extra.LANDSCAPE_LAYOUT"
        const val EXTRA_PANEL_ID = "com.manh.panelsassistive.extra.PANEL_ID"
        const val EXTRA_POSITION = "com.manh.panelsassistive.extra.POSITION"
        const val EXTRA_EXPANSION_SLOT = "com.manh.panelsassistive.extra.EXPANSION_SLOT"
        const val EXTRA_ACTION_PICKER_TARGET = "com.manh.panelsassistive.extra.ACTION_PICKER_TARGET"
        const val ACTION_PICKER_ASSISTANT = "assistant"
        const val ACTION_PICKER_QUICK_TILE_PRIMARY = "quick_tile_primary"
        const val ACTION_PICKER_QUICK_TILE_SECONDARY = "quick_tile_secondary"
        const val EXTRA_ITEM_TYPE = "com.manh.panelsassistive.extra.PANEL_ITEM_TYPE"
        private const val REQUEST_BIND = 1
        private const val REQUEST_CONFIGURE = 2
        private const val REQUEST_SHORTCUT = 3
        private const val STATE_PENDING_WIDGET_ID = "pending_widget_id"
        private const val TAG = "WidgetPicker"
        private const val PROVIDER_PAGE_SIZE = 40
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val SHORTCUTS_META_DATA = "android.app.shortcuts"
    }
}
