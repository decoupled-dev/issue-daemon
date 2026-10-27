package com.aaos.issuedaemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.aaos.issuedaemon.config.ConfigManager
import com.aaos.issuedaemon.config.DaemonConfig

/** Long-lived foreground service hosting the [DaemonRuntime]; restarts it when the config changes. */
class DaemonService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var runtime: DaemonRuntime? = null

    private val configListener: (DaemonConfig) -> Unit = { cfg -> main.post { restart(cfg) } }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        ConfigManager.init(this)
        ConfigManager.addListener(configListener)
        restart(ConfigManager.current)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        ConfigManager.removeListener(configListener)
        runtime?.stop()
        runtime = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun restart(cfg: DaemonConfig) {
        runtime?.stop()
        runtime = DaemonRuntime(applicationContext, cfg).also { it.start() }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val TAG = "IssueDaemon"
        private const val CHANNEL_ID = "issue_daemon"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            if (!isSystemUser()) return
            try {
                context.startForegroundService(Intent(context, DaemonService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start daemon service", e)
            }
        }

        /**
         * AAOS runs in headless-system-user mode: the daemon must only run once, in user 0,
         * not again inside each driver profile.
         */
        fun isSystemUser(): Boolean = android.os.Process.myUid() / PER_USER_RANGE == 0

        private const val PER_USER_RANGE = 100_000
    }
}
