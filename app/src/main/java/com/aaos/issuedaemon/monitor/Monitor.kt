package com.aaos.issuedaemon.monitor

import android.util.Log
import com.aaos.issuedaemon.model.Issue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

fun interface IssueSink {
    fun report(issue: Issue)
}

abstract class Monitor {
    abstract val name: String
    abstract fun start()
    abstract fun stop()
}

/** A monitor that samples something every [intervalSec] seconds on the shared scheduler. */
abstract class PeriodicMonitor(
    private val scheduler: ScheduledExecutorService,
    private val intervalSec: Long,
) : Monitor() {
    private var future: ScheduledFuture<*>? = null

    override fun start() {
        future = scheduler.scheduleWithFixedDelay({
            try {
                poll()
            } catch (t: Throwable) {
                Log.e(TAG, "$name poll failed", t)
            }
        }, 1, intervalSec.coerceAtLeast(1), TimeUnit.SECONDS)
    }

    override fun stop() {
        future?.cancel(false)
        future = null
    }

    protected abstract fun poll()

    companion object {
        const val TAG = "IssueDaemon.Monitor"
    }
}
