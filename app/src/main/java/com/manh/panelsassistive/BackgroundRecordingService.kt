package com.manh.panelsassistive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackgroundRecordingService : Service() {
    private class RecordingSourceException(val messageResource: Int) : RuntimeException()

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var recordingUri: Uri? = null
    private var recordingFile: File? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var windowManager: WindowManager? = null
    private var controlBar: LinearLayout? = null
    private var pauseButton: LucideGlyphView? = null
    private var pauseLabel: TextView? = null
    private var controlDragHandle: View? = null
    private var paused = false
    private var isFinalizing = false
    private var foregroundStarted = false
    private var startedAt = 0L
    private var pausedAt = 0L
    private var accumulatedPauseTime = 0L

    private val notificationTicker = object : Runnable {
        override fun run() {
            if (recorder != null) {
                updateNotification()
                handler.postDelayed(this, ONE_SECOND)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Recording service started without microphone permission")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            enterForeground()
        } catch (error: SecurityException) {
            Log.e(TAG, "Microphone foreground-service permission is unavailable", error)
            Toast.makeText(this, R.string.recording_start_failed, Toast.LENGTH_LONG).show()
            stopSelf(startId)
            launchRecordingActivity(this)
            return START_NOT_STICKY
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Microphone foreground service cannot start from the current app state", error)
            Toast.makeText(this, R.string.recording_start_failed, Toast.LENGTH_LONG).show()
            stopSelf(startId)
            launchRecordingActivity(this)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> {
                ActionDispatcher.performHapticFeedback(this)
                togglePause()
            }
            ACTION_STOP -> {
                ActionDispatcher.performHapticFeedback(this)
                stopRecording()
            }
            ACTION_REFRESH_CONTROLS -> {
                if (isActive) {
                    removeControlBar()
                    showControlBar()
                    updatePauseButton()
                } else {
                    stopForeground(true)
                    foregroundStarted = false
                    stopSelf(startId)
                }
            }
            else -> if (recorder == null) startRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (recorder != null) stopRecording()
        removeControlBar()
        super.onDestroy()
    }

    private fun startRecording() {
        var newRecorder: MediaRecorder? = null
        try {
            prepareOutput()
            val createdRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder = createdRecorder
            createdRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                configureInputDevice(this)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                val descriptor = outputDescriptor
                if (descriptor != null) setOutputFile(descriptor.fileDescriptor)
                else setOutputFile(recordingFile?.absolutePath ?: throw IOException("Recording output path is missing"))
                prepare()
                start()
            }
            recorder = newRecorder
            paused = false
            startedAt = SystemClock.elapsedRealtime()
            accumulatedPauseTime = 0L
            isActive = true
            FloatBallService.setRecordingControlsVisible(this, true)
            showControlBar()
            updatePauseButton()
            updateNotification()
            handler.postDelayed(notificationTicker, ONE_SECOND)
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
        } catch (error: IOException) {
            failToStart(newRecorder, error)
        } catch (error: IllegalStateException) {
            failToStart(newRecorder, error)
        } catch (error: SecurityException) {
            failToStart(newRecorder, error)
        } catch (error: RuntimeException) {
            failToStart(newRecorder, error)
        }
    }

    private fun prepareOutput() {
        val filename = "PanelsAssistive_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MUSIC}/Panels Assistive"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            recordingUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create a MediaStore recording")
            outputDescriptor = contentResolver.openFileDescriptor(recordingUri!!, "w")
                ?: throw IOException("Could not open the recording output")
        } else {
            val musicDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
            val directory = File(musicDirectory, "Panels Assistive")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create the recording directory")
            }
            recordingFile = File(directory, filename)
        }
    }

    private fun failToStart(newRecorder: MediaRecorder?, error: Exception) {
        Log.e(TAG, "Unable to start background recording", error)
        try {
            newRecorder?.release()
        } catch (releaseError: RuntimeException) {
            Log.w(TAG, "Unable to release failed recorder", releaseError)
        }
        recorder = null
        closeOutputDescriptor()
        deleteIncompleteOutput()
        isActive = false
        FloatBallService.setRecordingControlsVisible(this, false)
        removeControlBar()
        val message = (error as? RecordingSourceException)?.messageResource
            ?: R.string.recording_start_failed
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopForeground(true)
        stopSelf()
    }

    private fun configureInputDevice(mediaRecorder: MediaRecorder) {
        val phoneEnabled = AppPrefs.recordingPhoneSourceEnabled(this)
        val externalEnabled = AppPrefs.recordingExternalSourceEnabled(this)
        if (!phoneEnabled && !externalEnabled) {
            throw RecordingSourceException(R.string.recording_input_source_required)
        }
        if (phoneEnabled && externalEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            if (phoneEnabled) return
            throw RecordingSourceException(R.string.recording_input_selection_unsupported)
        }

        val inputs = (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
        val selected = if (phoneEnabled) {
            inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: throw RecordingSourceException(R.string.recording_phone_source_unavailable)
        } else {
            inputs.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: throw RecordingSourceException(R.string.recording_external_source_unavailable)
        }
        if (!mediaRecorder.setPreferredDevice(selected)) {
            throw RecordingSourceException(
                if (phoneEnabled) R.string.recording_phone_source_unavailable
                else R.string.recording_external_source_unavailable
            )
        }
    }

    private fun togglePause() {
        val currentRecorder = recorder ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Toast.makeText(this, R.string.recording_pause_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (paused) {
                currentRecorder.resume()
                accumulatedPauseTime += SystemClock.elapsedRealtime() - pausedAt
                paused = false
            } else {
                currentRecorder.pause()
                pausedAt = SystemClock.elapsedRealtime()
                paused = true
            }
            updatePauseButton()
            updateNotification()
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Unable to pause or resume recording", error)
            Toast.makeText(this, R.string.recording_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        if (isFinalizing) return
        val currentRecorder = recorder
        if (currentRecorder == null) {
            stopForeground(true)
            foregroundStarted = false
            stopSelf()
            return
        }
        val duration = recordedDuration()
        isFinalizing = true
        try {
            currentRecorder.stop()
            closeOutputDescriptor()
            finalizeOutput { uri ->
                if (uri == null) {
                    deleteIncompleteOutput()
                    Toast.makeText(this, R.string.recording_start_failed, Toast.LENGTH_LONG).show()
                    stopForeground(true)
                    foregroundStarted = false
                } else {
                    showCompletedNotification(duration, uri)
                    Toast.makeText(this, R.string.recording_saved, Toast.LENGTH_LONG).show()
                }
                stopSelf()
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to finalize background recording", error)
            deleteIncompleteOutput()
            Toast.makeText(this, R.string.recording_start_failed, Toast.LENGTH_LONG).show()
            stopForeground(true)
            foregroundStarted = false
            stopSelf()
        } finally {
            recorder = null
            try {
                currentRecorder.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to release recorder", error)
            }
            closeOutputDescriptor()
            isActive = false
            paused = false
            handler.removeCallbacks(notificationTicker)
            removeControlBar()
            FloatBallService.setRecordingControlsVisible(this, false)
        }
    }

    private fun finalizeOutput(onComplete: (Uri?) -> Unit) {
        val uri = recordingUri
        if (uri != null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updated = contentResolver.update(uri, values, null, null)
            if (updated > 0) {
                recordingUri = null
                onComplete(uri)
            } else {
                onComplete(null)
            }
            return
        }
        val file = recordingFile
        if (file == null || !file.exists() || file.length() <= 0L) {
            onComplete(null)
            return
        }
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("audio/mp4")) { _, scannedUri ->
            if (scannedUri != null) recordingFile = null
            handler.post { onComplete(scannedUri) }
        }
    }

    private fun recordedDuration(): Long {
        if (startedAt == 0L) return 0L
        val end = if (paused) pausedAt else SystemClock.elapsedRealtime()
        return (end - startedAt - accumulatedPauseTime).coerceAtLeast(0L)
    }

    private fun showControlBar() {
        if (!Settings.canDrawOverlays(this) || controlBar != null) return
        val labelsVisible = AppPrefs.recordingControlLabels(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 10, 22, 38))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.argb(150, 124, 190, 255))
            }
        }
        controlDragHandle = android.widget.FrameLayout(this).apply {
            contentDescription = getString(R.string.recording_drag_controls)
            addView(
                LucideGlyphView(this@BackgroundRecordingService, "grip-vertical", GlassUi.TEXT_SECONDARY),
                android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            )
        }.also {
            bar.addView(it, LinearLayout.LayoutParams(dp(44), dp(24)))
        }
        val pauseCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val pauseGlyph = LucideGlyphView(this, "pause", Color.WHITE)
        pauseButton = pauseGlyph
        pauseCell.addView(controlButton(pauseGlyph, getString(R.string.recording_pause)) {
            ActionDispatcher.performHapticFeedback(this@BackgroundRecordingService)
            togglePause()
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(4) })
        pauseLabel = if (labelsVisible) {
            TextView(this).apply {
                text = getString(R.string.recording_pause)
                textSize = 11f
                setTextColor(GlassUi.TEXT_SECONDARY)
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }.also { pauseCell.addView(it) }
        } else {
            null
        }
        bar.addView(pauseCell)

        val stopCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        stopCell.addView(controlButton(
            LucideGlyphView(this@BackgroundRecordingService, "square", STOP_COLOR),
            getString(R.string.recording_stop)
        ) {
            ActionDispatcher.performHapticFeedback(this@BackgroundRecordingService)
            stopRecording()
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(4) })
        if (labelsVisible) {
            stopCell.addView(TextView(this).apply {
                text = getString(R.string.recording_stop)
                textSize = 11f
                setTextColor(GlassUi.TEXT_SECONDARY)
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
        }
        bar.addView(stopCell)

        val params = WindowManager.LayoutParams(
            dp(68),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - dp(80)).coerceAtLeast(0)
            y = (resources.displayMetrics.heightPixels - dp(160)).coerceAtLeast(0) / 2
        }
        var dragDownX = 0f
        var dragDownY = 0f
        var dragStartX = 0
        var dragStartY = 0
        try {
            windowManager?.addView(bar, params)
            controlBar = bar
            controlDragHandle?.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragDownX = event.rawX
                        dragDownY = event.rawY
                        dragStartX = params.x
                        dragStartY = params.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (dragStartX + (event.rawX - dragDownX).toInt())
                            .coerceIn(0, (resources.displayMetrics.widthPixels - bar.width).coerceAtLeast(0))
                        params.y = (dragStartY + (event.rawY - dragDownY).toInt())
                            .coerceIn(0, (resources.displayMetrics.heightPixels - bar.height).coerceAtLeast(0))
                        try {
                            windowManager?.updateViewLayout(bar, params)
                        } catch (error: IllegalArgumentException) {
                            Log.w(TAG, "Recording controls were removed while moving", error)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
            bar.post {
                if (controlBar !== bar) return@post
                params.x = (resources.displayMetrics.widthPixels - bar.width - dp(12)).coerceAtLeast(0)
                params.y = (resources.displayMetrics.heightPixels - bar.height).coerceAtLeast(0) / 2
                try {
                    windowManager?.updateViewLayout(bar, params)
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "Unable to position recording controls", error)
                }
            }
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Unable to show recording controls", error)
        } catch (error: SecurityException) {
            Log.e(TAG, "Overlay permission is required for recording controls", error)
        }
    }

    private fun updatePauseButton() {
        pauseButton?.setIconName(if (paused) "play" else "pause")
        val label = getString(if (paused) R.string.recording_resume else R.string.recording_pause)
        (pauseButton?.parent as? View)?.contentDescription = label
        pauseLabel?.text = label
    }

    private fun removeControlBar() {
        controlBar?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Recording controls were already removed", error)
            }
        }
        controlBar = null
        pauseButton = null
        pauseLabel = null
        controlDragHandle = null
    }

    private fun controlButton(
        glyph: LucideGlyphView,
        description: String,
        onClick: () -> Unit
    ): android.widget.FrameLayout = android.widget.FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(30, 224, 236, 255))
        }
        contentDescription = description
        isClickable = true
        isFocusable = true
        addView(glyph, android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun updateNotification() {
        if (foregroundStarted) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun enterForeground() {
        if (foregroundStarted) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(): Notification {
        val pauseIntent = PendingIntent.getService(
            this,
            REQUEST_PAUSE,
            Intent(this, BackgroundRecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, BackgroundRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val content = if (paused) {
            getString(R.string.recording_notification_paused_duration, formatDuration(recordedDuration()))
        } else if (recorder != null) {
            getString(R.string.recording_notification_active_duration, formatDuration(recordedDuration()))
        } else {
            getString(R.string.recording_notification_active)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(content)
            .setContentIntent(recordingLibraryPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                getString(if (paused) R.string.recording_resume else R.string.recording_pause),
                pauseIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.recording_stop),
                stopIntent
            )
        return builder.build()
    }

    private fun showCompletedNotification(duration: Long, recordingUri: Uri) {
        stopForeground(true)
        foregroundStarted = false
        val playbackIntent = PendingIntent.getActivity(
            this,
            REQUEST_PLAYBACK,
            recordingActivityIntent(recordingUri = recordingUri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val libraryIntent = recordingLibraryPendingIntent()
        val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        })
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.recording_completed_title))
            .setContentText(getString(R.string.recording_completed_duration, formatDuration(duration)))
            .setContentIntent(playbackIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_agenda,
                getString(R.string.manage_recordings),
                libraryIntent
            )
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun recordingLibraryPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_LIBRARY,
            recordingActivityIntent(action = RecordingPlaybackActivity.ACTION_SHOW_RECORDINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun recordingActivityIntent(action: String? = null, recordingUri: Uri? = null) =
        Intent(this, RecordingPlaybackActivity::class.java).apply {
            this.action = action
            data = recordingUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun closeOutputDescriptor() {
        try {
            outputDescriptor?.close()
        } catch (error: IOException) {
            Log.w(TAG, "Unable to close recording output", error)
        }
        outputDescriptor = null
    }

    private fun deleteIncompleteOutput() {
        recordingUri?.let {
            try {
                contentResolver.delete(it, null, null)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to delete incomplete recording", error)
            }
        }
        recordingUri = null
        recordingFile?.let { file ->
            if (file.exists() && !file.delete()) Log.w(TAG, "Unable to delete incomplete recording file")
        }
        recordingFile = null
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000L
        return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BackgroundRecording"
        private const val CHANNEL_ID = "background_recording"
        private const val NOTIFICATION_ID = 52
        private const val REQUEST_PAUSE = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_PLAYBACK = 3
        private const val REQUEST_LIBRARY = 4
        private const val ONE_SECOND = 1000L
        private val STOP_COLOR = Color.rgb(255, 122, 122)

        const val ACTION_START = "com.manh.panelsassistive.action.START_BACKGROUND_RECORDING"
        const val ACTION_TOGGLE_PAUSE = "com.manh.panelsassistive.action.TOGGLE_RECORDING_PAUSE"
        const val ACTION_STOP = "com.manh.panelsassistive.action.STOP_BACKGROUND_RECORDING"
        private const val ACTION_REFRESH_CONTROLS = "com.manh.panelsassistive.action.REFRESH_RECORDING_CONTROLS"

        @Volatile
        var isActive: Boolean = false
            private set

        fun requestStart(context: Context) {
            val microphonePermissionMissing =
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            val notificationPermissionMissing =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (microphonePermissionMissing ||
                notificationPermissionMissing ||
                !Settings.canDrawOverlays(context)
            ) {
                launchRecordingActivity(context)
                return
            }
            try {
                start(context)
            } catch (error: SecurityException) {
                Log.e(TAG, "Unable to start microphone foreground service", error)
                launchRecordingActivity(context)
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Recording needs a visible activity to start", error)
                launchRecordingActivity(context)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, BackgroundRecordingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refreshControls(context: Context) {
            if (!isActive) return
            context.startService(
                Intent(context, BackgroundRecordingService::class.java)
                    .setAction(ACTION_REFRESH_CONTROLS)
            )
        }

        private fun launchRecordingActivity(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_START_BACKGROUND_RECORDING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
