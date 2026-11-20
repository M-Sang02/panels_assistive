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
import android.widget.SeekBar
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
    private var crossWindowBlurListener: Consumer<Boolean>? = null
    private var brightnessPermissionPrompted = false
    private var longPressTriggered = false
    private var tapCount = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0

    private val configurationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    repositionFloatBall()
                    if (popup != null) populateWidgetList()
                }
                Intent.ACTION_SCREEN_OFF -> floatBall?.onScreenOff()
                Intent.ACTION_SCREEN_ON -> floatBall?.onScreenOn()
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
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        try {
            widgetHost.startListening()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start widget host", error)
            Toast.makeText(this, R.string.widget_host_unavailable, Toast.LENGTH_LONG).show()
        }
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
                toggleFlashlight()
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
                if (popup != null) populateWidgetList()
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
                popupDialog?.window?.let(::applyPopupBlur)
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
        refreshTile()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
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
        removePopup()
        removeFloatBall()
        try {
            widgetHost.stopListening()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to stop widget host", error)
        }
        refreshTile()
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
        try {
            widgetHost.startListening()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to resume widget updates for popup", error)
            Toast.makeText(this, R.string.widget_host_unavailable, Toast.LENGTH_LONG).show()
        }
        val landscape = usesLandscapeLayout()
        val pages = AppPrefs.panelPages(this, landscape)
        val page = pages.firstOrNull { it.id == AppPrefs.currentPanelId(this, landscape) } ?: pages.first()
        val popupBackground = frostedPanelBackground(28, page.backgroundColor, page.backgroundOpacity)
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
        val surfaceHeight = dp(maxOf(
            viewportHeightDp,
            PanelViewportMetrics.heightDp(rows, page.rowHeightDp)
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
                Gravity.CENTER
            ))
        }
        val vertical = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(surface, FrameLayout.LayoutParams(surfaceWidth, surfaceHeight))
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
        populateWidgetList(root)
        showOverlayPanel(
            root,
            dp(PanelViewportMetrics.panelWidthDp(page)),
            dp(PanelViewportMetrics.panelHeightDp(page)),
            windowBackground = popupBackground,
            backgroundOpacity = page.backgroundOpacity
        )
        root.post {
            horizontal.scrollTo(
                ((vertical.width - horizontal.width) / 2).coerceAtLeast(0),
                0
            )
            vertical.post {
                vertical.scrollTo(
                    0,
                    ((surface.height - vertical.height) / 2).coerceAtLeast(0)
                )
            }
        }
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
            MOVE
        }

        private class PopupIconView(
            context: Context,
            private val icon: PopupIcon
        ) : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 2f
            }

            fun setIconColor(color: Int) {
                paint.color = color
                invalidate()
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val size = minOf(width, height).toFloat()
                if (size <= 0f) return
                canvas.save()
                canvas.translate((width - size) / 2f, (height - size) / 2f)
                canvas.scale(size / 24f, size / 24f)
                when (icon) {
                    PopupIcon.WIDGETS -> {
                        canvas.drawRoundRect(4f, 4f, 20f, 9f, 1.5f, 1.5f, paint)
                        canvas.drawRoundRect(4f, 11f, 20f, 16f, 1.5f, 1.5f, paint)
                        canvas.drawRoundRect(4f, 18f, 20f, 20f, 1f, 1f, paint)
                    }
                    PopupIcon.APPS -> {
                        canvas.drawRoundRect(4f, 4f, 10f, 10f, 1.5f, 1.5f, paint)
                        canvas.drawRoundRect(14f, 4f, 20f, 10f, 1.5f, 1.5f, paint)
                        canvas.drawRoundRect(4f, 14f, 10f, 20f, 1.5f, 1.5f, paint)
                        canvas.drawRoundRect(14f, 14f, 20f, 20f, 1.5f, 1.5f, paint)
                    }
                    PopupIcon.SHORTCUTS -> {
                        val path = android.graphics.Path().apply {
                            moveTo(6f, 18f)
                            lineTo(6f, 10f)
                            quadTo(6f, 6f, 10f, 6f)
                            lineTo(18f, 6f)
                            moveTo(14f, 3f)
                            lineTo(18f, 6f)
                            lineTo(14f, 9f)
                        }
                        canvas.drawPath(path, paint)
                        canvas.drawCircle(6f, 18f, 2f, paint)
                    }
                    PopupIcon.ADD -> {
                        canvas.drawLine(12f, 5f, 12f, 19f, paint)
                        canvas.drawLine(5f, 12f, 19f, 12f, paint)
                    }
                    PopupIcon.CLOSE -> {
                        canvas.drawLine(6f, 6f, 18f, 18f, paint)
                        canvas.drawLine(18f, 6f, 6f, 18f, paint)
                    }
                    PopupIcon.BRIGHTNESS -> {
                        canvas.drawCircle(12f, 12f, 4f, paint)
                        for (index in 0 until 8) {
                            val angle = Math.PI * index / 4
                            val inner = 7f
                            val outer = 10f
                            canvas.drawLine(
                                12f + (kotlin.math.cos(angle) * inner).toFloat(),
                                12f + (kotlin.math.sin(angle) * inner).toFloat(),
                                12f + (kotlin.math.cos(angle) * outer).toFloat(),
                                12f + (kotlin.math.sin(angle) * outer).toFloat(),
                                paint
                            )
                        }
                    }
                    PopupIcon.ALARM -> {
                        canvas.drawCircle(12f, 13f, 7f, paint)
                        canvas.drawLine(12f, 13f, 12f, 9f, paint)
                        canvas.drawLine(12f, 13f, 16f, 15f, paint)
                        canvas.drawLine(6f, 5f, 9f, 8f, paint)
                        canvas.drawLine(18f, 5f, 15f, 8f, paint)
                    }
                    PopupIcon.NOTIFICATION -> {
                        canvas.drawArc(6f, 5f, 18f, 19f, 180f, 180f, false, paint)
                        canvas.drawLine(6f, 12f, 6f, 17f, paint)
                        canvas.drawLine(18f, 12f, 18f, 17f, paint)
                        canvas.drawLine(4f, 17f, 20f, 17f, paint)
                        canvas.drawCircle(12f, 20f, 1f, paint)
                    }
                    PopupIcon.RING -> {
                        val path = android.graphics.Path().apply {
                            moveTo(7f, 4f)
                            lineTo(10f, 3f)
                            lineTo(13f, 8f)
                            lineTo(11f, 10f)
                            quadTo(13f, 14f, 17f, 16f)
                            lineTo(19f, 14f)
                            lineTo(22f, 17f)
                            lineTo(21f, 20f)
                            quadTo(20f, 22f, 17f, 21f)
                            quadTo(7f, 17f, 4f, 7f)
                            quadTo(3f, 5f, 7f, 4f)
                            close()
                        }
                        canvas.drawPath(path, paint)
                    }
                    PopupIcon.MEDIA -> {
                        val path = android.graphics.Path().apply {
                            moveTo(8f, 5f)
                            lineTo(19f, 12f)
                            lineTo(8f, 19f)
                            close()
                        }
                        canvas.drawPath(path, paint)
                    }
                    PopupIcon.RESIZE -> {
                        canvas.drawLine(7f, 17f, 17f, 7f, paint)
                        canvas.drawLine(12f, 7f, 17f, 7f, paint)
                        canvas.drawLine(17f, 7f, 17f, 12f, paint)
                        canvas.drawLine(7f, 17f, 7f, 12f, paint)
                        canvas.drawLine(7f, 17f, 12f, 17f, paint)
                    }
                    PopupIcon.MOVE -> {
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
                    }
                }
                canvas.restore()
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
