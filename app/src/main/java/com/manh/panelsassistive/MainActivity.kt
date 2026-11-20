package com.manh.panelsassistive

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.Toast

class MainActivity : Activity() {
    private var settingsScroll: ScrollView? = null
    private var lastBackPressMillis = 0L
    private var backPressCount = 0
    private val backExitHandler = Handler(Looper.getMainLooper())
    private val resetBackPress = Runnable {
        backPressCount = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val background = GlassUi.PAGE_COLOR
        window.statusBarColor = background
        window.navigationBarColor = background
        if (intent.getBooleanExtra(EXTRA_REQUEST_OVERLAY_PERMISSION, false) &&
            !Settings.canDrawOverlays(this)
        ) {
            intent.removeExtra(EXTRA_REQUEST_OVERLAY_PERMISSION)
            AppPrefs.setPendingOverlayEnable(this, true)
            startActivity(
                android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        if (AppPrefs.floatBallEnabled(this) && !Settings.canDrawOverlays(this)) {
            FloatBallService.stop(this)
        }
        if (AppPrefs.takePendingOverlayEnable(this) && Settings.canDrawOverlays(this)) {
            FloatBallService.start(this)
        }
        val previousScroll = settingsScroll?.scrollY ?: 0
        val scroll = SettingsScreen(this).createView()
        settingsScroll = scroll
        setContentView(scroll)
        if (previousScroll > 0) {
            scroll.post { scroll.scrollTo(0, previousScroll) }
        }
        launchRequestedPicker()
        if (intent.getBooleanExtra(EXTRA_REQUEST_CAMERA_PERMISSION, false)) {
            intent.removeExtra(EXTRA_REQUEST_CAMERA_PERMISSION)
            if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA)
            } else {
                runTorchAfterPermission()
            }
        }
        handlePendingRecordingStart()
    }

    fun openFloatBallIconPicker() = openFloatBallStaticPicker()

