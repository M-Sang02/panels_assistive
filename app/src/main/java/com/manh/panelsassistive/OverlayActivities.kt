package com.manh.panelsassistive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline used by the music widget: it resolves the app that is
 * actually playing right now (via AudioManager playback configurations, no
 * notification-listener permission needed) and opens it.
 */
class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_OPEN_MEDIA, false)) openMediaSource(this)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_OPEN_MEDIA = "com.manh.panelsassistive.extra.OPEN_MEDIA"

        fun openMediaSource(context: Context) {
            val launch = activeMediaPackage(context)
                ?.let { context.packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(launch) }.isSuccess) return
            }
            val musicApps = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(musicApps) }.isSuccess
            if (!opened) {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        private fun activeMediaPackage(context: Context): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
            return try {
                val audio = context.getSystemService(AudioManager::class.java) ?: return null
                // isActive()/getClientUid() are hidden framework methods, so read
                // them reflectively and fall back to CATEGORY_APP_MUSIC when blocked.
                for (config in audio.activePlaybackConfigurations) {
                    val active = config.javaClass.getMethod("isActive").invoke(config) as? Boolean
                    if (active != true) continue
                    val usage = config.audioAttributes.usage
                    if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME) continue
                    val uid = config.javaClass.getMethod("getClientUid").invoke(config) as? Int
                        ?: continue
                    return context.packageManager.getNameForUid(uid)
                        ?.substringBefore(":")
                        ?.takeIf { it != context.packageName }
                }
                null
            } catch (error: Exception) {
                Log.w("WidgetLaunch", "Unable to resolve the playing app", error)
                null
            }
        }
    }
}

/**
 * Some OEM ROMs (OnePlus/Oppo/Realme and others) silently ignore volume changes
 * requested by a background service. Applying the change from a briefly visible
 * transparent activity works around that restriction; the service only launches
 * it after verifying that the direct call had no effect.
 */
class VolumeApplyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stream = intent.getIntExtra(EXTRA_STREAM, AudioManager.STREAM_MUSIC)
        val value = intent.getIntExtra(EXTRA_VALUE, -1)
        if (value >= 0) {
            try {
                val audio = getSystemService(AudioManager::class.java)
                if (audio != null) {
                    audio.setStreamVolume(
                        stream,
                        value.coerceIn(0, audio.getStreamMaxVolume(stream).coerceAtLeast(1)),
                        AudioManager.FLAG_SHOW_UI
                    )
                }
            } catch (error: SecurityException) {
                Log.e("VolumeApply", "Unable to apply volume $stream=$value", error)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_STREAM = "com.manh.panelsassistive.extra.VOLUME_STREAM"
        private const val EXTRA_VALUE = "com.manh.panelsassistive.extra.VOLUME_VALUE"

        fun request(context: Context, stream: Int, value: Int) {
            context.startActivity(
                Intent(context, VolumeApplyActivity::class.java)
                    .putExtra(EXTRA_STREAM, stream)
                    .putExtra(EXTRA_VALUE, value)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}
