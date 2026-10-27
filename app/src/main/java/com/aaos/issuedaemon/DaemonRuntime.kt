package com.aaos.issuedaemon

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.aaos.issuedaemon.config.DaemonConfig
import com.aaos.issuedaemon.monitor.BootMonitor
import com.aaos.issuedaemon.monitor.CarServiceMonitor
import com.aaos.issuedaemon.monitor.CpuMonitor
import com.aaos.issuedaemon.monitor.DropBoxMonitor
import com.aaos.issuedaemon.monitor.LogcatMonitor
import com.aaos.issuedaemon.monitor.MemoryMonitor
import com.aaos.issuedaemon.monitor.Monitor
import com.aaos.issuedaemon.monitor.StorageMonitor
import com.aaos.issuedaemon.monitor.ThermalMonitor
import com.aaos.issuedaemon.report.EvidenceCollector
import com.aaos.issuedaemon.report.IssueDispatcher
import com.aaos.issuedaemon.report.ReportQueue
import com.aaos.issuedaemon.report.ServerClient
import com.aaos.issuedaemon.report.Uploader
import com.aaos.issuedaemon.util.DeviceInfo
import com.aaos.issuedaemon.util.HealthState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/** Wires monitors -> dispatcher -> queue -> uploader for one configuration. Rebuilt on config change. */
class DaemonRuntime(context: Context, val config: DaemonConfig) {

    private val storage = context.createDeviceProtectedStorageContext()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2) { Thread(it, "issue-monitor") }

    val health = HealthState()
    val device = DeviceInfo(context, config.server.deviceId)
    val queue = ReportQueue(File(storage.filesDir, "reports"), config.reporting.maxQueueMb * 1024 * 1024)
    val uploader = Uploader(config.server, queue, ServerClient(config.server) { device.deviceId }, ::heartbeatJson)
    val dispatcher = IssueDispatcher(config.reporting, EvidenceCollector(config.reporting), queue, uploader, device, health)

    private val monitors: List<Monitor> = buildList {
        if (config.cpu.enabled) add(CpuMonitor(config.cpu, scheduler, dispatcher, health))
        if (config.memory.enabled) add(MemoryMonitor(context, config.memory, scheduler, dispatcher, health))
        if (config.storage.enabled) add(StorageMonitor(config.storage, scheduler, dispatcher, health))
        if (config.thermal.enabled) add(ThermalMonitor(context, config.thermal, scheduler, dispatcher, health))
        if (config.carServiceEnabled) add(CarServiceMonitor(context, dispatcher, health))
        if (config.boot.enabled) add(BootMonitor(storage, config.boot, device, scheduler, dispatcher, health))
        if (config.dropbox.enabled) add(DropBoxMonitor(context, config.dropbox, scheduler, dispatcher))
        if (config.logcat.enabled) add(LogcatMonitor(config.logcat, scheduler, dispatcher))
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = uploader.trigger()
    }

    fun start() {
        Log.i(TAG, "Starting monitors: ${monitors.joinToString { it.name }}")
        uploader.start()
        monitors.forEach { m ->
            runCatching { m.start() }.onFailure { Log.e(TAG, "Monitor ${m.name} failed to start", it) }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        uploader.trigger()
        active = this
    }

    fun stop() {
        if (active === this) active = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        monitors.forEach { m -> runCatching { m.stop() } }
        scheduler.shutdownNow()
        dispatcher.shutdown()
        uploader.stop()
    }

    fun status(): JSONObject = JSONObject().apply {
        put("server_url", config.server.url)
        put("monitors", JSONArray(monitors.map { it.name }))
        put("queued_reports", queue.size())
        put("last_upload", uploader.lastResult)
        put("device", device.toJson())
        put("health", health.toJson())
    }

    private fun heartbeatJson(): String = JSONObject().apply {
        put("type", "heartbeat")
        put("timestamp_ms", System.currentTimeMillis())
        put("device", device.toJson())
        put("health", health.toJson())
        put("queued_reports", queue.size())
    }.toString()

    companion object {
        private const val TAG = "IssueDaemon"

        @Volatile
        var active: DaemonRuntime? = null
            private set
    }
}
