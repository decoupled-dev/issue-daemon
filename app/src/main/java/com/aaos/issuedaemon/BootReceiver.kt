package com.aaos.issuedaemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fallback start path for non-persistent (development) installs. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DaemonService.start(context)
    }
}
