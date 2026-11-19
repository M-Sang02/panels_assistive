package com.manh.panelsassistive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        if (AppPrefs.startOnBoot(context) &&
            AppPrefs.floatBallEnabled(context) &&
            Settings.canDrawOverlays(context)
        ) {
            FloatBallService.start(context)
        }
    }
}
