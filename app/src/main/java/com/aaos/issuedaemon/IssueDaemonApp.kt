package com.aaos.issuedaemon

import android.app.Application
import com.aaos.issuedaemon.config.ConfigManager

/** android:persistent="true" makes the system start this process at boot and keep it alive. */
class IssueDaemonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigManager.init(this)
        DaemonService.start(this)
    }
}
