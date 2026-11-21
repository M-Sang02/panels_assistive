package com.manh.panelsassistive

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline used by the music widget. Tapping the card opens the
 * app that is actually playing right now (MediaSession, then Audio
 * playback configs), falling back to the app assigned to this widget instance.
 * Tapping Play while idle opens that assigned app and asks it to start playing.
 */
class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(GlassWidgets.EXTRA_WIDGET_ID, -1)
        when {
            intent.getBooleanExtra(EXTRA_PLAY_NOW, false) -> playNow(this, widgetId)
            intent.getBooleanExtra(EXTRA_MEDIA_TAP, false) -> openMusicApp(this, widgetId)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_MEDIA_TAP = "com.manh.panelsassistive.extra.MEDIA_TAP"
        const val EXTRA_PLAY_NOW = "com.manh.panelsassistive.extra.PLAY_NOW"

        /**
         * Opens the app that is playing now, then the app assigned to this
         * widget, then the pinned quick-open app, then the system music app.
         */
        fun openMusicApp(context: Context, widgetId: Int = -1) {
            val playing = WidgetMedia.activePackage(context)
            if (playing != null && WidgetMedia.launch(context, playing)) return
            val assigned = AppPrefs.musicWidgetPackage(context, widgetId)
            if (assigned.isNotEmpty() && WidgetMedia.launch(context, assigned)) return
            val shortcut = AppPrefs.widgetShortcut(context)
                .takeIf { it.isNotEmpty() }
                ?.let { ComponentName.unflattenFromString(it) }
            if (shortcut != null) {
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(shortcut)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

        /** "Play music now": resume the assigned app or launch it and send play. */
        fun playNow(context: Context, widgetId: Int) {
            val assigned = AppPrefs.musicWidgetPackage(context, widgetId)
            if (assigned.isNotEmpty()) {
                WidgetMedia.playNow(context, assigned)
                return
            }
            if (WidgetMedia.activePackage(context) != null) {
                WidgetMedia.send(context, GlassWidgets.ACTION_MEDIA_TOGGLE)
            } else {
                openMusicApp(context, widgetId)
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