    fun openFloatBallStaticPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            REQUEST_FLOAT_ICON_STATIC
        )
    }

    fun openFloatBallIconCrop(mode: AppPrefs.FloatBallIconMode) {
        val uri = if (mode == AppPrefs.FloatBallIconMode.GIF) AppPrefs.floatBallGifUri(this)
        else AppPrefs.floatBallStaticUris(this).firstOrNull()
        if (uri.isNullOrBlank()) return
        startActivityForResult(Intent(this, FloatBallIconCropActivity::class.java).apply {
            putExtra(FloatBallIconCropActivity.EXTRA_MODE, if (mode == AppPrefs.FloatBallIconMode.GIF) "gif" else "static")
            putExtra(FloatBallIconCropActivity.EXTRA_URI, uri)
        }, REQUEST_FLOAT_ICON_CROP)
    }

    fun openFloatBallGifPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/gif"),
            REQUEST_FLOAT_ICON_GIF
        )
    }

    fun exportSettingsFile() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "panels-assistive-settings.json"),
            REQUEST_EXPORT_SETTINGS
        )
    }

    fun importSettingsFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json"),
            REQUEST_IMPORT_SETTINGS
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequestedPicker()
    }

    private fun launchRequestedPicker() {
        if (!intent.getBooleanExtra(EXTRA_OPEN_WIDGET_PICKER, false)) return
        intent.removeExtra(EXTRA_OPEN_WIDGET_PICKER)
        startActivity(
            Intent(this, WidgetPickerActivity::class.java)
                .putExtra(
                    WidgetPickerActivity.EXTRA_LANDSCAPE_LAYOUT,
                    intent.getBooleanExtra(WidgetPickerActivity.EXTRA_LANDSCAPE_LAYOUT, false)
                )
                .putExtra(
                    WidgetPickerActivity.EXTRA_PANEL_ID,
                    intent.getStringExtra(WidgetPickerActivity.EXTRA_PANEL_ID)
                )
                .putExtra(
                    WidgetPickerActivity.EXTRA_ITEM_TYPE,
                    intent.getStringExtra(WidgetPickerActivity.EXTRA_ITEM_TYPE)
                        ?: PanelItemType.WIDGET
                )
                .putExtra(
                    WidgetPickerActivity.EXTRA_POSITION,
                    intent.getIntExtra(WidgetPickerActivity.EXTRA_POSITION, -1)
                )
                .putExtra(
                    WidgetPickerActivity.EXTRA_EXPANSION_SLOT,
                    intent.getIntExtra(WidgetPickerActivity.EXTRA_EXPANSION_SLOT, -1)
                )
        )
    }

    fun requestAssistantRole() {
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
                    android.util.Log.w("MainActivity", "Assistant role picker could not be opened", error)
                } catch (error: SecurityException) {
                    android.util.Log.w("MainActivity", "Assistant role picker is not accessible", error)
                }
            }
        }
        ActionDispatcher.openAssistantSettings(this)
    }

    fun openAssistantSettings() {
        ActionDispatcher.openAssistantSettings(this)
    }

    fun isAssistantRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }
        val selectedService = Settings.Secure.getString(
            contentResolver,
            VOICE_INTERACTION_SERVICE_SETTING
        )
        return android.content.ComponentName.unflattenFromString(selectedService)
            ?.packageName == packageName
    }

    fun requestRecordingPermission() {
        val missing = recordingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing, REQUEST_RECORDING_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runTorchAfterPermission()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_RECORDING_PERMISSION) {
            if (recordingPermissions().isEmpty()) {
                handlePendingRecordingStart()
            } else {
                intent.removeExtra(EXTRA_START_BACKGROUND_RECORDING)
                intent.removeExtra(EXTRA_RECORDING_OVERLAY_REQUESTED)
                val deniedMicrophone = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                Toast.makeText(
                    this,
                    if (deniedMicrophone) R.string.microphone_permission_denied
                    else R.string.recording_notification_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Deprecated("Handles the system picker result on supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQUEST_FLOAT_ICON_STATIC || requestCode == REQUEST_FLOAT_ICON_GIF) &&
            resultCode == RESULT_OK
        ) {
            val uris = buildList {
                data?.data?.let(::add)
                data?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
                }
            }.distinct()
            if (uris.isEmpty()) return
            try {
                uris.forEach { uri ->
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                if (requestCode == REQUEST_FLOAT_ICON_GIF) {
                    AppPrefs.setFloatBallGifUri(this, uris.first().toString())
                    AppPrefs.setFloatBallGifPlayEnabled(this, false)
                    startActivityForResult(Intent(this, FloatBallIconCropActivity::class.java).apply {
                        putExtra(FloatBallIconCropActivity.EXTRA_MODE, "gif")
                        putExtra(FloatBallIconCropActivity.EXTRA_URI, uris.first().toString())
                    }, REQUEST_FLOAT_ICON_CROP)
                } else {
                    val staticUris = uris.filterNot { uri ->
                        contentResolver.getType(uri)?.equals("image/gif", ignoreCase = true) == true ||
                            uri.toString().lowercase().endsWith(".gif")
                    }
                    if (staticUris.isEmpty()) {
                        Toast.makeText(this, R.string.gif_choose_from_gif_tab, Toast.LENGTH_SHORT).show()
                        return
                    }
                    AppPrefs.setFloatBallStaticUris(this, staticUris.map(Uri::toString))
                    AppPrefs.setFloatBallSlideshowEnabled(this, false)
                    startActivityForResult(Intent(this, FloatBallIconCropActivity::class.java).apply {
                        putExtra(FloatBallIconCropActivity.EXTRA_MODE, "static")
                        putExtra(FloatBallIconCropActivity.EXTRA_URI, staticUris.first().toString())
                    }, REQUEST_FLOAT_ICON_CROP)
                }
                updateFloatBall()
            } catch (error: SecurityException) {
                Toast.makeText(this, R.string.icon_permission_failed, Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_ASSISTANT_ROLE) {
            Toast.makeText(
                this,
                if (isAssistantRoleHeld()) R.string.assistant_is_default
                else R.string.assistant_not_default,
                Toast.LENGTH_LONG
            ).show()
        } else if (requestCode == REQUEST_EXPORT_SETTINGS && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val output = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Unable to open the selected file.")
                output.bufferedWriter(Charsets.UTF_8).use {
                    it.write(AppPrefs.exportSettings(this))
                }
                Toast.makeText(this, R.string.settings_exported, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                android.util.Log.e("MainActivity", "Unable to export settings", error)
                Toast.makeText(this, R.string.settings_file_error, Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_IMPORT_SETTINGS && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Unable to open the selected file.")
                val serialized = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                AppPrefs.importSettings(this, serialized)
                if (AppPrefs.floatBallEnabled(this)) {
                    if (Settings.canDrawOverlays(this)) {
                        FloatBallService.start(this)
                    } else {
                        AppPrefs.setPendingOverlayEnable(this, true)
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    }
                } else {
                    FloatBallService.stop(this)
                }
                Toast.makeText(this, R.string.settings_imported, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                android.util.Log.e("MainActivity", "Unable to import settings", error)
                Toast.makeText(this, R.string.settings_file_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runTorchAfterPermission() {
        if (!intent.getBooleanExtra(EXTRA_RUN_TORCH_AFTER_PERMISSION, false)) return
        intent.removeExtra(EXTRA_RUN_TORCH_AFTER_PERMISSION)
        FloatBallService.toggleFlashlight(this)
    }

    private fun handlePendingRecordingStart() {
        if (!intent.getBooleanExtra(EXTRA_START_BACKGROUND_RECORDING, false)) return
        val missingPermissions = recordingPermissions()
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions, REQUEST_RECORDING_PERMISSION)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            if (intent.getBooleanExtra(EXTRA_RECORDING_OVERLAY_REQUESTED, false)) {
                intent.removeExtra(EXTRA_START_BACKGROUND_RECORDING)
                intent.removeExtra(EXTRA_RECORDING_OVERLAY_REQUESTED)
                Toast.makeText(this, R.string.recording_overlay_permission_required, Toast.LENGTH_LONG).show()
            } else {
                intent.putExtra(EXTRA_RECORDING_OVERLAY_REQUESTED, true)
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        intent.removeExtra(EXTRA_START_BACKGROUND_RECORDING)
        intent.removeExtra(EXTRA_RECORDING_OVERLAY_REQUESTED)
        try {
            BackgroundRecordingService.start(this)
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.recording_start_failed, Toast.LENGTH_LONG).show()
        } catch (error: IllegalStateException) {
            Toast.makeText(this, R.string.recording_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun recordingPermissions(): Array<String> {
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
        return missing.toTypedArray()
    }

    private fun updateFloatBall() {
        if (FloatBallService.running) {
            startService(Intent(this, FloatBallService::class.java).apply {
                action = FloatBallService.ACTION_UPDATE_FLOAT_BALL
            })
        }
    }

    @Deprecated("Use the system back callback")
    override fun onBackPressed() {
        val scroll = settingsScroll
        if (scroll != null && scroll.scrollY > dp(24)) {
            backPressCount = 0
            backExitHandler.removeCallbacks(resetBackPress)
            scroll.smoothScrollTo(0, 0)
            Toast.makeText(this, R.string.back_previous_section, Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressMillis > 1500L) {
            backPressCount = 1
        } else {
            backPressCount++
        }
        lastBackPressMillis = now
        if (backPressCount >= 2) {
            backExitHandler.removeCallbacks(resetBackPress)
            finishAffinity()
        } else {
            backExitHandler.removeCallbacks(resetBackPress)
            backExitHandler.postDelayed(resetBackPress, 1500L)
            Toast.makeText(this, R.string.back_press_again_to_exit, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        const val EXTRA_REQUEST_OVERLAY_PERMISSION =
            "com.manh.panelsassistive.extra.REQUEST_OVERLAY_PERMISSION"
        const val EXTRA_OPEN_WIDGET_PICKER =
            "com.manh.panelsassistive.extra.OPEN_WIDGET_PICKER"
        const val EXTRA_REQUEST_CAMERA_PERMISSION =
            "com.manh.panelsassistive.extra.REQUEST_CAMERA_PERMISSION"
        const val EXTRA_RUN_TORCH_AFTER_PERMISSION =
            "com.manh.panelsassistive.extra.RUN_TORCH_AFTER_PERMISSION"
        const val EXTRA_START_BACKGROUND_RECORDING =
            "com.manh.panelsassistive.extra.START_BACKGROUND_RECORDING"
        const val EXTRA_RECORDING_OVERLAY_REQUESTED =
            "com.manh.panelsassistive.extra.RECORDING_OVERLAY_REQUESTED"
        private const val REQUEST_CAMERA = 41
        private const val REQUEST_FLOAT_ICON_STATIC = 42
        private const val REQUEST_FLOAT_ICON_GIF = 43
        private const val REQUEST_FLOAT_ICON_CROP = 44
        private const val REQUEST_ASSISTANT_ROLE = 43
        private const val REQUEST_RECORDING_PERMISSION = 44
        private const val REQUEST_EXPORT_SETTINGS = 45
        private const val REQUEST_IMPORT_SETTINGS = 46
        private const val VOICE_INTERACTION_SERVICE_SETTING = "voice_interaction_service"
    }
}
