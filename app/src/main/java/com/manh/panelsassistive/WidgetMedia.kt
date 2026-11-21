package com.manh.panelsassistive

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent

/**
 * Reads the active MediaSession (once the user has granted notification access)
 * and sends transport commands to it. Falls back to AudioManager media keys on
 * devices/ROMs that expose no session, so the widget never crashes when
 * metadata is unavailable.
 */
object WidgetMedia {
    data class Snapshot(
        val packageName: String?,
        val title: String?,
        val artist: String?,
        val art: Bitmap?,
        val isPlaying: Boolean,
        val hasSession: Boolean
    )

    fun notificationAccessEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(':').any { flat ->
            ComponentName.unflattenFromString(flat)?.packageName == context.packageName
        }
    }

    fun openNotificationAccess(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Jump straight to our own toggle when the ROM supports it.
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                    ComponentName(context, MediaNotificationListener::class.java).flattenToString()
                )
                .addFlags(flags)
            if (runCatching { context.startActivity(detail) }.isSuccess) return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(flags)
            )
        }.onFailure { Log.w(TAG, "Unable to open notification access", it) }
    }

    /** Snapshot for the widget card; never throws when sessions are unavailable. */
    fun snapshot(context: Context, preferredPackage: String? = null): Snapshot {
        val sessions = activeSessions(context)
        try {
            val controller = choose(sessions, preferredPackage)
            if (controller != null) {
                val metadata = controller.metadata
                val state = controller.playbackState?.state
                val playing = state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING
                return Snapshot(
                    packageName = controller.packageName,
                    title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                    artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    art = artwork(context, metadata),
                    isPlaying = playing,
                    hasSession = true
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to read the media session", error)
        } finally {
            sessions.forEach { release(it) }
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val playing = audio?.isMusicActive == true
        return Snapshot(
            packageName = activePackage(context),
            title = null,
            artist = null,
            art = null,
            isPlaying = playing,
            hasSession = false
        )
    }

    /** Package of the app that is actually playing audio right now. */
    fun activePackage(context: Context): String? {
        val sessions = activeSessions(context)
        try {
            sessions.firstOrNull { isPlaying(it) }?.let { return it.packageName }
        } finally {
            sessions.forEach { release(it) }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.activePlaybackConfigurations?.forEach { config ->
                // isActive()/getClientUid() are hidden framework methods.
                val active = config.javaClass.getMethod("isActive").invoke(config) as? Boolean
                if (active != true) return@forEach
                val usage = config.audioAttributes.usage
                if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME) {
                    return@forEach
                }
                val uid = config.javaClass.getMethod("getClientUid").invoke(config) as? Int
                    ?: return@forEach
                val packageName = context.packageManager.getNameForUid(uid)
                    ?.substringBefore(":")
                    ?.takeIf { it != context.packageName }
                if (packageName != null) return packageName
            }
            null
        } catch (error: Exception) {
            Log.w(TAG, "Unable to resolve the playing package", error)
            null
        }
    }

    /** Sends a transport action to the active session, or a media key as fallback. */
    fun send(context: Context, action: String, preferredPackage: String? = null): Boolean {
        val sessions = activeSessions(context)
        val controller = choose(sessions, preferredPackage)
        if (controller != null) {
            try {
                when (action) {
                    GlassWidgets.ACTION_MEDIA_PREV -> controller.transportControls.skipToPrevious()
                    GlassWidgets.ACTION_MEDIA_NEXT -> controller.transportControls.skipToNext()
                    GlassWidgets.ACTION_MEDIA_TOGGLE ->
                        if (isPlaying(controller)) {
                            controller.transportControls.pause()
                        } else {
                            controller.transportControls.play()
                        }
                    else -> return false
                }
                return true
            } catch (error: Exception) {
                Log.w(TAG, "MediaSession command failed: $action", error)
            } finally {
                sessions.forEach { release(it) }
            }
        } else {
            sessions.forEach { release(it) }
        }
        val key = when (action) {
            GlassWidgets.ACTION_MEDIA_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            GlassWidgets.ACTION_MEDIA_TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            GlassWidgets.ACTION_MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return false
        }
        dispatchKey(context, key)
        return true
    }

    /** Resumes the assigned app when it already has a paused session. */
    fun resume(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val sessions = activeSessions(context)
        try {
            val controller = sessions.firstOrNull { it.packageName == packageName } ?: return false
            controller.transportControls.play()
            return true
        } catch (error: Exception) {
            Log.w(TAG, "Unable to resume $packageName", error)
            return false
        } finally {
            sessions.forEach { release(it) }
        }
    }

    /**
     * Opens the assigned music app and nudges it to start playing. If a paused
     * session already exists it is resumed directly instead of relaunching.
     */
    fun playNow(context: Context, packageName: String) {
        if (packageName.isEmpty()) return
        if (resume(context, packageName)) {
            GlassWidgets.refreshSoon(context, GlassMusicWidget::class.java)
            Handler(Looper.getMainLooper()).postDelayed({
                GlassWidgets.refresh(context, GlassMusicWidget::class.java)
            }, 800L)
            return
        }
        if (!launch(context, packageName)) return
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            dispatchKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
            GlassWidgets.refreshSoon(context, GlassMusicWidget::class.java)
        }, 1500L)
        handler.postDelayed({
            GlassWidgets.refresh(context, GlassMusicWidget::class.java)
        }, 2600L)
    }

    fun launch(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun dispatchKey(context: Context, code: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun label(context: Context, packageName: String): String {
        if (packageName.isEmpty()) return ""
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    /** Launcher apps offered by the assignment dialogs, sorted by label. */
    fun launcherApps(context: Context): List<Pair<String, String>> =
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info -> label(context, info.packageName) to info.packageName }
            .sortedBy { it.first.lowercase() }

    private fun activeSessions(context: Context): List<MediaController> {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return emptyList()
        val listener = ComponentName(context, MediaNotificationListener::class.java)
        return try {
            manager.getActiveSessions(listener)
        } catch (error: Exception) {
            // SecurityException until the user enables notification access.
            emptyList()
        }
    }

    private fun choose(
        sessions: List<MediaController>,
        preferredPackage: String?
    ): MediaController? {
        val preferred = preferredPackage?.takeIf { it.isNotEmpty() }
        return sessions.firstOrNull { it.packageName == preferred && isPlaying(it) }
            ?: sessions.firstOrNull { isPlaying(it) }
            ?: sessions.firstOrNull { it.packageName == preferred }
            ?: sessions.firstOrNull { it.metadata != null }
            ?: sessions.firstOrNull()
    }

    private fun isPlaying(controller: MediaController): Boolean {
        val state = controller.playbackState?.state
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    private fun release(controller: MediaController) {
        runCatching { controller.release() }
    }

    private fun artwork(context: Context, metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?.let { return it }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val bytes = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to load album art", error)
            null
        }
    }

    private const val TAG = "WidgetMedia"
}

/**
 * Notification listener used only to (a) obtain MediaSessionManager access and
 * (b) refresh the music card when a media notification appears, changes track
 * or disappears. No polling; it is idle unless media notifications change.
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        GlassWidgets.refresh(applicationContext, GlassMusicWidget::class.java)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (isMedia(sbn)) scheduleRefresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (isMedia(sbn)) scheduleRefresh()
    }

    private fun isMedia(sbn: StatusBarNotification?): Boolean {
        val notification = sbn?.notification ?: return false
        return notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }

    private fun scheduleRefresh() {
        val context = applicationContext
        GlassWidgets.refreshSoon(context, GlassMusicWidget::class.java)
        Handler(Looper.getMainLooper()).postDelayed({
            GlassWidgets.refresh(context, GlassMusicWidget::class.java)
        }, 900L)
    }
}
