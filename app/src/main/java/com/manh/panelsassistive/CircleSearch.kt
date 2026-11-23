package com.manh.panelsassistive

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Starts Circle to Search through the system omni entry point, the same voice
 * interaction call the assistant gesture performs. The scan overlay is drawn
 * by the system; Panels only hides its own windows and waits out the delay.
 */
object CircleSearch {
    private const val TAG = "CircleSearch"
    private const val ENTRY_POINT = 1
    private const val SESSION_FLAGS = 7
    private const val OMNI_PACKAGE = "hyperOS_home"

    fun trigger(context: Context) {
        if (FloatBallService.running) {
            FloatBallService.start(context, FloatBallService.ACTION_SCREEN_SEARCH)
            return
        }
        fire(context.applicationContext, AppPrefs.circleSearchDelay(context))
    }

    fun fire(context: Context, delayMs: Int, onResult: ((Boolean) -> Unit)? = null) {
        val run = {
            // With Panels as the default assistant the session would loop back
            // into Panels; Circle to Search needs Google as the assistant.
            val triggered = !panelsIsAssistant(context) && invokeOmni()
            if (triggered) {
                if (AppPrefs.circleSearchVibrate(context)) vibrate(context)
            } else {
                Toast.makeText(context, R.string.circle_search_failed, Toast.LENGTH_SHORT).show()
            }
            onResult?.invoke(triggered)
        }
        if (delayMs > 0) {
            Handler(Looper.getMainLooper()).postDelayed(Runnable { run() }, delayMs.toLong())
        } else {
            run()
        }
    }

    private fun panelsIsAssistant(context: Context): Boolean = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            "voice_interaction_service"
        )?.contains(context.packageName) == true
    } catch (error: Exception) {
        Log.w(TAG, "Unable to read the assistant service", error)
        false
    }

    @SuppressLint("PrivateApi")
    private fun invokeOmni(): Boolean = try {
        val args = Bundle().apply {
            putLong("invocation_time_ms", SystemClock.elapsedRealtime())
            putInt("omni.entry_point", ENTRY_POINT)
        }
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "voiceinteraction") as? IBinder
        if (binder == null) {
            false
        } else {
            val stub = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")
            val service = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HiddenApiBypass.invoke(
                    stub,
                    service,
                    "showSessionFromSession",
                    null,
                    args,
                    SESSION_FLAGS,
                    OMNI_PACKAGE
                )
            } else {
                HiddenApiBypass.invoke(
                    stub,
                    service,
                    "showSessionFromSession",
                    null,
                    args,
                    SESSION_FLAGS
                )
            }
            opened as? Boolean ?: false
        }
    } catch (error: Throwable) {
        Log.w(TAG, "Unable to start Circle to Search", error)
        false
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                    attributes
                )
            } else {
                vibrator.vibrate(longArrayOf(0, 1, 75, 76), -1, attributes)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to vibrate", error)
        }
    }
}
