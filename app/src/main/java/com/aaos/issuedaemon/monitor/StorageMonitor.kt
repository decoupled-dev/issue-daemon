package com.aaos.issuedaemon.monitor

import android.os.StatFs
import com.aaos.issuedaemon.config.StorageConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import com.aaos.issuedaemon.util.HealthState
import com.aaos.issuedaemon.util.round1
import java.util.concurrent.ScheduledExecutorService

class StorageMonitor(
    private val cfg: StorageConfig,
    scheduler: ScheduledExecutorService,
    private val sink: IssueSink,
    private val health: HealthState,
) : PeriodicMonitor(scheduler, cfg.intervalSec) {
    override val name = "storage"

    override fun poll() {
        val stat = StatFs(cfg.path)
        val freePct = stat.availableBytes * 100.0 / stat.totalBytes
        health.put("storage_free_mb", stat.availableBytes / MB)
        health.put("storage_free_percent", round1(freePct))
        if (freePct >= cfg.freeMinPercent) return

        sink.report(
            Issue(
                type = IssueType.LOW_STORAGE,
                severity = if (freePct < cfg.freeMinPercent / 2) Severity.ERROR else Severity.WARNING,
                title = "Low storage on ${cfg.path}: %.1f%% free (%d MB)".format(freePct, stat.availableBytes / MB),
                source = name,
                attributes = mapOf("path" to cfg.path, "free_percent" to "%.1f".format(freePct)),
            ),
        )
    }

    companion object {
        private const val MB = 1024 * 1024
    }
}
