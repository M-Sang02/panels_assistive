package com.manh.panelsassistive

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Circle to Search bridge. The whole feature (highlighting, delay, vibration,
 * which assistant is used) lives inside MiCTS; Panels only hides its own
 * overlays and launches MiCTS, which starts Circle to Search on its own.
 * https://github.com/parallelcc/MiCTS
 */
object MiCts {
    const val PACKAGE = "com.parallelc.micts"
    const val GITHUB_URL = "https://github.com/parallelcc/MiCTS"
    private const val SETTINGS_CLASS = "$PACKAGE.ui.activity.SettingsActivity"
    private const val TAG = "MiCts"

    fun isInstalled(context: Context): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE, 0)
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Hides the floating ball / open panel first (a clean screen is what MiCTS
     * scans), then starts the scan. Runs through the service when it is alive;
     * otherwise MiCTS is launched directly.
     */
    fun trigger(context: Context) {
        if (FloatBallService.running) {
            FloatBallService.start(context, FloatBallService.ACTION_SCREEN_SEARCH)
            return
        }
        if (!isInstalled(context)) {
            promptInstall(context)
            return
        }
        if (!launch(context)) {
            Toast.makeText(context, R.string.micts_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Launching MiCTS is the trigger: its launcher activity starts the scan. */
    fun launch(context: Context): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (error: Exception) {
        Log.e(TAG, "Unable to launch MiCTS", error)
        false
    }

    /** MiCTS settings (trigger options, delay, vibration). */
    fun openSettings(context: Context): Boolean {
        val explicit = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(PACKAGE, SETTINGS_CLASS))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (explicit) return true
        return runCatching {
            context.startActivity(
                Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES")
                    .setPackage(PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    fun openGithub(context: Context) {
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) {
            Toast.makeText(context, R.string.micts_browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptInstall(context: Context) {
        val intent = Intent(context, MiCtsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to show the MiCTS reminder", error)
            Toast.makeText(context, R.string.micts_required_message, Toast.LENGTH_LONG).show()
        }
    }

    /** Empty glass sheet with the download action; callers add their own buttons. */
    fun requiredSheet(context: Context): GlassDialogs.Sheet {
        val sheet = GlassDialogs.sheet(
            context,
            context.getString(R.string.micts_required_title),
            context.getString(R.string.micts_required_message),
            cancelLabel = null
        )
        sheet.content.addView(GlassUi.actionButton(
            context,
            context.getString(R.string.micts_download_github),
            prominent = true
        ) {
            sheet.dismiss()
            openGithub(context)
        })
        return sheet
    }

    /**
     * Asked while assigning the action: without MiCTS this action does nothing,
     * so remind the user but still let them keep the assignment.
     */
    fun remindIfNeeded(context: Context, action: String, onContinue: () -> Unit) {
        if (ActionDispatcher.selectedActionType(action) != ActionDispatcher.CIRCLE_SEARCH ||
            isInstalled(context)
        ) {
            onContinue()
            return
        }
        val sheet = requiredSheet(context)
        sheet.content.addView(GlassUi.actionButton(
            context,
            context.getString(R.string.micts_assign_anyway)
        ) {
            sheet.dismiss()
            onContinue()
        })
        sheet.content.addView(GlassUi.actionButton(
            context,
            context.getString(R.string.micts_assign_later)
        ) {
            sheet.dismiss()
        })
        sheet.show()
    }
}

/**
 * Transparent reminder opened when the action runs before MiCTS is installed.
 * Mirrors the in-app glass dialog instead of silently doing nothing.
 */
class MiCtsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MiCts.isInstalled(this)) {
            if (!MiCts.launch(this)) {
                Toast.makeText(this, R.string.micts_launch_failed, Toast.LENGTH_SHORT).show()
            }
            finish()
            overridePendingTransition(0, 0)
            return
        }
        val sheet = MiCts.requiredSheet(this)
        sheet.content.addView(GlassUi.actionButton(
            this,
            getString(R.string.micts_close)
        ) {
            sheet.dismiss()
            finish()
        })
        sheet.onDismiss = { finish() }
        sheet.show()
    }
}
