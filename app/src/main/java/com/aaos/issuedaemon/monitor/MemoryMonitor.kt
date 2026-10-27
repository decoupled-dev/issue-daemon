package com.aaos.issuedaemon.monitor

import android.app.ActivityManager
import android.content.Context
import com.aaos.issuedaemon.config.MemoryConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import com.aaos.issuedaemon.util.HealthState
import com.aaos.issuedaemon.util.round1
import com.aaos.issuedaemon.util.Shell
import java.util.concurrent.ScheduledExecutorService

/** Reports HIGH_MEMORY when available RAM stays below the threshold (or the system flags lowMemory). */
class MemoryMonitor(
    context: Context,
    private val cfg: MemoryConfig,
    scheduler: ScheduledExecutorService,
    private val sink: IssueSink,
    private val health: HealthState,
) : PeriodicMonitor(scheduler, cfg.intervalSec) {
    override val name = "memory"

    private val am = context.getSystemService(ActivityManager::class.java)
    private var consecutive = 0

    override fun poll() {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availPct = mi.availMem * 100.0 / mi.totalMem
        health.put("mem_total_mb", mi.totalMem / MB)
        health.put("mem_available_mb", mi.availMem / MB)
        health.put("mem_available_percent", round1(availPct))
        health.put("mem_low", mi.lowMemory)

        consecutive = if (availPct < cfg.availableMinPercent || mi.lowMemory) consecutive + 1 else 0
        if (consecutive < cfg.sustainedSamples) return

        val meminfo = Shell.run(listOf("dumpsys", "meminfo", "-s"), timeoutMs = 30_000)
        val top = TOP_PROC.findAll(meminfo).take(10).map { "${it.groupValues[2]} (${it.groupValues[1]}K)" }.toList()
        sink.report(
            Issue(
                type = IssueType.HIGH_MEMORY,
                severity = if (mi.lowMemory) Severity.ERROR else Severity.WARNING,
                title = "Available memory %.1f%% (%d MB of %d MB)".format(availPct, mi.availMem / MB, mi.totalMem / MB),
                source = name,
                details = "Largest processes: ${top.joinToString()}",
                attributes = mapOf(
                    "available_percent" to "%.1f".format(availPct),
                    "low_memory" to mi.lowMemory.toString(),
                    "threshold_mb" to (mi.threshold / MB).toString(),
                    "top_process" to (top.firstOrNull() ?: ""),
                ),
                attachments = mapOf("dumpsys_meminfo_at_detection.txt" to meminfo.toByteArray()),
            ),
        )
    }

    companion object {
        private const val MB = 1024 * 1024
        // Matches "Total PSS by process" / "Total RSS by process" entries: "  312,345K: system (pid 1234)"
        private val TOP_PROC = Regex("""^\s*([\d,]+)K: (\S+) \(pid (\d+)""", RegexOption.MULTILINE)
    }
}
