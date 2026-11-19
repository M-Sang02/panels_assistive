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
            if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                repositionFloatBall()
                if (popup != null) populateWidgetList()
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
        val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
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
            setIconUri(AppPrefs.floatBallIconUri(this@FloatBallService))
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
        view.setIconUri(AppPrefs.floatBallIconUri(this))
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
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private var sourceUri: String? = null
            private var bitmap: Bitmap? = null
            private var movie: Movie? = null
            private var animationStart = SystemClock.uptimeMillis()
            private var animationFrozen = false
            private var frozenPosition = 0
            private var cornerPercent = 34

            fun setCornerPercent(percent: Int) {
                cornerPercent = percent.coerceIn(0, 100)
                invalidate()
            }

            fun setIconUri(uri: String?) {
                if (uri == sourceUri) return
                sourceUri = uri
                bitmap = null
                movie = null
                if (uri == null) {
                    invalidate()
                    return
                }
                try {
                    val parsedUri = Uri.parse(uri)
                    movie = context.contentResolver.openInputStream(parsedUri)?.use { Movie.decodeStream(it) }
                    if (movie == null) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(parsedUri)?.use {
                            BitmapFactory.decodeStream(it, null, bounds)
                        }
                        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
                        var sampleSize = 1
                        while (longestSide > 0 && longestSide / sampleSize > 512) sampleSize *= 2
                        bitmap = context.contentResolver.openInputStream(parsedUri)?.use {
                            BitmapFactory.decodeStream(
                                it,
                                null,
                                BitmapFactory.Options().apply { inSampleSize = sampleSize }
                            )
                        }
                    }
                    animationStart = SystemClock.uptimeMillis()
                    animationFrozen = false
                    frozenPosition = 0
                } catch (error: SecurityException) {
                    Log.e("FloatBallIcon", "Cannot read the saved icon URI", error)
                    sourceUri = null
                    AppPrefs.setFloatBallIconUri(context, null)
                } catch (error: IllegalArgumentException) {
                    Log.e("FloatBallIcon", "Saved icon URI is invalid", error)
                    sourceUri = null
                    AppPrefs.setFloatBallIconUri(context, null)
                }
                invalidate()
            }

            fun setAnimationFrozen(frozen: Boolean) {
                if (animationFrozen == frozen) return
                val animated = movie
                if (animated != null) {
                    val duration = animated.duration().coerceAtLeast(1)
                    if (frozen) {
                        frozenPosition =
                            ((SystemClock.uptimeMillis() - animationStart) % duration).toInt()
                    } else {
                        animationStart = SystemClock.uptimeMillis() - frozenPosition
                    }
                }
                animationFrozen = frozen
                invalidate()
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
                val animated = movie
                if (animated != null && animated.width() > 0 && animated.height() > 0) {
                    val duration = animated.duration().coerceAtLeast(1)
                    if (!animationFrozen) {
                        frozenPosition =
                            ((SystemClock.uptimeMillis() - animationStart) % duration).toInt()
                    }
                    animated.setTime(frozenPosition)
                    val scale = minOf(width.toFloat() / animated.width(), height.toFloat() / animated.height())
                    val drawWidth = animated.width() * scale
                    val drawHeight = animated.height() * scale
                    canvas.save()
                    canvas.translate((width - drawWidth) / 2f, (height - drawHeight) / 2f)
                    canvas.scale(scale, scale)
                    animated.draw(canvas, 0f, 0f, paint)
                    canvas.restore()
                    canvas.restoreToCount(saveCount)
                    if (!animationFrozen) postInvalidateDelayed(33)
                    return
                }
                bitmap?.let { image ->
                    val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
                    val drawWidth = image.width * scale
                    val drawHeight = image.height * scale
                    val destination = RectF(
                        (width - drawWidth) / 2f,
                        (height - drawHeight) / 2f,
                        (width + drawWidth) / 2f,
                        (height + drawHeight) / 2f
                    )
                    canvas.drawBitmap(image, null, destination, paint)
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
                paint.color = Color.rgb(103, 184, 255)
                val barHeight = height * 0.09f
                val startX = width * 0.3f
                val endX = width * 0.7f
                val centerY = height * 0.5f
                for (index in -1..1) {
                    val y = centerY + index * barHeight * 1.8f
                    canvas.drawRoundRect(
                        RectF(startX, y - barHeight / 2f, endX, y + barHeight / 2f),
                        barHeight,
                        barHeight,
                        paint
                    )
                }
                canvas.restoreToCount(saveCount)
            }
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

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun openWidgetPicker(type: String, position: Int = -1) {
        launchPicker(type, position)
    }

    private fun launchPicker(type: String, position: Int = -1, expansionIndex: Int = -1) {
        popupTemporarilyHidden = popupDialog?.isShowing == true
        popupDialog?.hide()
        removeFloatBall()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_WIDGET_PICKER, true)
                .putExtra(WidgetPickerActivity.EXTRA_LANDSCAPE_LAYOUT, usesLandscapeLayout())
                .putExtra(
                    WidgetPickerActivity.EXTRA_PANEL_ID,
                    AppPrefs.currentPanelId(this, usesLandscapeLayout())
                )
                .putExtra(WidgetPickerActivity.EXTRA_ITEM_TYPE, type)
                .putExtra(WidgetPickerActivity.EXTRA_POSITION, position)
                .putExtra(WidgetPickerActivity.EXTRA_EXPANSION_SLOT, expansionIndex)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        )
    }

    private fun showOverlayDialog(builder: android.app.AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    private fun showExpansionPanel() {
        if (popup != null) removePopup(stopIfEmpty = false)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = frostedPanelBackground(24)
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.expansion_panel_title)
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addBrightnessControl(sliderRow)
        addVolumeControl(sliderRow, R.string.alarm_volume, PopupIcon.ALARM, android.media.AudioManager.STREAM_ALARM)
        addVolumeControl(sliderRow, R.string.notification_volume, PopupIcon.NOTIFICATION, android.media.AudioManager.STREAM_NOTIFICATION)
        addVolumeControl(sliderRow, R.string.ring_volume, PopupIcon.RING, android.media.AudioManager.STREAM_RING)
        addVolumeControl(sliderRow, R.string.media_volume, PopupIcon.MEDIA, android.media.AudioManager.STREAM_MUSIC)
        panel.addView(sliderRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(190)
        ))
        val shortcutItems = AppPrefs.expansionShortcuts(this)
        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shortcutItems.forEachIndexed { index, shortcut ->
            val cell = if (shortcut == null) {
                FrameLayout(this).apply {
                    contentDescription = getString(R.string.add_shortcut)
                    background = roundedControlBackground()
                    isClickable = true
                    isFocusable = true
                    addView(PopupIconView(this@FloatBallService, PopupIcon.ADD).apply {
                        setIconColor(Color.WHITE)
                    }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    setOnClickListener { showExpansionShortcutOptions(index) }
                }
            } else {
                val shortcutView = createPanelShortcut(
                    PanelItem(shortcut.type, shortcut.value, shortcut.label),
                    showLabel = false
                )
                val removeButton = popupItemControl(PopupIcon.CLOSE, R.string.remove_shortcut) {
                    AppPrefs.setExpansionShortcut(this@FloatBallService, index, null)
                    showExpansionPanel()
                }.apply {
                    visibility = View.GONE
                    contentDescription = getString(R.string.remove_shortcut)
                }
                FrameLayout(this).apply {
                    addView(shortcutView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    addView(removeButton, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
                    shortcutView.setOnLongClickListener {
                        removeButton.visibility = View.VISIBLE
                        true
                    }
                }
            }
            shortcuts.addView(cell, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        panel.addView(shortcuts)
        showOverlayPanel(panel, dp(360), dp(390))
    }

    private fun showExpansionShortcutOptions(index: Int) {
        val choices = arrayOf(
            getString(R.string.choose_app),
            getString(R.string.choose_shortcut),
            getString(R.string.choose_phone_action)
        )
        showOverlayDialog(android.app.AlertDialog.Builder(this)
            .setTitle(R.string.select_expansion_shortcut_type)
            .setItems(choices) { _, selected ->
                when (selected) {
                    0 -> openExpansionPicker(PanelItemType.APP, index)
                    1 -> openExpansionPicker(PanelItemType.SHORTCUT, index)
                    2 -> showExpansionActionChoices(index)
                }
            }
        )
    }

    private fun openExpansionPicker(type: String, index: Int) {
        launchPicker(type, expansionIndex = index)
    }

    private fun showExpansionActionChoices(index: Int) {
        val actions = ActionDispatcher.quickActions(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: android.app.AlertDialog
        actions.forEach { choice ->
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = true
                addView(ActionGlyphView(this@FloatBallService, choice.id, Color.rgb(125, 188, 255)),
                    LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(this@FloatBallService).apply {
                    text = choice.label
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), 0, 0, 0)
                })
                setOnClickListener {
                    AppPrefs.setExpansionShortcut(
                        this@FloatBallService,
                        index,
                        ExpansionShortcut(PanelItemType.ACTION, choice.id, choice.label)
                    )
                    dialog.dismiss()
                    showExpansionPanel()
                }
            })
        }
        dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.select_action)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    private fun showBrightnessSlider() {
        if (popup != null) removePopup(stopIfEmpty = false)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = frostedPanelBackground(24)
        }
        val icon = PopupIconView(this, PopupIcon.BRIGHTNESS).apply {
            setIconColor(Color.rgb(125, 188, 255))
            contentDescription = getString(R.string.brightness_slider)
        }
        panel.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            topMargin = dp(8)
        })
        val current = if (Settings.System.canWrite(this)) {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } else {
            128
        }
        panel.addView(verticalSlider(255, current, dp(190)) { updateBrightness(it) }, LinearLayout.LayoutParams(
            dp(64),
            dp(210)
        ))
        showOverlayPanel(panel, dp(150), dp(310))
    }

    private fun addBrightnessControl(row: LinearLayout) {
        val current = if (Settings.System.canWrite(this)) {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } else {
            128
        }
        row.addView(sliderColumn(
            R.string.brightness_short_label,
            PopupIcon.BRIGHTNESS,
            verticalSlider(255, current) { updateBrightness(it) }
        ), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun addVolumeControl(row: LinearLayout, label: Int, icon: PopupIcon, stream: Int) {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        val maximum = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val current = audio.getStreamVolume(stream).coerceIn(0, maximum)
        val deferUntilReleased = stream == android.media.AudioManager.STREAM_MUSIC
        var pendingProgress = current
        val applyPendingVolume = Runnable {
            setStreamVolume(audio, stream, pendingProgress.coerceIn(0, maximum))
        }
        row.addView(sliderColumn(
            label,
            icon,
            verticalSlider(
                maximum,
                current,
                deferUpdatesUntilReleased = deferUntilReleased,
                onTrackingStarted = {
                    if (deferUntilReleased) handler.removeCallbacks(applyPendingVolume)
                },
                onReleased = {
                    if (deferUntilReleased) {
                        handler.removeCallbacks(applyPendingVolume)
                        handler.postDelayed(applyPendingVolume, MEDIA_VOLUME_RELEASE_DELAY_MS)
                    }
                }
            ) { progress ->
                pendingProgress = progress
                if (!deferUntilReleased) {
                    setStreamVolume(audio, stream, progress.coerceIn(0, maximum))
                }
            }
        ), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun setStreamVolume(
        audio: android.media.AudioManager,
        stream: Int,
        requested: Int
    ) {
        try {
            val flags = android.media.AudioManager.FLAG_SHOW_UI or
                android.media.AudioManager.FLAG_PLAY_SOUND
            audio.setStreamVolume(stream, requested, flags)
            var actual = audio.getStreamVolume(stream)
            var attempts = 0
            val maximumAttempts = audio.getStreamMaxVolume(stream).coerceAtLeast(1) + 1
            while (actual != requested && attempts++ < maximumAttempts) {
                val direction = if (actual < requested) {
                    android.media.AudioManager.ADJUST_RAISE
                } else {
                    android.media.AudioManager.ADJUST_LOWER
                }
                audio.adjustStreamVolume(
                    stream,
                    direction,
                    if (actual == requested - 1 || actual == requested + 1) flags else 0
                )
                val updated = audio.getStreamVolume(stream)
                if (updated == actual) break
                actual = updated
            }
            if (actual != requested) {
                Log.w(TAG, "System volume did not accept level $requested for stream $stream")
                Toast.makeText(this, R.string.volume_update_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to update volume stream $stream", error)
            Toast.makeText(this, R.string.volume_update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sliderColumn(label: Int, icon: PopupIcon, slider: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(PopupIconView(this@FloatBallService, icon).apply {
                setIconColor(Color.rgb(125, 188, 255))
                contentDescription = getString(label)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(slider, LinearLayout.LayoutParams(dp(48), dp(132)).apply {
                topMargin = dp(6)
            })
            addView(TextView(this@FloatBallService).apply {
                text = getString(label)
                textSize = 9f
                setTextColor(Color.rgb(166, 173, 185))
                gravity = Gravity.CENTER
                maxLines = 2
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30)
            ))
        }

    private fun verticalSlider(
        maximum: Int,
        current: Int,
        sliderLength: Int = dp(132),
        deferUpdatesUntilReleased: Boolean = false,
        onTrackingStarted: () -> Unit = {},
        onReleased: () -> Unit = {},
        onChanged: (Int) -> Unit
    ): FrameLayout {
        val frame = FrameLayout(this)
        val seekBar = SeekBar(this).apply {
            max = maximum.coerceAtLeast(1)
            progress = current.coerceIn(0, max)
            rotation = -90f
            layoutParams = FrameLayout.LayoutParams(sliderLength, dp(48), Gravity.CENTER)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        onChanged(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    if (deferUpdatesUntilReleased) onTrackingStarted()
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (deferUpdatesUntilReleased) onReleased()
                }
            })
        }
        frame.addView(seekBar)
        return frame
    }

    private fun updateBrightness(progress: Int) {
        if (!Settings.System.canWrite(this)) {
            if (!brightnessPermissionPrompted) {
                brightnessPermissionPrompted = true
                Toast.makeText(this, R.string.brightness_permission_required, Toast.LENGTH_LONG).show()
                startActivity(Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        brightnessPermissionPrompted = false
        try {
            val modeSaved = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val brightnessSaved = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                progress.coerceIn(1, 255)
            )
            if (!modeSaved || !brightnessSaved) {
                Toast.makeText(this, R.string.brightness_update_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission to change system brightness was revoked", error)
            Toast.makeText(this, R.string.brightness_update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun panelHeader(title: Int, onClose: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@FloatBallService).apply {
                text = getString(title)
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(
                popupIconButton(PopupIcon.CLOSE, R.string.close, onClose),
                LinearLayout.LayoutParams(dp(42), dp(42))
            )
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
            ?.cellWidthDp ?: 72

    private fun showOverlayPanel(
        view: View,
        width: Int,
        height: Int,
        dragHandle: View? = null,
        windowBackground: Drawable? = null,
        backgroundOpacity: Int? = null
    ) {
        val panelWidth = minOf(width, (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(1)))
        val panelHeight = minOf(height, (resources.displayMetrics.heightPixels - dp(64)).coerceAtLeast(dp(1)))
        val dialog = Dialog(this)
        val freeformContainer = FrameLayout(this)
        freeformContainer.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
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
            applyPopupBlur(window)
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
                    keepFloatBallAvailable()
                }
            }
            dialog.show()
            dialog.window?.setLayout(panelWidth, panelHeight)
            popupDialog = dialog
            popup = view
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = Consumer<Boolean> { applyPopupBlur(window) }
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

    private fun applyPopupBlur(window: android.view.Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val blurRequested = AppPrefs.popupBlurEnabled(this)
        val requestedRadius = if (blurRequested) {
            AppPrefs.popupBlurRadius(this).coerceIn(0, 80)
        } else {
            0
        }
        val blurAvailable = windowManager.isCrossWindowBlurEnabled
        val blurEnabled = requestedRadius > 0 && blurAvailable
        val panelOnly = AppPrefs.popupBlurScope(this) == AppPrefs.BLUR_SCOPE_PANEL
        if (blurEnabled && !panelOnly) {
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
        attributes.dimAmount = if (blurEnabled) 0.14f else 0.42f
        window.attributes = attributes
    }

    private fun hasFlashlightPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestFlashlightPermission() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REQUEST_CAMERA_PERMISSION, true)
                .putExtra(MainActivity.EXTRA_RUN_TORCH_AFTER_PERMISSION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun toggleFlashlight() {
        if (!hasFlashlightPermission()) {
            requestFlashlightPermission()
            return
        }
        val cameraManager = getSystemService(CameraManager::class.java)
        if (cameraManager == null) {
            Toast.makeText(this, R.string.flashlight_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run {
                Toast.makeText(this, R.string.flashlight_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            val enabled = !AppPrefs.flashlightEnabled(this)
            cameraManager.setTorchMode(cameraId, enabled)
            AppPrefs.setFlashlightEnabled(this, enabled)
        } catch (error: CameraAccessException) {
            Log.e(TAG, "Unable to toggle flashlight", error)
            Toast.makeText(this, R.string.flashlight_unavailable, Toast.LENGTH_SHORT).show()
        } catch (error: SecurityException) {
            Log.e(TAG, "Camera permission is required for flashlight control", error)
            requestFlashlightPermission()
        }
    }

    private fun usesLandscapeLayout() =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            AppPrefs.landscapeWidgetLayoutEnabled(this)

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

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun clampPosition(params: WindowManager.LayoutParams, size: Int) {
        params.x = params.x.coerceIn(0, maxX(size))
        params.y = params.y.coerceIn(safeTop(), safeTop() + maxY(size))
    }

    private fun persistPosition(params: WindowManager.LayoutParams, size: Int) {
        val rangeX = maxX(size).coerceAtLeast(1)
        val rangeY = maxY(size).coerceAtLeast(1)
        AppPrefs.setPosition(
            this,
            params.x.toFloat() / rangeX,
            (params.y - safeTop()).toFloat() / rangeY
        )
    }

    private fun applyEdgeHide(params: WindowManager.LayoutParams, size: Int) {
        if (!AppPrefs.floatBallEdgeHideEnabled(this)) return
        val landscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val autoMirror = AppPrefs.floatBallAutoMirror(this)
        val fixedLeft = AppPrefs.floatBallFixedSide(this) == "left"
        val left = if (autoMirror) {
            val centerX = params.x + size / 2
            centerX < resources.displayMetrics.widthPixels / 2
        } else {
            fixedLeft
        }
        val percent = AppPrefs.floatBallEdgeOffset(this, landscape, left)
        val extra = (size * percent / 100f).toInt()
        params.x = if (left) {
            -extra.coerceIn(0, size)
        } else {
            (maxX(size) + extra.coerceIn(0, size)).coerceAtLeast(0)
        }
    }

    private fun maxX(size: Int) = (resources.displayMetrics.widthPixels - size).coerceAtLeast(0)

    private fun maxY(size: Int) =
        (resources.displayMetrics.heightPixels - safeTop() - safeBottom() - size).coerceAtLeast(0)

    private fun safeTop() = systemDimension("status_bar_height")

    private fun safeBottom() = systemDimension("navigation_bar_height")

    private fun systemDimension(name: String): Int {
        val resourceId = resources.getIdentifier(name, "dimen", "android")
        return if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun overlayWindowType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun refreshTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            QuickSettingsTiles.updateListeningServices(this)
        }
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
