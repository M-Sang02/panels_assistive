package com.manh.panelsassistive

import android.Manifest
import android.app.Dialog
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.ImageDecoder
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.function.Consumer

class FloatBallService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var widgetHost: AppWidgetHost
    private val handler = Handler(Looper.getMainLooper())
    private val idleDimRunnable = Runnable {
        val view = floatBall ?: return@Runnable
        if (!AppPrefs.dimWhenIdle(this)) return@Runnable
        view.animate().cancel()
        view.animate()
            .alpha(AppPrefs.floatBallOpacity(this) / 100f * AppPrefs.idleOpacity(this) / 100f)
            .setDuration(250)
            .start()
        view.setAnimationFrozen(AppPrefs.freezeAnimationWhenIdle(this))
    }
    private var floatBall: FloatBallIconView? = null
    private var floatBallParams: WindowManager.LayoutParams? = null
    private var popup: View? = null
    private var popupDialog: Dialog? = null
    private var popupList: GridLayout? = null
    private var popupAddButton: View? = null
    private var popupTemporarilyHidden = false
    private var popupEditMode = false
    private var widgetHostListening = false
    private var crossWindowBlurListener: Consumer<Boolean>? = null
    private var brightnessPermissionPrompted = false
    private var longPressTriggered = false
    private var tapCount = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    // Coalesce rapid slider movements so the system volume is committed only
    // after the user has stopped dragging for 500 ms.
    private val pendingVolumeUpdates = mutableMapOf<Int, Runnable>()
    private var torchCameraId: String? = null
    private var torchOn = false
    private var torchCallbackRegistered = false
    private var overlaySurface = OverlaySurface.NONE

    private enum class OverlaySurface { NONE, WIDGETS, EXPANSION, BRIGHTNESS }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchCameraId) return
            torchOn = enabled
            AppPrefs.setFlashlightEnabled(this@FloatBallService, enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId != torchCameraId) return
            torchOn = false
            AppPrefs.setFlashlightEnabled(this@FloatBallService, false)
        }
    }

    private val configurationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    repositionFloatBall()
                    if (popup != null) {
                        startWidgetUpdates()
                        populateWidgetList()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    floatBall?.onScreenOff()
                    // Nothing in the widget panel is visible while the screen is off.
                    if (popupList != null) stopWidgetUpdates()
                }
                Intent.ACTION_SCREEN_ON -> {
                    floatBall?.onScreenOn()
                    if (popupList != null) {
                        startWidgetUpdates()
                        populateWidgetList()
                    }
                }
            }
        }
    }

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        handler.removeCallbacks(tapSequenceRunnable)
        tapCount = 0
        if (popupList != null && popupDialog?.isShowing == true) {
            popupEditMode = !popupEditMode
            populateWidgetList()
            Toast.makeText(
                this,
                if (popupEditMode) R.string.panel_edit_mode_enabled else R.string.panel_edit_mode_disabled,
                Toast.LENGTH_SHORT
            ).show()
            bringFloatBallToFront()
        } else {
            ActionDispatcher.dispatch(
                this,
                AppPrefs.action(this, Gesture.LONG_PRESS, ActionDispatcher.TOGGLE_FLOAT_BALL)
            )
        }
    }

    private val tapSequenceRunnable = Runnable {
        val taps = tapCount
        tapCount = 0
        if (popupDialog?.isShowing == true) {
            when (taps) {
                1 -> removePopup()
                2 -> switchToNextPanel()
                3 -> showPanelOrderDialog()
            }
            return@Runnable
        }
        val gesture = when (taps) {
            1 -> Gesture.SINGLE_TAP
            2 -> Gesture.DOUBLE_TAP
            3 -> Gesture.TRIPLE_TAP
            else -> null
        }
        gesture?.let { selected ->
            val defaultAction = when (selected) {
                Gesture.SINGLE_TAP -> ActionDispatcher.WIDGET_POPUP
                Gesture.DOUBLE_TAP -> ActionDispatcher.FLASHLIGHT
                Gesture.LONG_PRESS -> ActionDispatcher.TOGGLE_FLOAT_BALL
                Gesture.TRIPLE_TAP -> ActionDispatcher.EXPANSION_PANEL
                Gesture.SWIPE_UP -> ActionDispatcher.WIDGET_POPUP
            }
            ActionDispatcher.dispatch(this, AppPrefs.action(this, selected, defaultAction))
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        torchOn = AppPrefs.flashlightEnabled(this)
        getSystemService(CameraManager::class.java)?.let { cameraManager ->
            if (checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                torchCameraId = findTorchCameraId(cameraManager)
            }
            try {
                cameraManager.registerTorchCallback(torchCallback, handler)
                torchCallbackRegistered = true
            } catch (error: Exception) {
                Log.w(TAG, "Unable to observe flashlight state", error)
            }
        }
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        createNotificationChannel()
        startInForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(configurationReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(configurationReceiver, filter)
        }
    }

    /**
     * The widget host only listens while the widget panel is open, so widgets do
     * not keep receiving/applying updates in the background and drain the battery.
     */
    private fun startWidgetUpdates() {
        if (widgetHostListening) return
        try {
            widgetHost.startListening()
            widgetHostListening = true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start widget host", error)
            Toast.makeText(this, R.string.widget_host_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopWidgetUpdates() {
        if (!widgetHostListening) return
        try {
            widgetHost.stopListening()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to pause widget host", error)
        }
        widgetHostListening = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP &&
            intent?.action != ACTION_TOGGLE_FLASHLIGHT &&
            !Settings.canDrawOverlays(this)
        ) {
            AppPrefs.setFloatBallEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                AppPrefs.setFloatBallEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_POPUP -> {
                if (!AppPrefs.widgetPopupEnabled(this)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                intent.getStringExtra(EXTRA_PANEL_ID)?.let {
                    AppPrefs.setCurrentPanel(this, usesLandscapeLayout(), it)
                }
                if (AppPrefs.floatBallEnabled(this)) showFloatBall()
                showPopup()
                return START_STICKY
            }
            ACTION_HIDE_POPUP -> {
                removePopup()
                return START_STICKY
            }
            ACTION_SHOW_EXPANSION_PANEL -> {
                showExpansionPanel()
                return START_STICKY
            }
            ACTION_SHOW_BRIGHTNESS_SLIDER -> {
                showBrightnessSlider()
                return START_STICKY
            }
            ACTION_RECORDING_STARTED -> {
                removeFloatBall()
                return START_STICKY
            }
            ACTION_RECORDING_STOPPED -> {
                if (AppPrefs.floatBallEnabled(this)) {
                    showFloatBall()
                } else if (popup == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_TOGGLE_FLASHLIGHT -> {
                if (AppPrefs.floatBallEnabled(this)) showFloatBall()
                performFlashlightToggle()
                if (floatBall == null && popup == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_REFRESH_PANEL_LAYOUT -> {
                if (popupDialog?.isShowing == true) {
                    removePopup(stopIfEmpty = false)
                    showPopup()
                }
                return START_STICKY
            }
            ACTION_REFRESH_WIDGETS -> {
                if (popup != null && popupList == null) {
                    if (popupTemporarilyHidden) {
                        popupTemporarilyHidden = false
                        popupDialog?.show()
                    }
                    if (floatBall == null && AppPrefs.floatBallEnabled(this)) showFloatBall()
                    bringFloatBallToFront()
                    return START_STICKY
                }
                if (popup != null) {
                    startWidgetUpdates()
                    populateWidgetList()
                }
                if (popupTemporarilyHidden) {
                    popupTemporarilyHidden = false
                    popupDialog?.show()
                }
                if (floatBall == null && AppPrefs.floatBallEnabled(this)) showFloatBall()
                bringFloatBallToFront()
                return START_STICKY
            }
            ACTION_UPDATE_FLOAT_BALL -> {
                if (floatBall == null && AppPrefs.floatBallEnabled(this)) {
                    showFloatBall()
                } else {
                    repositionFloatBall()
                }
                return START_STICKY
            }
            ACTION_UPDATE_POPUP_APPEARANCE -> {
                popupDialog?.window?.let { window -> applyPopupBlur(window, overlaySurface) }
                if (popupDialog?.isShowing == true) {
                    when (overlaySurface) {
                        OverlaySurface.WIDGETS -> popupDialog?.window?.let { window ->
                            window.setBackgroundDrawable(popupPanelBackground())
                            // Re-apply so the background blur survives the drawable swap.
                            applyPopupBlur(window, OverlaySurface.WIDGETS)
                        }
                        OverlaySurface.EXPANSION -> showExpansionPanel()
                        OverlaySurface.BRIGHTNESS -> showBrightnessSlider()
                        OverlaySurface.NONE -> Unit
                    }
                }
                return START_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            AppPrefs.setFloatBallEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        AppPrefs.setFloatBallEnabled(this, true)
        showFloatBall()
        QuickSettingsTiles.updateListeningServices(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        if (torchCallbackRegistered) {
            torchCallbackRegistered = false
            try {
                getSystemService(CameraManager::class.java)
                    ?.unregisterTorchCallback(torchCallback)
            } catch (error: Exception) {
                Log.w(TAG, "Unable to stop observing flashlight state", error)
            }
        }
        handler.removeCallbacksAndMessages(null)
        crossWindowBlurListener?.let { listener ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                windowManager.removeCrossWindowBlurEnabledListener(listener)
            }
        }
        crossWindowBlurListener = null
        try {
            unregisterReceiver(configurationReceiver)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Configuration receiver was already unregistered", error)
        }
        pendingVolumeUpdates.values.forEach(handler::removeCallbacks)
        pendingVolumeUpdates.clear()
        removePopup()
        removeFloatBall()
        stopWidgetUpdates()
        QuickSettingsTiles.updateListeningServices(this)
        super.onDestroy()
    }

    private fun startInForeground() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.float_ball_notification_title))
            .setContentText(getString(R.string.float_ball_notification_text))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.float_ball_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun showFloatBall() {
        if (floatBall != null || BackgroundRecordingService.isActive) return
        val size = dp(AppPrefs.floatBallSize(this))
        val view = FloatBallIconView(this).apply {
            setIconSources(this@FloatBallService)
            setCornerPercent(AppPrefs.floatBallCorner(this@FloatBallService))
            alpha = AppPrefs.floatBallOpacity(this@FloatBallService) / 100f
            elevation = dp(8).toFloat()
            setOnTouchListener(floatBallTouchListener)
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val position = AppPrefs.position(this@FloatBallService) ?: Pair(1f, 0.5f)
            x = (position.first * maxX(size)).toInt()
            y = safeTop() + (position.second * maxY(size)).toInt()
            if (AppPrefs.floatBallEdgeHideEnabled(this@FloatBallService)) {
                applyEdgeHide(this, size)
            }
        }
        try {
            windowManager.addView(view, params)
            floatBall = view
            floatBallParams = params
            scheduleIdleDim()
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Unable to display floating shortcut", error)
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            stopSelf()
        } catch (error: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked before the floating shortcut appeared", error)
            AppPrefs.setFloatBallEnabled(this, false)
            stopSelf()
        }
    }

    private val floatBallTouchListener = View.OnTouchListener { _, event ->
        val view = floatBall ?: return@OnTouchListener false
        val params = floatBallParams ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.onUserInteraction()
                scheduleIdleDim()
                downRawX = event.rawX
                downRawY = event.rawY
                downWindowX = params.x
                downWindowY = params.y
                longPressTriggered = false
                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                true
            }
            MotionEvent.ACTION_MOVE -> {
                scheduleIdleDim()
                val distance = kotlin.math.hypot(
                    (event.rawX - downRawX).toDouble(),
                    (event.rawY - downRawY).toDouble()
                )
                if (distance > ViewConfiguration.get(this).scaledTouchSlop) {
                    handler.removeCallbacks(longPressRunnable)
                    params.x = (downWindowX + event.rawX - downRawX).toInt()
                    params.y = (downWindowY + event.rawY - downRawY).toInt()
                    clampPosition(params, view.width.takeIf { it > 0 } ?: dp(AppPrefs.floatBallSize(this)))
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (error: IllegalArgumentException) {
                        Log.w(TAG, "Floating shortcut disappeared while moving", error)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (longPressTriggered) {
                    scheduleIdleDim()
                    true
                } else {
                    val moved = kotlin.math.hypot(
                        (event.rawX - downRawX).toDouble(),
                        (event.rawY - downRawY).toDouble()
                    ) > ViewConfiguration.get(this).scaledTouchSlop
                    if (moved) {
                        if (AppPrefs.snapToEdge(this)) {
                            val sizePx = view.width.takeIf { it > 0 } ?: dp(AppPrefs.floatBallSize(this))
                            params.x = if (params.x + sizePx / 2 < resources.displayMetrics.widthPixels / 2) {
                                0
                            } else {
                                maxX(sizePx)
                            }
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (error: IllegalArgumentException) {
                                Log.w(TAG, "Floating shortcut disappeared after drag", error)
                            }
                        }
                        persistPosition(params, view.width.takeIf { it > 0 } ?: dp(AppPrefs.floatBallSize(this)))
                        if (AppPrefs.floatBallEdgeHideEnabled(this)) {
                            applyEdgeHide(params, view.width.takeIf { it > 0 } ?: dp(AppPrefs.floatBallSize(this)))
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (error: IllegalArgumentException) {
                                Log.w(TAG, "Unable to apply edge hide after drag", error)
                            }
                        }
                    } else {
                        handleTap()
                    }
                    scheduleIdleDim()
                    true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                scheduleIdleDim()
                true
            }
            else -> false
        }
    }

    private fun handleTap() {
        if (popupDialog?.isShowing == true) {
            tapCount++
            handler.removeCallbacks(tapSequenceRunnable)
            if (tapCount >= 3) {
                val taps = tapCount
                tapCount = 0
                if (taps == 3) showPanelOrderDialog()
            } else {
                handler.postDelayed(
                    tapSequenceRunnable,
                    ViewConfiguration.getDoubleTapTimeout().toLong()
                )
            }
            return
        }
        if (popup != null) {
            removePopup()
            return
        }
        tapCount++
        handler.removeCallbacks(tapSequenceRunnable)
        if (tapCount >= 3) {
            tapCount = 0
            ActionDispatcher.dispatch(
                this,
                AppPrefs.action(this, Gesture.TRIPLE_TAP, ActionDispatcher.WIDGET_POPUP)
            )
        } else {
            handler.postDelayed(tapSequenceRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
        }
    }

    private fun switchToNextPanel() {
        val landscape = usesLandscapeLayout()
        val pages = AppPrefs.panelPages(this, landscape)
        if (pages.size < 2) return
        val currentIndex = pages.indexOfFirst { it.id == AppPrefs.currentPanelId(this, landscape) }
        val nextPage = pages[(currentIndex.coerceAtLeast(0) + 1) % pages.size]
        AppPrefs.setCurrentPanel(this, landscape, nextPage.id)
        removePopup()
        showPopup()
    }

    private fun showPanelOrderDialog() {
        val landscape = usesLandscapeLayout()
        if (popupDialog?.isShowing != true || AppPrefs.panelPages(this, landscape).size < 2) return

        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = frostedPanelBackground(24, Color.rgb(11, 24, 40), 97)
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var refreshPopupAfterDismiss = false
        val removedWidgetIds = mutableSetOf<Int>()
        lateinit var renderRows: () -> Unit

        fun confirmDeletePanel(panelId: String) {
            val page = AppPrefs.panelPages(this, landscape).firstOrNull { it.id == panelId } ?: return
            val confirmation = AlertDialog.Builder(this)
                .setTitle(R.string.delete_panel)
                .setMessage(getString(R.string.panel_order_delete_confirmation, page.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_panel) { _, _ ->
                    val latestPages = AppPrefs.panelPages(this, landscape)
                    val removedPage = latestPages.firstOrNull { it.id == panelId } ?: return@setPositiveButton
                    removedPage.items.filter { it.type == PanelItemType.WIDGET }.forEach { item ->
                        item.value.toIntOrNull()?.let(removedWidgetIds::add)
                    }
                    AppPrefs.removePanel(this, landscape, panelId)
                    refreshPopupAfterDismiss = true
                    renderRows()
                    if (AppPrefs.panelPages(this, landscape).size < 2) dialog.dismiss()
                }
                .create()
            confirmation.window?.setType(overlayWindowType())
            confirmation.show()
        }

        renderRows = {
            rows.removeAllViews()
            val pages = AppPrefs.panelPages(this, landscape)
            val selectedId = AppPrefs.currentPanelId(this, landscape)
            pages.forEachIndexed { index, page ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(6), dp(6), dp(6))
                    background = frostedPanelBackground(
                        16,
                        if (page.id == selectedId) Color.rgb(42, 87, 128) else Color.rgb(29, 42, 58),
                        82
                    )
                }
                row.addView(TextView(this).apply {
                    text = page.name
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                fun orderButton(symbol: String, description: Int, enabled: Boolean, action: () -> Unit) =
                    TextView(this).apply {
                        text = symbol
                        textSize = 19f
                        gravity = Gravity.CENTER
                        setTextColor(if (enabled) Color.WHITE else Color.GRAY)
                        contentDescription = getString(description)
                        isEnabled = enabled
                        background = roundedControlBackground()
                        setOnClickListener { if (enabled) action() }
                    }
                row.addView(orderButton("↑", R.string.move_panel_up, index > 0) {
                    AppPrefs.movePanel(this, landscape, index, index - 1)
                    Toast.makeText(this, R.string.panel_order_changed, Toast.LENGTH_SHORT).show()
                    renderRows()
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(4)
                })
                row.addView(orderButton("↓", R.string.move_panel_down, index < pages.lastIndex) {
                    AppPrefs.movePanel(this, landscape, index, index + 1)
                    Toast.makeText(this, R.string.panel_order_changed, Toast.LENGTH_SHORT).show()
                    renderRows()
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(4)
                })
                val more = orderButton("⋮", R.string.panel_actions, true) {}
                more.setOnClickListener {
                    val menu = PopupMenu(this, more)
                    menu.menu.add(0, 1, 0, R.string.delete_panel)
                    menu.setOnMenuItemClickListener { menuItem ->
                        if (menuItem.itemId == 1) confirmDeletePanel(page.id)
                        true
                    }
                    menu.show()
                }
                row.addView(more, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(4)
                })
                rows.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
            }
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@FloatBallService).apply {
                text = getString(R.string.manage_panels)
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(TextView(this@FloatBallService).apply {
                text = "×"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = getString(R.string.close)
                background = roundedControlBackground()
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(rows)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(8) })
        renderRows()
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setType(overlayWindowType())
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.42f)
        }
        dialog.setOnDismissListener {
            if (refreshPopupAfterDismiss && popupDialog?.isShowing == true) {
                removePopup()
                val usedWidgetIds = AppPrefs.widgetIds(this, false) + AppPrefs.widgetIds(this, true)
                removedWidgetIds.filterNot { it.toString() in usedWidgetIds }
                    .forEach(widgetHost::deleteAppWidgetId)
                showPopup()
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            minOf(dp(390), resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(1)),
            minOf(dp(520), resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(dp(1))
        )
    }

    private fun repositionFloatBall() {
        val view = floatBall ?: return
        val params = floatBallParams ?: return
        val size = dp(AppPrefs.floatBallSize(this))
        val position = AppPrefs.position(this) ?: Pair(1f, 0.5f)
        params.width = size
        params.height = size
        view.alpha = AppPrefs.floatBallOpacity(this) / 100f
        view.setIconSources(this)
        view.setCornerPercent(AppPrefs.floatBallCorner(this))
        params.x = (position.first * maxX(size)).toInt()
        params.y = safeTop() + (position.second * maxY(size)).toInt()
        if (AppPrefs.floatBallEdgeHideEnabled(this)) {
            applyEdgeHide(params, size)
        }
        try {
            windowManager.updateViewLayout(view, params)
            scheduleIdleDim()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Unable to reposition floating shortcut", error)
        }
    }

    private fun showPopup() {
        if (popup != null) {
            removePopup()
            return
        }
        popupEditMode = false
        startWidgetUpdates()
        val landscape = usesLandscapeLayout()
        val pages = AppPrefs.panelPages(this, landscape)
        val page = pages.firstOrNull { it.id == AppPrefs.currentPanelId(this, landscape) } ?: pages.first()
        val popupBackground = popupPanelBackground()
        val root = FrameLayout(this).apply {
            elevation = dp(18).toFloat()
        }
        val columns = page.columns.coerceIn(2, 20)
        val rows = maxOf(
            page.rows.coerceIn(1, 30),
            page.items.maxOfOrNull {
                (it.position.coerceAtLeast(0) / columns) + it.spanY.coerceIn(1, 10)
            } ?: 1
        ).coerceIn(1, 30)
        val viewportWidthDp = PanelViewportMetrics.widthDp(page.areaColumns, page.cellWidthDp)
        val viewportHeightDp = PanelViewportMetrics.heightDp(page.areaRows, page.rowHeightDp)
        val surfaceWidth = dp(maxOf(
            viewportWidthDp,
            PanelViewportMetrics.widthDp(columns, page.cellWidthDp)
        ))
        val grid = GridLayout(this).apply {
            columnCount = columns
            rowCount = rows
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipChildren = false
            clipToPadding = false
        }
        popupList = grid
        val surface = FrameLayout(this).apply {
            addView(grid, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
        }
        // Content is pinned to the top so row 0 hugs the panel edge; extra space
        // stays below the grid instead of pushing it down, exactly like the preview.
        val vertical = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(surface, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val horizontal = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(vertical, FrameLayout.LayoutParams(surfaceWidth, dp(viewportHeightDp)))
        }
        root.addView(horizontal, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            val inset = dp(page.areaPaddingDp.coerceIn(0, 100))
            setMargins(inset, inset, inset, inset)
        })
        grid.setOnTouchListener(panelSwipeListener)
        root.setOnTouchListener(panelSwipeListener)
        showOverlayPanel(
            root,
            dp(PanelViewportMetrics.panelWidthDp(page)),
            dp(PanelViewportMetrics.panelHeightDp(page)),
            windowBackground = popupBackground,
            surface = OverlaySurface.WIDGETS
        )
        // Widget views are inflated only after the panel window is up, during the
        // open fade, so RemoteViews loading does not stall the first frame.
        root.postDelayed({
            populateWidgetList(root)
            horizontal.scrollTo(
                ((vertical.width - horizontal.width) / 2).coerceAtLeast(0),
                0
            )
        }, 32L)
    }

    private fun populateWidgetList(root: View? = popup) {
        if (root !is FrameLayout || popupList == null) return
        val grid = popupList ?: return
        popupAddButton?.let { button ->
            (button.parent as? ViewGroup)?.removeView(button)
            popupAddButton = null
        }
        grid.removeAllViews()
        val pages = AppPrefs.panelPages(this, usesLandscapeLayout())
        val page = pages.firstOrNull { it.id == AppPrefs.currentPanelId(this, usesLandscapeLayout()) }
            ?: pages.first()
        grid.background = if (popupEditMode) {
            PanelEditDialogs.guideBackground(
                this,
                page.columns,
                page.cellWidthDp,
                page.rowHeightDp
            )
        } else {
            null
        }
        val columns = page.columns.coerceIn(2, 20)
        val maxRowExclusive = page.items.maxOfOrNull { item ->
            (item.position.coerceAtLeast(0) / columns) + item.spanY.coerceIn(1, 10)
        } ?: 1
        val rows = maxOf(page.rows.coerceIn(1, 30), maxRowExclusive).coerceIn(1, 30)
        grid.rowCount = rows
        val occupied = mutableSetOf<Int>()
        page.items.forEach { item ->
            val position = item.position.coerceAtLeast(0)
            val row = position / columns
            val column = position % columns
            val spanX = item.spanX.coerceIn(1, columns - column)
            val spanY = item.spanY.coerceIn(1, 10)
            for (dy in 0 until spanY) for (dx in 0 until spanX) {
                occupied.add((row + dy) * columns + column + dx)
            }
            val child = createPopupItem(item, page.cellWidthDp, page.rowHeightDp)
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, spanY),
                GridLayout.spec(column, spanX)
            ).apply {
                width = dp(page.cellWidthDp * spanX)
                height = dp(page.rowHeightDp * spanY)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(child, params)
        }
        val slotCount = maxOf(rows, page.rows.coerceIn(1, 30)) * columns
        for (position in 0 until slotCount) {
            if (position in occupied) continue
            val row = position / columns
            val column = position % columns
            var pressX = 0f
            var pressY = 0f
            val blank = FrameLayout(this).apply {
                isLongClickable = true
                setOnTouchListener { _, event ->
                    panelSwipeListener.onTouch(this, event)
                    if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_MOVE
                    ) {
                        pressX = event.x
                        pressY = event.y
                    }
                    false
                }
                setOnLongClickListener {
                    showInlineAddButton(root, this, position, pressX, pressY)
                    true
                }
            }
            grid.addView(blank, GridLayout.LayoutParams(
                GridLayout.spec(row),
                GridLayout.spec(column)
            ).apply {
                width = dp(page.cellWidthDp)
                height = dp(page.rowHeightDp)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun createPopupItem(item: PanelItem, cellWidthDp: Int, rowHeightDp: Int): View {
        val wrapper = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val content = if (item.type == PanelItemType.WIDGET) {
            val id = item.value.toIntOrNull() ?: return View(this)
            val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
            if (info == null) {
                AppPrefs.removeWidget(this, id)
                widgetHost.deleteAppWidgetId(id)
                return View(this)
            }
            try {
                widgetHost.createView(this, id, info).apply {
                    updateAppWidgetSize(
                        null,
                        cellWidthDp * item.spanX,
                        rowHeightDp * item.spanY,
                        cellWidthDp * item.spanX,
                        rowHeightDp * item.spanY
                    )
                    setOnTouchListener { _, event ->
                        if (popupEditMode) return@setOnTouchListener true
                        panelSwipeListener.onTouch(this, event)
                        false
                    }
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to create widget view for $id", error)
                View(this)
            }
        } else {
            createPanelShortcut(item, showLabel = AppPrefs.showPanelLabels(this))
        }
        wrapper.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        if (popupEditMode) {
            blockWidgetTouches(content)
            showPopupItemControls(wrapper, item)
        }
        return wrapper
    }

    private fun blockWidgetTouches(view: View) {
        view.setOnTouchListener { _, _ -> true }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                blockWidgetTouches(view.getChildAt(index))
            }
        }
    }



    /**
     * Compact icon-first control surface. The visual language follows the
     * volume-widget reference: one rounded pill, a real slider, and no +/- buttons.
     */
    private fun showExpansionPanel() {
        if (popup != null) removePopup(stopIfEmpty = false)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = sharedPanelBackground()
        }

        // Brightness is intentionally a separate floating control, not part of this panel.
        // XGesture-style five-column vertical level controls: brightness + four audio streams.
        val volumeRows = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6))
        }
        val audio = getSystemService(android.media.AudioManager::class.java)
        val brightnessValue = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(1, 255)
        } catch (_: Exception) { 128 }
        addVerticalLevelSlider(volumeRows, PopupIcon.BRIGHTNESS, "Sáng", 255, brightnessValue) { updateBrightness(it) }
        if (audio != null) {
            listOf(
                Triple(android.media.AudioManager.STREAM_MUSIC, PopupIcon.MEDIA, "Media"),
                Triple(android.media.AudioManager.STREAM_RING, PopupIcon.RING, "Chuông"),
                Triple(android.media.AudioManager.STREAM_NOTIFICATION, PopupIcon.NOTIFICATION, "Thông báo"),
                Triple(android.media.AudioManager.STREAM_ALARM, PopupIcon.ALARM, "Báo thức")
            ).forEach { (stream, icon, label) ->
                val maximum = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
                val current = audio.getStreamVolume(stream).coerceIn(0, maximum)
                addVerticalLevelSlider(volumeRows, icon, label, maximum, current) {
                    scheduleStreamVolume(audio, stream, it)
                }
            }
        }
        panel.addView(volumeRows)

        val shortcutArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        AppPrefs.expansionShortcuts(this).chunked(4).forEachIndexed { rowIndex, rowShortcuts ->
            val shortcutRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                if (rowIndex > 0) setPadding(0, dp(4), 0, 0)
            }
            rowShortcuts.forEachIndexed { columnIndex, shortcut ->
                val index = rowIndex * 4 + columnIndex
                val cell: View = if (shortcut == null) {
                    FrameLayout(this).apply {
                        background = sliderTileBackground()
                        contentDescription = getString(R.string.add_shortcut)
                        isClickable = true
                        isFocusable = true
                        addView(PopupIconView(this@FloatBallService, PopupIcon.ADD).apply {
                            setIconColor(Color.WHITE)
                        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                        setOnClickListener {
                            startActivity(Intent(this@FloatBallService, WidgetPickerActivity::class.java).apply {
                                putExtra(WidgetPickerActivity.EXTRA_EXPANSION_SLOT, index)
                                putExtra(WidgetPickerActivity.EXTRA_ITEM_TYPE, PanelItemType.ACTION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    }
                } else {
                    FrameLayout(this).apply {
                        background = sliderTileBackground()
                        val shortcutView = createPanelShortcut(
                            PanelItem(shortcut.type, shortcut.value, shortcut.label), showLabel = false
                        )
                        addView(shortcutView, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                        ))
                        contentDescription = shortcut.label
                        shortcutView.setOnLongClickListener {
                            AppPrefs.setExpansionShortcut(this@FloatBallService, index, null)
                            Toast.makeText(this@FloatBallService, R.string.panel_item_removed, Toast.LENGTH_SHORT).show()
                            showExpansionPanel()
                            true
                        }
                    }
                }
                shortcutRow.addView(cell, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                })
            }
            shortcutArea.addView(shortcutRow)
        }

        // The centered chevron controls only the shortcut area; volume rows stay visible.
        var shortcutsExpanded = true
        val shortcutToggle = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Thu gọn hoặc mở rộng lối tắt"
            addView(PopupIconView(this@FloatBallService, PopupIcon.CHEVRON_UP).apply {
                setIconColor(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener {
                shortcutsExpanded = !shortcutsExpanded
                shortcutArea.visibility = if (shortcutsExpanded) View.VISIBLE else View.GONE
                (getChildAt(0) as? PopupIconView)?.setIcon(shortcutsExpanded)
            }
        }
        panel.addView(shortcutToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(30)
        ).apply { topMargin = dp(3) })
        panel.addView(shortcutArea)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(panel, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        showOverlayPanel(
            scroll,
            dp(360),
            minOf(dp(470), resources.displayMetrics.heightPixels - dp(80)),
            surface = OverlaySurface.EXPANSION
        )
    }

    private fun showBrightnessSlider() {
        if (popup != null) removePopup(stopIfEmpty = false)
        val current = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(1, 255)
        } catch (_: Exception) { 128 }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Generous top/bottom insets keep the icon and the thumb clear of the
            // panel rim while making the track taller.
            setPadding(dp(10), dp(24), dp(10), dp(24))
            background = sharedPanelBackground()
            addView(PopupIconView(this@FloatBallService, PopupIcon.BRIGHTNESS).apply {
                setIconColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { bottomMargin = dp(16) })
            addView(VerticalBrightnessTrackView(this@FloatBallService, 255, current) { updateBrightness(it) },
                LinearLayout.LayoutParams(dp(44), dp(250)))
        }
        showOverlayPanel(panel, dp(92), dp(374), surface = OverlaySurface.BRIGHTNESS)
    }

    private inner class VerticalBrightnessTrackView(
        context: Context,
        private val maximum: Int,
        initialProgress: Int,
        private val onValueChanged: (Int) -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(95, 230, 240, 250) }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(132, 198, 255) }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var value = initialProgress.coerceIn(0, maximum)
        private var tracking = false
        init { isClickable = true; isFocusable = true; contentDescription = getString(R.string.brightness_slider) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val top = 10f * density
            val bottom = height - 10f * density
            val fraction = value.toFloat() / maximum
            val y = bottom - (bottom - top) * fraction
            val radius = 4f * density
            canvas.drawRoundRect(cx - radius, top, cx + radius, bottom, radius, radius, trackPaint)
            canvas.drawRoundRect(cx - radius, y, cx + radius, bottom, radius, radius, activePaint)
            canvas.drawCircle(cx, y, 9f * density, activePaint)
            canvas.drawCircle(cx, y, 6.5f * density, thumbPaint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { tracking = true; parent?.requestDisallowInterceptTouchEvent(true); updateValue(event.y); return true }
                MotionEvent.ACTION_MOVE -> { if (tracking) updateValue(event.y); return tracking }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (tracking) updateValue(event.y); tracking = false; parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true }
            }
            return tracking
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun updateValue(y: Float) {
            val top = 10f * density
            val bottom = (height - 10f * density).coerceAtLeast(top + 1f)
            val next = (((bottom - y) / (bottom - top)).coerceIn(0f, 1f) * maximum).toInt()
            if (next != value) { value = next; invalidate(); onValueChanged(value) }
        }
    }

    private fun addVerticalLevelSlider(
        parent: LinearLayout,
        icon: PopupIcon,
        label: String,
        maximum: Int,
        progress: Int,
        onChanged: (Int) -> Unit
    ) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
            addView(PopupIconView(this@FloatBallService, icon).apply {
                setIconColor(Color.WHITE)
                contentDescription = label
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { bottomMargin = dp(7) })
            addView(VerticalBrightnessTrackView(this@FloatBallService, maximum.coerceAtLeast(1), progress) {
                onChanged(it)
            }, LinearLayout.LayoutParams(dp(42), dp(142)))
            addView(TextView(this@FloatBallService).apply {
                text = label
                textSize = 10f
                setTextColor(Color.argb(220, 245, 248, 252))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)).apply {
                topMargin = dp(5)
            })
        }
        parent.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun sharedPanelBackground() = panelBackground(
        AppPrefs.sharedPanelColor(this),
        AppPrefs.sharedPanelOpacity(this),
        AppPrefs.sharedPanelCorner(this)
    )

    private fun popupPanelBackground() = panelBackground(
        AppPrefs.popupPanelColor(this),
        AppPrefs.popupPanelOpacity(this),
        AppPrefs.popupPanelCorner(this)
    )

    private fun panelBackground(color: Int, opacity: Int, corner: Int) = GradientDrawable().apply {
        val alpha = opacity.coerceIn(0, 100) * 255 / 100
        setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
        cornerRadius = dp(corner).toFloat()
        setStroke(dp(1), Color.argb(65, 225, 238, 250))
    }

    private fun addVolumeSliderRow(parent: LinearLayout, stream: Int, icon: PopupIcon) {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        val maximum = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val current = audio.getStreamVolume(stream).coerceIn(0, maximum)
        val description = when (stream) {
            android.media.AudioManager.STREAM_MUSIC -> getString(R.string.media_volume)
            android.media.AudioManager.STREAM_RING -> getString(R.string.ring_volume)
            android.media.AudioManager.STREAM_NOTIFICATION -> getString(R.string.notification_volume)
            else -> getString(R.string.alarm_volume)
        }
        parent.addView(createSliderPill(
            icon = icon,
            description = description,
            max = maximum,
            progress = current,
            compact = false
        ) { value -> scheduleStreamVolume(audio, stream, value) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            bottomMargin = dp(7)
        })
    }

    private fun createSliderPill(
        icon: PopupIcon,
        description: String,
        max: Int,
        progress: Int,
        compact: Boolean,
        onChanged: (Int) -> Unit
    ): View {
        // A custom-drawn track avoids the platform SeekBar's inconsistent
        // thumb/track insets and gives brightness + every volume row one design.
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = sliderPillBackground()
            contentDescription = description
            addView(FrameLayout(this@FloatBallService).apply {
                addView(PopupIconView(this@FloatBallService, icon).apply {
                    setIconColor(Color.WHITE)
                    contentDescription = description
                }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(26), dp(30)))
            addView(SliderTrackView(
                context = this@FloatBallService,
                maximum = max.coerceAtLeast(1),
                initialProgress = progress.coerceIn(0, max.coerceAtLeast(1)),
                onValueChanged = onChanged
            ), LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                leftMargin = dp(12)
            })
        }
    }

    /** Rounded, inset-safe slider shared by brightness and all audio streams. */
    private inner class SliderTrackView(
        context: Context,
        private val maximum: Int,
        initialProgress: Int,
        private val onValueChanged: (Int) -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 230, 240, 250)
            style = Paint.Style.FILL
        }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(132, 198, 255)
            style = Paint.Style.FILL
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(2.5f * density, 0f, 1f * density, Color.argb(110, 0, 0, 0))
        }
        private var value = initialProgress.coerceIn(0, maximum)
        private val inset = 9f * density
        private val trackHeight = 4.5f * density
        private val thumbRadius = 7f * density
        private var tracking = false

        init {
            isClickable = true
            isFocusable = true
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            contentDescription = "Thanh điều chỉnh"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = inset
            val right = (width.toFloat() - inset).coerceAtLeast(left + 1f)
            val centerY = height / 2f
            val fraction = value.toFloat() / maximum.toFloat()
            val thumbX = left + (right - left) * fraction
            val top = centerY - trackHeight / 2f
            val bottom = centerY + trackHeight / 2f
            canvas.drawRoundRect(left, top, right, bottom, trackHeight, trackHeight, trackPaint)
            activePaint.alpha = 255
            if (thumbX > left) {
                canvas.drawRoundRect(left, top, thumbX, bottom, trackHeight, trackHeight, activePaint)
            }
            activePaint.alpha = 75
            canvas.drawCircle(thumbX, centerY, thumbRadius + 2f * density, activePaint)
            thumbPaint.alpha = 255
            canvas.drawCircle(thumbX, centerY, thumbRadius, thumbPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateFromTouch(event.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking) updateFromTouch(event.x)
                    return tracking
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tracking) updateFromTouch(event.x)
                    tracking = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
            }
            return tracking
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun updateFromTouch(x: Float) {
            val usable = (width.toFloat() - inset * 2f).coerceAtLeast(1f)
            val fraction = ((x - inset) / usable).coerceIn(0f, 1f)
            val next = (fraction * maximum).toInt()
            if (next != value) {
                value = next
                invalidate()
                onValueChanged(value)
            }
        }
    }

    private fun sliderPillBackground() = GradientDrawable().apply {
        setColor(Color.argb(72, 225, 237, 250))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.argb(58, 225, 237, 250))
    }

    private fun sliderTileBackground() = GradientDrawable().apply {
        setColor(Color.argb(58, 220, 235, 250))
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), Color.argb(55, 220, 235, 250))
    }

    private fun updateBrightness(progress: Int) {
        if (!Settings.System.canWrite(this)) {
            if (!brightnessPermissionPrompted) {
                brightnessPermissionPrompted = true
                showOverlayDialog(AlertDialog.Builder(this)
                    .setMessage(R.string.brightness_permission_required)
                    .setPositiveButton(R.string.grant_brightness_permission) { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                .setData(Uri.parse("package:$packageName"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (error: Exception) {
                            Log.e(TAG, "Unable to open brightness permission settings", error)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null))
            }
            return
        }
        brightnessPermissionPrompted = false
        try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val saved = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                progress.coerceIn(1, 255)
            )
            if (!saved) {
                Toast.makeText(this, R.string.brightness_update_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to update screen brightness", error)
            Toast.makeText(this, R.string.brightness_update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performFlashlightToggle() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_REQUEST_CAMERA_PERMISSION, true)
                putExtra(MainActivity.EXTRA_RUN_TORCH_AFTER_PERMISSION, true)
            })
            return
        }
        val cameraManager = getSystemService(CameraManager::class.java)
        if (cameraManager == null) {
            Toast.makeText(this, R.string.flashlight_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cameraId = torchCameraId ?: findTorchCameraId(cameraManager)?.also {
                torchCameraId = it
            }
            if (cameraId == null) {
                Toast.makeText(this, R.string.flashlight_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            val enabled = !torchOn
            cameraManager.setTorchMode(cameraId, enabled)
            torchOn = enabled
            AppPrefs.setFlashlightEnabled(this, enabled)
        } catch (error: CameraAccessException) {
            Log.e(TAG, "Flashlight is unavailable", error)
            Toast.makeText(this, R.string.flashlight_unavailable, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to toggle the flashlight", error)
            Toast.makeText(this, R.string.flashlight_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun findTorchCameraId(cameraManager: CameraManager): String? {
        var fallback: String? = null
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = try {
                cameraManager.getCameraCharacteristics(cameraId)
            } catch (error: Exception) {
                Log.w(TAG, "Unable to read camera $cameraId", error)
                continue
            }
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
            if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                return cameraId
            }
            if (fallback == null) fallback = cameraId
        }
        return fallback
    }

    private fun scheduleStreamVolume(
        audio: android.media.AudioManager,
        stream: Int,
        requested: Int
    ) {
        pendingVolumeUpdates.remove(stream)?.let(handler::removeCallbacks)
        val update = Runnable {
            pendingVolumeUpdates.remove(stream)
            setStreamVolume(audio, stream, requested)
        }
        pendingVolumeUpdates[stream] = update
        handler.postDelayed(update, 500L)
    }

    private fun setStreamVolume(
        audio: android.media.AudioManager,
        stream: Int,
        requested: Int
    ) {
        try {
            audio.setStreamVolume(
                stream,
                requested.coerceIn(0, audio.getStreamMaxVolume(stream).coerceAtLeast(1)),
                android.media.AudioManager.FLAG_SHOW_UI
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to update volume stream $stream", error)
            Toast.makeText(this, R.string.volume_update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun maxX(size: Int): Int =
        (resources.displayMetrics.widthPixels - size).coerceAtLeast(0)

    private fun safeTop(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun maxY(size: Int): Int =
        (resources.displayMetrics.heightPixels - safeTop() - size).coerceAtLeast(0)

    private fun clampPosition(params: WindowManager.LayoutParams, size: Int) {
        params.x = params.x.coerceIn(0, maxX(size))
        params.y = (params.y - safeTop()).coerceIn(0, maxY(size)) + safeTop()
    }

    private fun persistPosition(params: WindowManager.LayoutParams, size: Int) {
        val normalizedX = if (maxX(size) == 0) 0f else params.x.toFloat() / maxX(size)
        val normalizedY = if (maxY(size) == 0) 0f else (params.y - safeTop()).toFloat() / maxY(size)
        AppPrefs.setPosition(this, normalizedX, normalizedY)
    }

    private fun applyEdgeHide(params: WindowManager.LayoutParams, size: Int) {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!AppPrefs.floatBallEdgeHideForOrientation(this, landscape)) return
        val value = AppPrefs.floatBallEdgeValue(this, landscape)
        if (value == 0) return
        val hideLeft = if (AppPrefs.floatBallAutoMirror(this)) {
            params.x + size / 2 < resources.displayMetrics.widthPixels / 2
        } else {
            value < 0
        }
        val hiddenAmount = (size * kotlin.math.abs(value) / 100f)
            .toInt()
            .coerceAtMost((size - dp(10)).coerceAtLeast(1))
        params.x = if (hideLeft) -hiddenAmount else maxX(size) + hiddenAmount
    }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun usesLandscapeLayout(): Boolean =
        AppPrefs.landscapeWidgetLayoutEnabled(this) &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun showOverlayDialog(builder: AlertDialog.Builder) {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val dialog = builder.create()
        dialog.window?.setType(overlayType)
        dialog.show()
    }

    private fun openWidgetPicker(type: String, position: Int) {
        val page = selectedPopupPage()
        startActivity(Intent(this, WidgetPickerActivity::class.java).apply {
            putExtra(WidgetPickerActivity.EXTRA_ITEM_TYPE, type)
            putExtra(WidgetPickerActivity.EXTRA_LANDSCAPE_LAYOUT, usesLandscapeLayout())
            putExtra(WidgetPickerActivity.EXTRA_PANEL_ID, page.id)
            putExtra(WidgetPickerActivity.EXTRA_POSITION, position)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun showPopupItemControls(itemView: FrameLayout, item: PanelItem) {
        itemView.findViewWithTag<View>("popup_remove")?.let { itemView.removeView(it) }
        itemView.findViewWithTag<View>("popup_resize")?.let { itemView.removeView(it) }
        itemView.findViewWithTag<View>("popup_move")?.let { itemView.removeView(it) }
        itemView.addView(popupItemControl(PopupIcon.CLOSE, R.string.remove_panel_item) {
            removePopupItem(item)
        }.apply { tag = "popup_remove" }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END))
        val handle = popupItemControl(PopupIcon.RESIZE, R.string.resize_panel_item) {
            showPopupItemSizeDialog(item)
        }.apply {
            tag = "popup_resize"
        }
        itemView.addView(handle, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
        val moveHandle = popupItemControl(PopupIcon.MOVE, R.string.move_panel_item) {
            PanelEditDialogs.showMove(this, item.label) { dx, dy ->
                movePopupItemBy(item, dx, dy)
            }
        }.apply {
            tag = "popup_move"
        }
        itemView.addView(
            moveHandle,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.START)
        )
        itemView.foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(2), Color.rgb(125, 188, 255))
        }
    }

    private fun popupItemControl(icon: PopupIcon, description: Int, action: () -> Unit): View =
        FrameLayout(this).apply {
            background = roundedBackground(Color.argb(210, 7, 18, 33), dp(10))
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = getString(description)
            addView(PopupIconView(this@FloatBallService, icon).apply {
                setIconColor(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            setOnClickListener { action() }
        }

    private fun removePopupItem(item: PanelItem) {
        val landscape = usesLandscapeLayout()
        val page = selectedPopupPage()
        AppPrefs.removePanelItem(this, landscape, page.id, item.id)
        populateWidgetList()
        val root = popup as? FrameLayout ?: return
        PanelEditDialogs.showUndoNotice(
            root,
            getString(R.string.panel_item_removed),
            onUndo = {
                AppPrefs.addPanelItem(this, landscape, page.id, item)
                populateWidgetList()
            },
            onExpire = {
                if (item.type == PanelItemType.WIDGET) {
                    val id = item.value.toIntOrNull()
                    val usedIds = AppPrefs.widgetIds(this, false) + AppPrefs.widgetIds(this, true)
                    if (id != null && id.toString() !in usedIds) widgetHost.deleteAppWidgetId(id)
                }
            }
        )
    }

    private fun showPopupItemSizeDialog(item: PanelItem) {
        val page = selectedPopupPage()
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
            AppPrefs.updatePanel(this, usesLandscapeLayout(), page.copy(items = items))
            populateWidgetList()
            true
        }
    }

    private fun movePopupItemBy(item: PanelItem, dx: Int, dy: Int) {
        val page = selectedPopupPage()
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
        val updated = page.items.map {
            if (it.id == current.id) it.copy(position = destination) else it
        }
        AppPrefs.updatePanel(this, usesLandscapeLayout(), page.copy(items = updated))
        populateWidgetList()
    }

    private fun selectedPopupPage(): WidgetPanelPage {
        val landscape = usesLandscapeLayout()
        val pages = AppPrefs.panelPages(this, landscape)
        return pages.firstOrNull { it.id == AppPrefs.currentPanelId(this, landscape) } ?: pages.first()
    }

    private fun showInlineAddButton(root: FrameLayout, cell: View, position: Int, touchX: Float, touchY: Float) {
        popupAddButton?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val rootLocation = IntArray(2)
        val cellLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        cell.getLocationOnScreen(cellLocation)
        val buttonSize = dp(40)
        val button = FrameLayout(this).apply {
            background = roundedBackground(Color.rgb(65, 155, 240), dp(20))
            elevation = dp(12).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.add_panel_item)
            addView(PopupIconView(this@FloatBallService, PopupIcon.ADD).apply {
                setIconColor(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener {
                popupAddButton = null
                root.removeView(this)
                showPopupAddOptions(position)
            }
        }
        popupAddButton = button
        root.addView(button, FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
            leftMargin = (cellLocation[0] - rootLocation[0] + touchX.toInt() - buttonSize / 2)
                .coerceIn(0, (root.width - buttonSize).coerceAtLeast(0))
            topMargin = (cellLocation[1] - rootLocation[1] + touchY.toInt() - buttonSize / 2)
                .coerceIn(0, (root.height - buttonSize).coerceAtLeast(0))
        })
    }

    private fun showPopupAddOptions(position: Int) {
        val types = arrayOf(PanelItemType.WIDGET, PanelItemType.APP, PanelItemType.SHORTCUT)
        val labels = arrayOf(
            getString(R.string.choose_widget),
            getString(R.string.choose_app),
            getString(R.string.choose_shortcut)
        )
        showOverlayDialog(android.app.AlertDialog.Builder(this)
            .setItems(labels) { _, selected -> openWidgetPicker(types[selected], position) }
        )
    }


    private fun createPanelShortcut(
        item: PanelItem,
        showLabel: Boolean = AppPrefs.showPanelLabels(this),
        touchObserver: ((View, MotionEvent) -> Boolean)? = null
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(24, 255, 255, 255))
                cornerRadius = dp(16).toFloat()
            }
        }
        val iconSize = dp(minOf(40, selectedCellWidth() - 12).coerceAtLeast(18))
        if (item.type == PanelItemType.ACTION) {
            cell.addView(ActionGlyphView(this, item.value, Color.rgb(125, 188, 255)), LinearLayout.LayoutParams(iconSize, iconSize))
        } else {
            cell.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                scaleType = ImageView.ScaleType.FIT_CENTER
                try {
                    val icon = when (item.type) {
                        PanelItemType.APP -> ComponentName.unflattenFromString(item.value)
                            ?.let { packageManager.getActivityIcon(it) }
                        PanelItemType.SHORTCUT -> Intent.parseUri(item.value, 0)
                            .resolveActivity(packageManager)
                            ?.let { packageManager.getActivityIcon(it) }
                        else -> null
                    }
                    setImageDrawable(icon ?: packageManager.getDefaultActivityIcon())
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to load panel item icon", error)
                    setImageResource(android.R.drawable.sym_def_app_icon)
                }
            })
        }
        if (showLabel) {
            cell.addView(TextView(this).apply {
                text = item.label
                textSize = 11f
                setTextColor(Color.rgb(225, 229, 235))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(4), dp(2), 0)
            })
        }
        cell.setOnClickListener {
            launchPanelItem(item)
            scheduleCloseAfterTap()
        }
        cell.setOnTouchListener { view, event ->
            if (touchObserver?.invoke(view, event) == true) return@setOnTouchListener true
            panelSwipeListener.onTouch(view, event)
            false
        }
        return cell
    }



    private fun launchPanelItem(item: PanelItem) {
        try {
            if (item.type == PanelItemType.ACTION) {
                ActionDispatcher.dispatch(this, item.value)
                return
            }
            val intent = when (item.type) {
                PanelItemType.APP -> Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName.unflattenFromString(item.value)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                PanelItemType.SHORTCUT -> Intent.parseUri(item.value, 0).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                else -> return
            }
            startActivity(intent)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to launch panel item", error)
            Toast.makeText(this, R.string.panel_item_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }



    private fun scheduleCloseAfterTap() {
        if (AppPrefs.closePanelAfterTap(this)) {
            handler.postDelayed({ removePopup() }, 180)
        }
    }



    private val panelSwipeListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                removePopup()
                return@OnTouchListener true
            }
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (kotlin.math.abs(dx) > dp(60) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    changePanel(if (dx < 0) 1 else -1)
                }
            }
        }
        false
    }



    private fun changePanel(direction: Int) {
        val landscape = usesLandscapeLayout()
        val pages = AppPrefs.panelPages(this, landscape)
        if (pages.size < 2) return
        val current = pages.indexOfFirst { it.id == AppPrefs.currentPanelId(this, landscape) }.coerceAtLeast(0)
        val next = (current + direction + pages.size) % pages.size
        AppPrefs.setCurrentPanel(this, landscape, pages[next].id)
        populateWidgetList()
        animatePanelChange(direction)
    }

    private fun animatePanelChange(direction: Int) {
        val target = popupList ?: return
        when (AppPrefs.tabTransition(this)) {
            "none" -> return
            "fade" -> {
                target.alpha = 0f
                target.animate().alpha(1f).setDuration(180L).start()
            }
            "slide" -> {
                target.translationX = dp(26) * direction.toFloat()
                target.alpha = 0.35f
                target.animate().translationX(0f).alpha(1f).setDuration(220L).start()
            }
            "flip" -> {
                target.cameraDistance = dp(8000).toFloat()
                target.rotationY = 65f * direction
                target.alpha = 0.4f
                target.animate().rotationY(0f).alpha(1f).setDuration(240L).start()
            }
        }
    }



    private fun popupIconButton(icon: PopupIcon, description: Int, action: () -> Unit) =
        FrameLayout(this).apply {
            background = roundedControlBackground()
            isClickable = true
            isFocusable = true
            contentDescription = getString(description)
            addView(PopupIconView(this@FloatBallService, icon).apply {
                setIconColor(Color.rgb(225, 232, 241))
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { action() }
        }



    private fun popupActionButton(label: String, action: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(16), 0)
            background = roundedBackground(Color.rgb(49, 87, 128), dp(14))
            isClickable = true
            isFocusable = true
            addView(
                PopupIconView(this@FloatBallService, PopupIcon.ADD).apply {
                    setIconColor(Color.WHITE)
                },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(8)
                }
            )
            addView(TextView(this@FloatBallService).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            setOnClickListener { action() }
        }



    private fun roundedControlBackground() = GradientDrawable().apply {
        setColor(Color.rgb(35, 39, 48))
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), Color.argb(100, 91, 174, 255))
    }



    private fun frostedPanelBackground(
        radius: Int,
        color: Int = Color.rgb(19, 24, 33),
        opacity: Int = 66
    ) = GradientDrawable().apply {
        val alpha = opacity.coerceIn(0, 100) * 255 / 100
        setColor((color and 0x00FFFFFF) or (alpha shl 24))
        cornerRadius = dp(radius).toFloat()
        if (alpha > 0) setStroke(1, Color.argb((alpha * 0.42f).toInt(), 225, 235, 248))
    }



    private fun selectedCellWidth(): Int =
        AppPrefs.panelPages(this, usesLandscapeLayout())
            .firstOrNull { it.id == AppPrefs.currentPanelId(this, usesLandscapeLayout()) }
            ?.cellWidthDp ?: 64


    private fun showOverlayPanel(
        view: View,
        width: Int,
        height: Int,
        dragHandle: View? = null,
        windowBackground: Drawable? = null,
        surface: OverlaySurface = OverlaySurface.NONE
    ) {
        val panelWidth = minOf(width, (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(1)))
        val panelHeight = minOf(height, (resources.displayMetrics.heightPixels - dp(64)).coerceAtLeast(dp(1)))
        val dialog = Dialog(this)
        val freeformContainer = FrameLayout(this)
        freeformContainer.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // Fade the panel in so RemoteViews/widget inflation does not read as jank.
        val animateIn = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            android.animation.ValueAnimator.areAnimatorsEnabled()
        val scalePanel = windowBackground == null
        if (animateIn) {
            freeformContainer.alpha = 0f
            if (scalePanel) {
                freeformContainer.scaleX = 0.95f
                freeformContainer.scaleY = 0.95f
            }
        }
        dialog.setContentView(freeformContainer)
        dialog.setCanceledOnTouchOutside(true)
        val window = dialog.window
        try {
            if (window == null) {
                Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
                return
            }
            window.setType(overlayWindowType())
            window.setBackgroundDrawable(windowBackground ?: GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            })
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            window.attributes = window.attributes.apply { dimAmount = 0.18f }
            window.setLayout(panelWidth, panelHeight)
            window.attributes = window.attributes.apply { gravity = Gravity.CENTER }
            applyPopupBlur(window, surface)
            var panelDownRawX = 0f
            var panelDownRawY = 0f
            var panelStartX = 0
            var panelStartY = 0
            dragHandle?.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        panelDownRawX = event.rawX
                        panelDownRawY = event.rawY
                        panelStartX = (resources.displayMetrics.widthPixels - panelWidth).coerceAtLeast(0) / 2
                        panelStartY = (resources.displayMetrics.heightPixels - panelHeight).coerceAtLeast(0) / 2
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val attributes = window.attributes
                        attributes.gravity = Gravity.TOP or Gravity.START
                        val currentWidth = attributes.width.takeIf { it > 0 } ?: panelWidth
                        val currentHeight = attributes.height.takeIf { it > 0 } ?: panelHeight
                        attributes.x = (panelStartX + (event.rawX - panelDownRawX).toInt())
                            .coerceIn(0, (resources.displayMetrics.widthPixels - currentWidth).coerceAtLeast(0))
                        attributes.y = (panelStartY + (event.rawY - panelDownRawY).toInt())
                            .coerceIn(0, (resources.displayMetrics.heightPixels - currentHeight).coerceAtLeast(0))
                        window.attributes = attributes
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
            dialog.setOnDismissListener {
                if (popupDialog === dialog) {
                    crossWindowBlurListener?.let { listener ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            windowManager.removeCrossWindowBlurEnabledListener(listener)
                        }
                    }
                    crossWindowBlurListener = null
                    popupDialog = null
                    popup = null
                    popupList = null
                    popupAddButton = null
                    popupEditMode = false
                    overlaySurface = OverlaySurface.NONE
                    stopWidgetUpdates()
                    keepFloatBallAvailable()
                }
            }
            dialog.show()
            dialog.window?.let { shown ->
                shown.setLayout(panelWidth, panelHeight)
                // Radius/layout only stick reliably once the window is attached.
                applyPopupBlur(shown, surface)
            }
            if (animateIn) {
                val animation = freeformContainer.animate()
                    .alpha(1f)
                    .setDuration(200L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                if (scalePanel) animation.scaleX(1f).scaleY(1f)
                animation.start()
            }
            popupDialog = dialog
            popup = view
            overlaySurface = surface
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = Consumer<Boolean> { applyPopupBlur(window, surface) }
                windowManager.addCrossWindowBlurEnabledListener(listener)
                crossWindowBlurListener = listener
            }
            bringFloatBallToFront()
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Unable to display assistive panel", error)
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked before the panel appeared", error)
            AppPrefs.setFloatBallEnabled(this, false)
            stopSelf()
        }
    }



    private fun applyPopupBlur(window: android.view.Window, surface: OverlaySurface = overlaySurface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val widgetSurface = surface == OverlaySurface.WIDGETS
        val requestedRadius = when {
            widgetSurface && AppPrefs.widgetPanelBlurEnabled(this) ->
                AppPrefs.widgetPanelBlurRadius(this).coerceIn(0, 80)
            !widgetSurface && AppPrefs.popupBlurEnabled(this) ->
                AppPrefs.popupBlurRadius(this).coerceIn(0, 80)
            else -> 0
        }
        val blurAvailable = windowManager.isCrossWindowBlurEnabled
        val blurEnabled = requestedRadius > 0 && blurAvailable
        // Panel-scope blur still needs FLAG_BLUR_BEHIND; the flag alone opts the
        // window into the blur pipeline, the radii decide what actually blurs.
        val panelOnly = widgetSurface ||
            AppPrefs.popupBlurScope(this) == AppPrefs.BLUR_SCOPE_PANEL
        if (blurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        window.setBackgroundBlurRadius(
            if (blurEnabled && panelOnly) requestedRadius else 0
        )
        val attributes = window.attributes
        attributes.setBlurBehindRadius(
            if (blurEnabled && !panelOnly) requestedRadius else 0
        )
        attributes.dimAmount = if (blurEnabled) 0.14f else 0.18f
        window.attributes = attributes
    }



    private fun removePopup(stopIfEmpty: Boolean = true) {
        popupTemporarilyHidden = false
        popupEditMode = false
        val dialog = popupDialog
        popupDialog = null
        popup?.let { view ->
            if (dialog != null) {
                if (dialog.isShowing) dialog.dismiss()
                return@let
            }
            try {
                windowManager.removeView(view)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Widget popup was already removed", error)
            }
        }
        popup = null
        popupList = null
        overlaySurface = OverlaySurface.NONE
        stopWidgetUpdates()
        if (stopIfEmpty && floatBall == null) keepFloatBallAvailable()
    }



    private fun keepFloatBallAvailable() {
        if (AppPrefs.floatBallEnabled(this) && Settings.canDrawOverlays(this)) {
            showFloatBall()
        } else if (!BackgroundRecordingService.isActive) {
            stopSelf()
        }
    }



    private fun bringFloatBallToFront() {
        if (!AppPrefs.floatBallEnabled(this) || floatBall == null) return
        removeFloatBall()
        showFloatBall()
    }



    private fun removeFloatBall() {
        handler.removeCallbacks(idleDimRunnable)
        floatBall?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Floating shortcut was already removed", error)
            }
        }
        floatBall = null
        floatBallParams = null
    }



    private fun scheduleIdleDim() {
        handler.removeCallbacks(idleDimRunnable)
        val view = floatBall ?: return
        view.animate().cancel()
        view.alpha = AppPrefs.floatBallOpacity(this) / 100f
        view.setAnimationFrozen(false)
        if (AppPrefs.dimWhenIdle(this)) {
            handler.postDelayed(
                idleDimRunnable,
                AppPrefs.idleDelaySeconds(this) * 1000L
            )
        }
    }



    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private class FloatBallIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val iconHandler = Handler(Looper.getMainLooper())
        private var sourceMode = AppPrefs.FloatBallIconMode.STATIC
        private var cropScale = 1f
        private var cropOffsetX = 0f
        private var cropOffsetY = 0f
        private var cropCorner = 70
        private var sourceUris: List<String> = emptyList()
        private var currentIndex = 0
        private var bitmap: Bitmap? = null
        private var animatedDrawable: AnimatedImageDrawable? = null
        private var fallbackMovie: Movie? = null
        private var animationStart = SystemClock.uptimeMillis()
        private var playing = false
        private var screenOn = true
        private var cornerPercent = 34
        private var lastInteraction = SystemClock.uptimeMillis()

        private val animationTick = object : Runnable {
            override fun run() {
                if (!playing || !screenOn || sourceMode != AppPrefs.FloatBallIconMode.GIF) return
                invalidate()
                iconHandler.postDelayed(this, 100L)
            }
        }

        private val idleStopRunnable = Runnable {
            if (playing &&
                AppPrefs.floatBallGifPlayEnabled(context) &&
                AppPrefs.floatBallGifPauseWhenIdle(context) &&
                elapsedSinceInteraction() >= AppPrefs.floatBallGifIdleSeconds(context) * 1000L
            ) {
                stopGifToFirstFrame()
            }
        }

        private val slideshowRunnable = object : Runnable {
            override fun run() {
                if ((!screenOn && AppPrefs.floatBallSlideshowPauseWhenScreenOff(context)) ||
                    sourceMode != AppPrefs.FloatBallIconMode.STATIC ||
                    !AppPrefs.floatBallSlideshowEnabled(context) ||
                    sourceUris.size < 2
                ) return
                currentIndex = (currentIndex + 1) % sourceUris.size
                loadStaticBitmap()
                iconHandler.postDelayed(
                    this,
                    AppPrefs.floatBallSlideshowIntervalSeconds(context) * 1000L
                )
            }
        }

        fun setCornerPercent(percent: Int) {
            cornerPercent = percent.coerceIn(0, 100)
            invalidate()
        }

        fun setIconSources(context: Context) {
            stopAllPlayback()
            sourceMode = AppPrefs.floatBallIconMode(context)
            val crop = AppPrefs.floatBallCrop(context, sourceMode)
            cropScale = crop.scale
            cropOffsetX = crop.x
            cropOffsetY = crop.y
            cropCorner = crop.corner
            setCornerPercent(cropCorner)
            sourceUris = when (sourceMode) {
                AppPrefs.FloatBallIconMode.GIF -> listOfNotNull(AppPrefs.floatBallGifUri(context))
                AppPrefs.FloatBallIconMode.STATIC -> AppPrefs.floatBallStaticUris(context)
            }
            currentIndex = 0
            if (sourceMode == AppPrefs.FloatBallIconMode.GIF) {
                loadGifFirstFrame()
                if (AppPrefs.floatBallGifPlayEnabled(context)) startGifIfAllowed()
            } else {
                loadStaticBitmap()
                if (AppPrefs.floatBallSlideshowEnabled(context) && sourceUris.size >= 2) {
                    startSlideshowIfAllowed()
                }
            }
        }

        fun onUserInteraction() {
            lastInteraction = SystemClock.uptimeMillis()
            if (sourceMode == AppPrefs.FloatBallIconMode.GIF &&
                AppPrefs.floatBallGifPlayEnabled(context)
            ) {
                if (!playing) startGifIfAllowed()
                scheduleGifIdleStop()
            }
        }

        fun onScreenOff() {
            screenOn = false
            // GIF always stops on screen-off. Slideshow can be explicitly allowed
            // to continue by the user, although the battery-friendly default is ON.
            stopAnimationOnly()
            if (AppPrefs.floatBallSlideshowPauseWhenScreenOff(context)) {
                cancelSlideshow()
            } else if (sourceMode == AppPrefs.FloatBallIconMode.STATIC &&
                AppPrefs.floatBallSlideshowEnabled(context) &&
                sourceUris.size >= 2
            ) {
                startSlideshowIfAllowed()
            }
        }

        fun onScreenOn() {
            screenOn = true
            lastInteraction = SystemClock.uptimeMillis()
            if (sourceMode == AppPrefs.FloatBallIconMode.GIF &&
                AppPrefs.floatBallGifPlayEnabled(context)
            ) {
                loadGifFirstFrame()
            } else {
                loadStaticBitmap()
            }
            if (sourceMode == AppPrefs.FloatBallIconMode.STATIC &&
                AppPrefs.floatBallSlideshowEnabled(context) &&
                sourceUris.size >= 2
            ) {
                startSlideshowIfAllowed()
            }
        }

        fun setAnimationFrozen(frozen: Boolean) {
            if (frozen) {
                stopAnimationOnly()
            } else if (screenOn) {
                if (sourceMode == AppPrefs.FloatBallIconMode.GIF &&
                    AppPrefs.floatBallGifPlayEnabled(context)
                ) {
                    startGifIfAllowed()
                } else if (
                    sourceMode == AppPrefs.FloatBallIconMode.STATIC &&
                    AppPrefs.floatBallSlideshowEnabled(context) &&
                    sourceUris.size >= 2
                ) {
                    startSlideshowIfAllowed()
                }
            }
        }

        private fun elapsedSinceInteraction() =
            SystemClock.uptimeMillis() - lastInteraction

        private fun scheduleGifIdleStop() {
            iconHandler.removeCallbacks(idleStopRunnable)
            if (AppPrefs.floatBallGifPauseWhenIdle(context)) {
                iconHandler.postDelayed(
                    idleStopRunnable,
                    AppPrefs.floatBallGifIdleSeconds(context) * 1000L
                )
            }
        }

        private fun startGifIfAllowed() {
            if (!screenOn ||
                sourceMode != AppPrefs.FloatBallIconMode.GIF ||
                !AppPrefs.floatBallGifPlayEnabled(context) ||
                sourceUris.isEmpty()
            ) return

            if (animatedDrawable == null && fallbackMovie == null) loadGifAnimated()
            if (animatedDrawable != null) {
                animatedDrawable?.start()
                playing = true
            } else if (fallbackMovie != null) {
                animationStart = SystemClock.uptimeMillis()
                playing = true
            }
            if (!playing) return
            iconHandler.removeCallbacks(animationTick)
            iconHandler.post(animationTick)
            scheduleGifIdleStop()
            invalidate()
        }

        private fun stopGifToFirstFrame() {
            stopAnimationOnly()
            loadGifFirstFrame()
        }

        private fun stopAnimationOnly() {
            playing = false
            iconHandler.removeCallbacks(animationTick)
            iconHandler.removeCallbacks(idleStopRunnable)
            animatedDrawable?.stop()
            animatedDrawable?.callback = null
            animatedDrawable = null
            fallbackMovie = null
            invalidate()
        }

        private fun stopAllPlayback() {
            stopAnimationOnly()
            cancelSlideshow()
            bitmap?.recycle()
            bitmap = null
        }

        private fun startSlideshowIfAllowed() {
            if ((!screenOn && AppPrefs.floatBallSlideshowPauseWhenScreenOff(context)) ||
                sourceMode != AppPrefs.FloatBallIconMode.STATIC ||
                !AppPrefs.floatBallSlideshowEnabled(context) ||
                sourceUris.size < 2
            ) return
            cancelSlideshow()
            iconHandler.postDelayed(
                slideshowRunnable,
                AppPrefs.floatBallSlideshowIntervalSeconds(context) * 1000L
            )
        }

        private fun cancelSlideshow() {
            iconHandler.removeCallbacks(slideshowRunnable)
        }

        private fun loadStaticBitmap() {
            stopAnimationOnly()
            bitmap?.recycle()
            bitmap = null
            val uri = sourceUris.getOrNull(currentIndex) ?: return
            bitmap = decodeBitmapForView(Uri.parse(uri))
            invalidate()
        }

        private fun loadGifFirstFrame() {
            stopAnimationOnly()
            bitmap?.recycle()
            bitmap = null
            val uri = sourceUris.firstOrNull()?.let(Uri::parse) ?: return
            bitmap = decodeBitmapForView(uri)
            invalidate()
        }

        private fun loadGifAnimated() {
            val uri = sourceUris.firstOrNull()?.let(Uri::parse) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                        val target = maxOf(width, height, dp(48))
                        val size = info.size
                        val scale = minOf(
                            target.toFloat() / size.width.coerceAtLeast(1),
                            target.toFloat() / size.height.coerceAtLeast(1)
                        )
                        decoder.setTargetSize(
                            (size.width * scale).toInt().coerceAtLeast(1),
                            (size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                    if (drawable is AnimatedImageDrawable) {
                        drawable.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE)
                        drawable.callback = this
                        animatedDrawable = drawable
                        bitmap?.recycle()
                        bitmap = null
                        return
                    }
                } catch (error: Exception) {
                    Log.w("FloatBallIcon", "Animated GIF decode failed; using legacy decoder", error)
                }
            }
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    fallbackMovie = Movie.decodeStream(it)
                }
                bitmap?.recycle()
                bitmap = null
            } catch (error: Exception) {
                Log.e("FloatBallIcon", "Cannot decode GIF", error)
                fallbackMovie = null
            }
        }

        private fun decodeBitmapForView(uri: Uri): Bitmap? {
            return try {
                val target = maxOf(width, height, dp(48))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val size = info.size
                        val scale = minOf(
                            target.toFloat() / size.width.coerceAtLeast(1),
                            target.toFloat() / size.height.coerceAtLeast(1)
                        )
                        decoder.setTargetSize(
                            (size.width * scale).toInt().coerceAtLeast(1),
                            (size.height * scale).toInt().coerceAtLeast(1)
                        )
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val longest = maxOf(bounds.outWidth, bounds.outHeight)
                    var sample = 1
                    while (longest > target * sample * 2 && sample < 32) sample *= 2
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                        )
                    }
                }
            } catch (error: Exception) {
                Log.e("FloatBallIcon", "Cannot decode icon", error)
                null
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val saveCount = canvas.save()
            val clipRadius = width * 0.5f * cornerPercent / 100f
            val clipPath = android.graphics.Path().apply {
                addRoundRect(
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    clipRadius,
                    clipRadius,
                    android.graphics.Path.Direction.CW
                )
            }
            canvas.clipPath(clipPath)

            val drawable = animatedDrawable
            if (drawable != null && playing) {
                val iw = drawable.intrinsicWidth.coerceAtLeast(1)
                val ih = drawable.intrinsicHeight.coerceAtLeast(1)
                val scale = maxOf(width.toFloat() / iw, height.toFloat() / ih) * cropScale
                val dw = iw * scale
                val dh = ih * scale
                val cx = width / 2f + cropOffsetX * width / 2f
                val cy = height / 2f + cropOffsetY * height / 2f
                canvas.save()
                canvas.translate(cx - dw / 2f, cy - dh / 2f)
                drawable.setBounds(0, 0, dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1))
                drawable.draw(canvas)
                canvas.restore()
                canvas.restoreToCount(saveCount)
                return
            }

            val movie = fallbackMovie
            if (movie != null && playing) {
                val duration = movie.duration().coerceAtLeast(1)
                val position =
                    ((SystemClock.uptimeMillis() - animationStart) % duration).toInt()
                movie.setTime(position)
                val scale = maxOf(
                    width.toFloat() / movie.width().coerceAtLeast(1),
                    height.toFloat() / movie.height().coerceAtLeast(1)
                ) * cropScale
                val drawWidth = movie.width() * scale
                val drawHeight = movie.height() * scale
                val cx = width / 2f + cropOffsetX * width / 2f
                val cy = height / 2f + cropOffsetY * height / 2f
                canvas.save()
                canvas.translate(cx - drawWidth / 2f, cy - drawHeight / 2f)
                canvas.scale(scale, scale)
                movie.draw(canvas, 0f, 0f, paint)
                canvas.restore()
                canvas.restoreToCount(saveCount)
                return
            }

            bitmap?.let { image ->
                val scale = maxOf(
                    width.toFloat() / image.width.coerceAtLeast(1),
                    height.toFloat() / image.height.coerceAtLeast(1)
                ) * cropScale
                val drawWidth = image.width * scale
                val drawHeight = image.height * scale
                val cx = width / 2f + cropOffsetX * width / 2f
                val cy = height / 2f + cropOffsetY * height / 2f
                canvas.drawBitmap(
                    image,
                    null,
                    RectF(
                        cx - drawWidth / 2f, cy - drawHeight / 2f,
                        cx + drawWidth / 2f, cy + drawHeight / 2f
                    ),
                    paint
                )
                canvas.restoreToCount(saveCount)
                return
            }

            val inset = width * 0.06f
            paint.color = Color.rgb(20, 57, 91)
            val radius = width * 0.5f * cornerPercent / 100f
            canvas.drawRoundRect(
                RectF(inset, inset, width - inset, height - inset),
                radius,
                radius,
                paint
            )
            paint.color = Color.WHITE
            canvas.drawCircle(width / 2f, height / 2f, width * 0.18f, paint)
            canvas.restoreToCount(saveCount)
        }

        override fun invalidateDrawable(drawable: Drawable) {
            super.invalidateDrawable(drawable)
            invalidate()
        }

        override fun onDetachedFromWindow() {
            stopAllPlayback()
            bitmap?.recycle()
            bitmap = null
            super.onDetachedFromWindow()
        }

        private fun dp(value: Int) =
            (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val ACTION_STOP = "com.manh.panelsassistive.action.STOP_FLOAT_BALL"
        const val ACTION_SHOW_POPUP = "com.manh.panelsassistive.action.SHOW_WIDGET_POPUP"
        const val ACTION_HIDE_POPUP = "com.manh.panelsassistive.action.HIDE_WIDGET_POPUP"
        const val ACTION_REFRESH_WIDGETS = "com.manh.panelsassistive.action.REFRESH_WIDGETS"
        const val ACTION_REFRESH_PANEL_LAYOUT =
            "com.manh.panelsassistive.action.REFRESH_PANEL_LAYOUT"
        const val ACTION_UPDATE_FLOAT_BALL = "com.manh.panelsassistive.action.UPDATE_FLOAT_BALL"
        const val ACTION_UPDATE_POPUP_APPEARANCE =
            "com.manh.panelsassistive.action.UPDATE_POPUP_APPEARANCE"
        private const val MEDIA_VOLUME_RELEASE_DELAY_MS = 500L
        const val ACTION_SHOW_BRIGHTNESS_SLIDER = "com.manh.panelsassistive.action.SHOW_BRIGHTNESS_SLIDER"
        private const val ACTION_RECORDING_STARTED = "com.manh.panelsassistive.action.RECORDING_STARTED"
        private const val ACTION_RECORDING_STOPPED = "com.manh.panelsassistive.action.RECORDING_STOPPED"
        private const val ACTION_TOGGLE_FLASHLIGHT = "com.manh.panelsassistive.action.TOGGLE_FLASHLIGHT"
        const val WIDGET_HOST_ID = 0x5041
        private const val TAG = "FloatBallService"
        private const val NOTIFICATION_CHANNEL = "float_ball"
        private const val NOTIFICATION_ID = 41
        @Volatile
        var running: Boolean = false
            private set

        const val ACTION_SHOW_EXPANSION_PANEL = "com.manh.panelsassistive.action.SHOW_EXPANSION_PANEL"
        const val EXTRA_PANEL_ID = "com.manh.panelsassistive.extra.PANEL_ID"

        fun start(context: Context, action: String? = null, panelId: String? = null) {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            val intent = Intent(context, FloatBallService::class.java).apply {
                this.action = action
                if (panelId != null) putExtra(EXTRA_PANEL_ID, panelId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private enum class PopupIcon {
            WIDGETS,
            APPS,
            SHORTCUTS,
            ADD,
            CLOSE,
            BRIGHTNESS,
            ALARM,
            NOTIFICATION,
            RING,
            MEDIA,
            RESIZE,
            MOVE,
            CHEVRON_UP
        }

        private class PopupIconView(
            context: Context,
            private val icon: PopupIcon
        ) : LucideGlyphView(context, iconName(icon), Color.WHITE) {
            fun setIcon(expanded: Boolean) {
                setIconName(if (expanded) "chevron-up" else "chevron-down")
            }
            companion object {
                private fun iconName(icon: PopupIcon): String = when (icon) {
                    PopupIcon.WIDGETS -> "panels-top-left"
                    PopupIcon.APPS -> "grid-2x2"
                    PopupIcon.SHORTCUTS -> "corner-up-right"
                    PopupIcon.ADD -> "plus"
                    PopupIcon.CLOSE -> "x"
                    PopupIcon.BRIGHTNESS -> "sun"
                    PopupIcon.ALARM -> "alarm-clock"
                    PopupIcon.NOTIFICATION -> "bell"
                    PopupIcon.RING -> "phone"
                    PopupIcon.MEDIA -> "music-2"
                    PopupIcon.RESIZE -> "expand"
                    PopupIcon.MOVE -> "move"
                    PopupIcon.CHEVRON_UP -> "chevron-up"
                }
            }
        }

        fun stop(context: Context) {
            AppPrefs.setFloatBallEnabled(context, false)
            context.stopService(Intent(context, FloatBallService::class.java))
        }

        fun setRecordingControlsVisible(context: Context, visible: Boolean) {
            if (!running) return
            val intent = Intent(context, FloatBallService::class.java).apply {
                action = if (visible) ACTION_RECORDING_STARTED else ACTION_RECORDING_STOPPED
            }
            context.startService(intent)
        }

        fun toggleFlashlight(context: Context) {
            val intent = Intent(context, FloatBallService::class.java).apply {
                action = ACTION_TOGGLE_FLASHLIGHT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun refreshPopupAppearance(context: Context) {
            if (!running) return
            context.startService(
                Intent(context, FloatBallService::class.java).apply {
                    action = ACTION_UPDATE_POPUP_APPEARANCE
                }
            )
        }

        fun refreshPanelLayout(context: Context) {
            if (!running) return
            context.startService(
                Intent(context, FloatBallService::class.java).apply {
                    action = ACTION_REFRESH_PANEL_LAYOUT
                }
            )
        }
    }
}
