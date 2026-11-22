package com.manh.panelsassistive

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import rikka.shizuku.Shizuku

/** Opens panel apps as floating (freeform) windows, with Shizuku as the reliable bridge. */
object FloatingApps {
    const val SHIZUKU_REQUEST_CODE = 7301
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val TAG = "FloatingApps"
    private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
    private const val NUBIA_IDENTIFIER = "_WindowReply"
    private const val HUAWEI_STACK_ID = 2
    private const val SUNSHINE_PACKAGE = "com.sunshine.freeform"
    private const val SUNSHINE_START = "com.sunshine.freeform.start_freeform"
    private const val SUNSHINE_START_MI = "com.sunshine.freeform.start_by_mi_freeform"
    private const val SUNSHINE_PACKAGE_EXTRA = "packageName"
    private const val SUNSHINE_ACTIVITY_EXTRA = "activityName"

    /** How a panel tap (and its optional long press) maps to fullscreen or freeform. */
    enum class Policy(val id: Int) {
        ALWAYS_FULLSCREEN(0),
        ALWAYS_FREE_WINDOW(1),
        FULLSCREEN_LONG_FREE_WINDOW(2),
        FREE_WINDOW_LONG_FULLSCREEN(3);

        fun usesLongPress(): Boolean =
            this == FULLSCREEN_LONG_FREE_WINDOW || this == FREE_WINDOW_LONG_FULLSCREEN

        companion object {
            fun fromId(id: Int): Policy =
                values().firstOrNull { it.id == id } ?: ALWAYS_FREE_WINDOW
        }
    }

    /**
     * OEM windowing modes. 5 is AOSP freeform; the other numbers are the
     * values ColorOS / HarmonyOS / OriginOS / FlymeOS expect in ActivityOptions.
     * Nubia asks for the system window-reply identifier instead, and Sunshine
     * is driven through its own broadcasts.
     */
    enum class Mode(val id: Int, val windowingMode: Int) {
        STANDARD(0, 5),
        COLOROS(1, 100),
        MAGICOS(2, 102),
        ORIGINOS(3, 106),
        FLYME(4, 11),
        NUBIA(5, 5),
        SUNSHINE(6, 5);

        companion object {
            fun fromId(id: Int): Mode =
                values().firstOrNull { it.id == id } ?: detectDefault()

            /** Picks the mode the current phone is most likely to understand. */
            fun detectDefault(): Mode {
                val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
                val brand = Build.BRAND.orEmpty().lowercase()
                return when {
                    manufacturer.contains("nubia") || brand.contains("nubia") ||
                        manufacturer.contains("redmagic") || brand.contains("redmagic") -> NUBIA
                    manufacturer.contains("meizu") || brand.contains("meizu") -> FLYME
                    manufacturer.contains("oppo") || manufacturer.contains("realme") ||
                        manufacturer.contains("oneplus") || brand.contains("oppo") ||
                        brand.contains("realme") || brand.contains("oneplus") -> COLOROS
                    manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
                        brand.contains("vivo") || brand.contains("iqoo") -> ORIGINOS
                    manufacturer.contains("honor") || manufacturer.contains("huawei") ||
                        brand.contains("honor") || brand.contains("huawei") -> MAGICOS
                    else -> STANDARD
                }
            }
        }
    }

    fun mode(context: Context): Mode = Mode.fromId(AppPrefs.freeformMode(context))

    fun policy(context: Context): Policy = Policy.fromId(AppPrefs.freeformPolicy(context))

    fun longPressLaunchEnabled(context: Context): Boolean =
        AppPrefs.freeformApps(context) && policy(context).usesLongPress()

    fun shouldUseFreeform(context: Context, longPress: Boolean = false): Boolean {
        if (!AppPrefs.freeformApps(context)) return false
        return when (policy(context)) {
            Policy.ALWAYS_FULLSCREEN -> false
            Policy.ALWAYS_FREE_WINDOW -> true
            Policy.FULLSCREEN_LONG_FREE_WINDOW -> longPress
            Policy.FREE_WINDOW_LONG_FULLSCREEN -> !longPress
        }
    }

