package com.manh.panelsassistive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == AppPrefs.ACTION_RESTORE_IMAGE_WIDGETS) {
            // The launcher confirmed the pin: fill the new card with saved data.
            AppPrefs.applyPendingImageWidgets(context)
            GlassWidgets.refreshAll(context.applicationContext)
            return
        }
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // After an update, re-send every card so a launcher that cached a
            // "problem loading widget" panel gets fresh RemoteViews at once.
            WidgetLevelObserver.ensure(context.applicationContext)
            GlassWidgets.refreshAll(context.applicationContext)
            return
        }
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        // Widgets are re-hosted after a reboot; revive their live level tracking.
        WidgetLevelObserver.ensure(context.applicationContext)
        GlassWidgets.refreshLevelWidgets(context.applicationContext)
        GlassWidgets.refreshMusicWidgets(context.applicationContext)
        if (AppPrefs.startOnBoot(context) &&
            AppPrefs.floatBallEnabled(context) &&
            Settings.canDrawOverlays(context)
        ) {
            FloatBallService.start(context)
        }
    }
}
