package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WidgetPickerActivity : Activity() {
    private val foreground = Color.rgb(244, 246, 250)
    private val secondary = Color.rgb(166, 173, 185)
    private val cardColor = Color.argb(38, 210, 230, 250)
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
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 24, 43), Color.rgb(14, 33, 54), Color.rgb(7, 18, 33))
            )
        }
        root.addView(TextView(this).apply {
            text = getString(when (currentType) {
                PanelItemType.ACTION -> R.string.choose_phone_shortcut
                PanelItemType.APP -> R.string.choose_app
                PanelItemType.SHORTCUT -> R.string.choose_shortcut
                else -> R.string.choose_widget
            })
            textSize = 25f
            setTextColor(this@WidgetPickerActivity.foreground)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
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
            background = roundedBackground(Color.argb(46, 220, 236, 250), dp(18))
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
            button.setTextColor(if (selected) foreground else secondary)
            button.background = roundedBackground(
                if (selected) Color.argb(150, 44, 104, 161) else Color.argb(36, 210, 230, 250),
                dp(18)
            )
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
        val actions = ActionDispatcher.quickActions(this).filter {
            it.label.contains(query, ignoreCase = true)
        }
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
                    ActionGlyphView(this@WidgetPickerActivity, action.id, Color.rgb(125, 188, 255)),
                    LinearLayout.LayoutParams(dp(42), dp(42))
                )
                addView(TextView(this@WidgetPickerActivity).apply {
                    text = action.label
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), 0, 0, 0)
                })
                setOnClickListener {
                    val slot = expansionSlot
                    if (slot >= 0) {
                        AppPrefs.setExpansionShortcut(
                            this@WidgetPickerActivity,
                            slot,
                            ExpansionShortcut(PanelItemType.ACTION, action.id, action.label)
                        )
                    } else {
                        AppPrefs.addPanelItem(
                            this@WidgetPickerActivity,
                            landscapeLayout,
                            panelId,
                            PanelItem(PanelItemType.ACTION, action.id, action.label, targetPosition)
                        )
                    }
                    finishPicking()
                }
            }
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            list.addView(row)
        }
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
                        setTextColor(Color.rgb(125, 188, 255))
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
                background = roundedBackground(Color.argb(52, 220, 236, 250), dp(12))
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

    private fun confirmWidget(provider: AppWidgetProviderInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.create_widget_title)
            .setMessage(getString(R.string.create_widget_permission_message, providerLabel(provider)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.create_widget_action) { _, _ ->
                bindWidget(provider.provider)
            }
            .show()
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
            list.addView(selectableRow(
                resolveInfo.loadLabel(packageManager).toString(),
                resolveInfo.loadIcon(packageManager)
            ) {
                val component = ComponentName(activityInfo.packageName, activityInfo.name)
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
                            .copy(position = targetPosition)
                    )
                }
                finishPicking()
            })
        }
    }

    private fun renderShortcutActivities(query: String) {
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
        if (matches.isEmpty() && !showBatteryShortcut) {
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
                PanelItem(PanelItemType.SHORTCUT, value, label, targetPosition)
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
        val spanX = if (info != null && page != null) {
            kotlin.math.ceil(info.minWidth.toDouble() / page.cellWidthDp).toInt()
                .coerceIn(1, page.columns)
        } else 1
        val spanY = if (info != null && page != null) {
            kotlin.math.ceil(info.minHeight.toDouble() / page.rowHeightDp).toInt()
                .coerceIn(1, 10)
        } else 1
        AppPrefs.addPanelItem(
            this,
            landscapeLayout,
            panelId,
            PanelItem(
                PanelItemType.WIDGET,
                pendingWidgetId.toString(),
                info?.loadLabel(packageManager)?.toString().orEmpty(),
                position = targetPosition,
                spanX = spanX,
                spanY = spanY
            )
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
            button.setTextColor(if (selected) foreground else secondary)
            button.background = roundedBackground(
                if (selected) Color.argb(150, 44, 104, 161) else cardColor,
                dp(18)
            )
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
        setTextColor(Color.rgb(167, 184, 203))
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

    companion object {
        const val EXTRA_LANDSCAPE_LAYOUT = "com.manh.panelsassistive.extra.LANDSCAPE_LAYOUT"
        const val EXTRA_PANEL_ID = "com.manh.panelsassistive.extra.PANEL_ID"
        const val EXTRA_POSITION = "com.manh.panelsassistive.extra.POSITION"
        const val EXTRA_EXPANSION_SLOT = "com.manh.panelsassistive.extra.EXPANSION_SLOT"
        const val EXTRA_ACTION_PICKER_TARGET = "com.manh.panelsassistive.extra.ACTION_PICKER_TARGET"
        const val ACTION_PICKER_ASSISTANT = "assistant"
        const val EXTRA_ITEM_TYPE = "com.manh.panelsassistive.extra.PANEL_ITEM_TYPE"
        private const val REQUEST_BIND = 1
        private const val REQUEST_CONFIGURE = 2
        private const val REQUEST_SHORTCUT = 3
        private const val STATE_PENDING_WIDGET_ID = "pending_widget_id"
        private const val TAG = "WidgetPicker"
        private const val PROVIDER_PAGE_SIZE = 40
    }
}