    /**
     * [trigger] is the touch point that opened the panel; it is only used when
     * "follow trigger position" is on.
     */
    fun open(
        context: Context,
        component: ComponentName,
        longPress: Boolean = false,
        trigger: PointF? = null
    ): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (!shouldUseFreeform(context, longPress)) {
            context.startActivity(intent)
            return true
        }
        if (launchFreeform(context, intent, component, trigger)) return true
        if (shizukuReady() && launchWithShizuku(context, component)) return true
        context.startActivity(intent)
        return true
    }

    fun shizukuInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun shizukuReady(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Shizuku.pingBinder() &&
            (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
    } catch (_: Throwable) {
        false
    }

    /** Returns true when already granted; otherwise shows the Shizuku prompt. */
    fun requestShizukuPermission(activity: Activity): Boolean = try {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Shizuku.pingBinder() -> false
            Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
            else -> {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                false
            }
        }
    } catch (_: Throwable) {
        false
    }

    /** Opens the system freeform toggle once; harmless when it is already enabled. */
    fun enableFreeformSupport() {
        if (!shizukuReady()) return
        Thread {
            runShell("settings put global enable_freeform_support 1")
            runShell("settings put global development_force_resizable_activities 1")
        }.start()
    }

    private fun launchFreeform(
        context: Context,
        intent: Intent,
        component: ComponentName,
        trigger: PointF?
    ): Boolean {
        val chosen = mode(context)
        return try {
            if (chosen == Mode.SUNSHINE && launchSunshine(context, component)) return true
            if (chosen == Mode.NUBIA) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    intent.identifier = NUBIA_IDENTIFIER
                }
                context.startActivity(intent, Bundle())
                return true
            }
            context.startActivity(intent, optionsBundle(context, chosen, trigger))
            true
        } catch (error: Exception) {
            Log.w(TAG, "Freeform launch failed (${chosen.name})", error)
            false
        }
    }

    /**
     * Launch options for the chosen ROM: windowing mode, the Huawei stack id and
     * the size/position rectangle. The bundle key is set as a second channel for
     * platforms where the hidden setter is stripped.
     */
    fun optionsBundle(context: Context, mode: Mode, trigger: PointF?): Bundle {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            options.setLaunchBounds(launchBounds(context, mode, trigger))
        }
        applyWindowingMode(options, mode.windowingMode)
        if (mode == Mode.MAGICOS) applyStackId(options, HUAWEI_STACK_ID)
        val bundle = options.toBundle() ?: Bundle()
        if (bundle.getInt(KEY_WINDOWING_MODE, -1) == -1) {
            bundle.putInt(KEY_WINDOWING_MODE, mode.windowingMode)
        }
        return bundle
    }

    /** Size/position rectangle from the saved percentages, with ROM compensations. */
    fun launchBounds(context: Context, mode: Mode, trigger: PointF?): Rect {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)
        val landscape = screenWidth > screenHeight
        val statusBar = statusBarHeight(context)
        val bottomMargin = maxOf(statusBar, (126 * metrics.density).toInt())
        val layout = AppPrefs.freeformLayout(context, landscape)
        val width = (screenWidth * layout.width / 100f).toInt().coerceAtLeast(120)
        val height = (screenHeight * layout.height / 100f).toInt()
            .coerceIn(120, (screenHeight - statusBar - bottomMargin).coerceAtLeast(120))
        val maxLeft = (screenWidth - width).coerceAtLeast(0)
        val maxTop = (screenHeight - height - bottomMargin).coerceAtLeast(statusBar)
        var left = (screenWidth * layout.left / 100f).toInt().coerceIn(0, maxLeft)
        var top = (screenHeight * layout.top / 100f).toInt().coerceIn(statusBar, maxTop)
        if (AppPrefs.freeformFollowTrigger(context) && trigger != null) {
            val edge = (16 * metrics.density).toInt()
            left = if (trigger.x < screenWidth / 2f) edge else screenWidth - width - edge
            top = (trigger.y - height / 2f).toInt()
        }
        left = left.coerceIn(0, maxLeft)
        top = top.coerceIn(statusBar, maxTop)
        if (mode == Mode.STANDARD && isMiui13Plus(context)) {
            // MIUI/HyperOS shrinks freeform windows a second time, so the bounds
            // it receives must be larger to leave the requested size on screen.
            val compensation = if (landscape) {
                miuiLandscapeCompensation(context, screenWidth, screenHeight)
            } else {
                1.42f
            }
            return Rect(
                left,
                top,
                left + (width * compensation).toInt(),
                top + (height * compensation).toInt()
            )
        }
        return Rect(left, top, left + width, top + height)
    }

    private fun launchSunshine(context: Context, component: ComponentName): Boolean {
        if (!isPackageInstalled(context, SUNSHINE_PACKAGE)) return false
        val action = Intent(SUNSHINE_START).setPackage(SUNSHINE_PACKAGE)
            .putExtra(SUNSHINE_PACKAGE_EXTRA, component.packageName)
            .putExtra(SUNSHINE_ACTIVITY_EXTRA, component.className)
        val miAction = Intent(SUNSHINE_START_MI).setPackage(SUNSHINE_PACKAGE)
            .putExtra(SUNSHINE_PACKAGE_EXTRA, component.packageName)
            .putExtra(SUNSHINE_ACTIVITY_EXTRA, component.className)
        return runCatching {
            context.sendBroadcast(action)
            context.sendBroadcast(miAction)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Sunshine broadcast failed for ${component.packageName}", error)
            false
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)

    private fun launchWithShizuku(context: Context, component: ComponentName): Boolean {
        val target = mode(context)
        val command = "am start-activity --windowingMode ${target.windowingMode} -n " +
            component.packageName + "/" + component.className
        Thread { runShell(command) }.start()
        return true
    }

    private fun applyWindowingMode(options: ActivityOptions, windowingMode: Int) {
        try {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType!!)
                .invoke(options, windowingMode)
        } catch (_: Throwable) {
            // Hidden API unavailable; the bundle key stays as the fallback channel.
        }
    }

    private fun applyStackId(options: ActivityOptions, stackId: Int) {
        try {
            ActivityOptions::class.java
                .getMethod("setLaunchStackId", Int::class.javaPrimitiveType!!)
                .invoke(options, stackId)
        } catch (_: Throwable) {
        }
    }

    private fun runShell(command: String) {
        try {
            Shizuku.newProcess(arrayOf("sh", "-c", command), null, null).waitFor()
        } catch (_: Throwable) {
        }
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id <= 0) return 0
        return runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(0)
    }

    private fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
            brand.contains("redmi") || brand.contains("poco")
    }

    /** MIUI 13+ / HyperOS; unknown Xiaomi builds are treated as modern. */
    private fun isMiui13Plus(context: Context): Boolean {
        if (!isXiaomiDevice()) return false
        val version = systemProperty("ro.miui.ui.version.name")
        if (version.isBlank()) return true
        val number = Regex("\\d+").find(version)?.value?.toIntOrNull() ?: return true
        return number >= 13
    }

    /**
     * MIUI keeps ~0.70x of the requested width in portrait (compensation 1.42).
     * In landscape a narrow phone is squeezed further to ~0.55x (1.8), while
     * tablets and folded inner screens stay at 1.42.
     */
    private fun miuiLandscapeCompensation(context: Context, screenWidth: Int, screenHeight: Int): Float {
        val minSide = minOf(screenWidth, screenHeight)
        val maxSide = maxOf(screenWidth, screenHeight)
        if (minSide <= 0) return 1.42f
        val isLarge = minSide >= 1600 ||
            (context.resources.configuration.screenLayout and
                android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >=
            android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE ||
            maxSide.toFloat() / minSide < 1.6f
        return if (isLarge) 1.42f else 1.8f
    }

    private fun systemProperty(name: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        get.invoke(null, name, "") as? String
    }.getOrNull().orEmpty()
}
