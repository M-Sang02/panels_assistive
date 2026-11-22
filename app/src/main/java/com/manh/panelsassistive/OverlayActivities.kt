package com.manh.panelsassistive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline used by the music widget. Tapping the card opens the
 * app that is actually playing right now (MediaSession, then Audio
 * playback configs), falling back to the music app shared by every music
 * widget. Tapping Play while idle opens that app and asks it to start playing.
 */
class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            intent.getBooleanExtra(EXTRA_OPEN_WRITE_SETTINGS, false) -> openWriteSettings(this)
            intent.getBooleanExtra(EXTRA_PLAY_NOW, false) -> playNow(this)
            intent.getBooleanExtra(EXTRA_MEDIA_TAP, false) -> openMusicApp(this)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_MEDIA_TAP = "com.manh.panelsassistive.extra.MEDIA_TAP"
        const val EXTRA_PLAY_NOW = "com.manh.panelsassistive.extra.PLAY_NOW"
        const val EXTRA_OPEN_WRITE_SETTINGS =
            "com.manh.panelsassistive.extra.OPEN_WRITE_SETTINGS"

        /** Brightness widget tapped without the WRITE_SETTINGS grant. */
        fun openWriteSettings(context: Context) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(intent) }.isSuccess ||
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
            if (!opened) return
            // Rebinding once the user flips the toggle keeps the widget usable
            // without waiting for the 30-minute provider refresh.
            val app = context.applicationContext
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            longArrayOf(1500L, 5000L, 15000L).forEach { delay ->
                handler.postDelayed({ GlassWidgets.refreshLevelWidgets(app) }, delay)
            }
        }

        /**
         * Opens the app whose session the card is showing — even when that
         * session is paused — then the shared music app, then the phone's
         * default music app. The clock's quick-open shortcut is deliberately
         * not used here: it may point at a chat app (Zalo) and must not
         * hijack the music card when nothing is playing.
         */
        fun openMusicApp(context: Context) {
            // "Không mở app" assignment: the card stays purely informational.
            if (AppPrefs.musicWidgetNoApp(context)) return
            val assigned = AppPrefs.musicWidgetPackage(context)
            // A paused session still present on the notification shade wins
            // over the widget's default app.
            val shown = WidgetMedia.shownPackage(context, assigned)
            if (shown != null && WidgetMedia.launch(context, shown)) return
            if (assigned.isNotEmpty() && WidgetMedia.launch(context, assigned)) return
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
        fun playNow(context: Context) {
            val assigned = AppPrefs.musicWidgetPackage(context)
            if (assigned.isNotEmpty()) {
                WidgetMedia.playNow(context, assigned)
                return
            }
            if (WidgetMedia.activePackage(context) != null) {
                WidgetMedia.send(context, GlassWidgets.ACTION_MEDIA_TOGGLE)
            } else {
                openMusicApp(context)
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

/**
 * Catches the plain `android.intent.action.ASSIST` intent some launchers send
 * when the active VoiceInteractionService is not bound yet (right after a
 * reboot, an app update or when the process was killed by the ROM). Without a
 * handler in this app that fallback lands on Google Assistant, even though
 * Panels still owns the assistant role. Here the configured assistant action
 * runs exactly like it does from the voice session.
 */
class AssistFallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppPrefs.assistantReplacementEnabled(this)) {
            try {
                ActionDispatcher.dispatch(this, AppPrefs.assistantReplacementAction(this))
            } catch (error: Exception) {
                Log.e("PanelsAssistant", "Assistant fallback action failed", error)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
