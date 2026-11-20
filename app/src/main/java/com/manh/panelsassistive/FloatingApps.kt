package com.manh.panelsassistive

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import rikka.shizuku.Shizuku

/** Opens panel apps as floating (freeform) windows, with Shizuku as the reliable bridge. */
object FloatingApps {
    const val SHIZUKU_REQUEST_CODE = 7301
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun open(context: Context, component: ComponentName): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (!AppPrefs.freeformApps(context)) {
            context.startActivity(intent)
            return true
        }
        if (launchWithOptions(context, intent)) return true
        if (shizukuReady() && launchWithShizuku(component)) return true
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

    private fun launchWithOptions(context: Context, intent: Intent): Boolean {
        return try {
            val options = ActivityOptions.makeBasic()
            options.setLaunchBounds(windowBounds(context))
            val method = ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType!!)
            method.invoke(options, 5)
            context.startActivity(intent, options.toBundle())
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun launchWithShizuku(component: ComponentName): Boolean {
        val command = "am start-activity --windowingMode 5 -n " +
            component.packageName + "/" + component.className
        Thread { runShell(command) }.start()
        return true
    }

    private fun runShell(command: String) {
        try {
            Shizuku.newProcess(arrayOf("sh", "-c", command), null, null).waitFor()
        } catch (_: Throwable) {
        }
    }

    private fun windowBounds(context: Context): Rect {
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.72f).toInt()
        val height = (metrics.heightPixels * 0.58f).toInt()
        val left = (metrics.widthPixels - width) / 2
        val top = (metrics.heightPixels - height) / 3
        return Rect(left, top, left + width, top + height)
    }
}
