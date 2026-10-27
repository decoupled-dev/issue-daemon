package com.aaos.issuedaemon.report

import android.util.Log
import com.aaos.issuedaemon.config.ServerConfig
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Drains the [ReportQueue] to the ECU server with exponential backoff, and sends heartbeats. */
class Uploader(
    private val cfg: ServerConfig,
    private val queue: ReportQueue,
    private val client: ServerClient,
    private val heartbeat: () -> String,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "issue-uploader") }
    private var retry: ScheduledFuture<*>? = null
    private var backoffSec = cfg.retryMinSec

    @Volatile var lastResult: String = "none"
        private set

    fun start() {
        executor.scheduleWithFixedDelay(::sendHeartbeat, 30, cfg.heartbeatIntervalSec.coerceAtLeast(30), TimeUnit.SECONDS)
        // Periodic sweep in case a trigger was missed (e.g. network came back without a callback).
        executor.scheduleWithFixedDelay(::flush, 5, 300, TimeUnit.SECONDS)
    }

    fun stop() = executor.shutdown()

    /** Request an upload attempt now (new report queued, network available, manual flush). */
    fun trigger() {
        if (!executor.isShutdown) executor.execute(::flush)
    }

    private fun flush() {
        if (!cfg.isConfigured) {
            lastResult = "server url not configured; ${queue.size()} report(s) waiting"
            return
        }
        for (entry in queue.pending()) {
            val code = try {
                client.uploadReport(entry.meta.readText(), entry.bundle)
            } catch (e: IOException) {
                Log.w(TAG, "Upload of ${entry.name} failed: $e")
                lastResult = "network error: ${e.message}"
                scheduleRetry()
                return
            }
            when {
                code in 200..299 -> {
                    Log.i(TAG, "Uploaded ${entry.name} (HTTP $code)")
                    queue.remove(entry)
                    lastResult = "ok (HTTP $code)"
                }
                code in 400..499 && code != 408 && code != 429 -> {
                    // The server rejected this report permanently; don't block the queue on it.
                    Log.e(TAG, "Server rejected ${entry.name} (HTTP $code), dropping")
                    queue.remove(entry)
                    lastResult = "rejected (HTTP $code)"
                }
                else -> {
                    Log.w(TAG, "Upload of ${entry.name} got HTTP $code, will retry")
                    lastResult = "retrying (HTTP $code)"
                    scheduleRetry()
                    return
                }
            }
        }
        backoffSec = cfg.retryMinSec
        retry?.cancel(false)
        retry = null
    }

    private fun scheduleRetry() {
        if (retry?.isDone == false) return
        Log.i(TAG, "Retrying uploads in ${backoffSec}s")
        retry = executor.schedule(::flush, backoffSec, TimeUnit.SECONDS)
        backoffSec = (backoffSec * 2).coerceAtMost(cfg.retryMaxSec)
    }

    private fun sendHeartbeat() {
        if (!cfg.isConfigured) return
        try {
            val code = client.sendHeartbeat(heartbeat())
            if (code !in 200..299) Log.w(TAG, "Heartbeat got HTTP $code")
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed: $e")
        }
    }

    companion object {
        private const val TAG = "IssueDaemon.Upload"
    }
}
