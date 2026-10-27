package com.aaos.issuedaemon.monitor

import android.util.Log
import com.aaos.issuedaemon.config.CpuConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import com.aaos.issuedaemon.util.HealthState
import com.aaos.issuedaemon.util.round1
import com.aaos.issuedaemon.util.Shell
import java.io.File
import java.util.concurrent.ScheduledExecutorService

/**
 * Reports HIGH_CPU when total CPU usage stays above the threshold for N consecutive samples.
 * Uses /proc/stat deltas when readable, otherwise falls back to `dumpsys cpuinfo` (needs DUMP).
 */
class CpuMonitor(
    private val cfg: CpuConfig,
    scheduler: ScheduledExecutorService,
    private val sink: IssueSink,
    private val health: HealthState,
) : PeriodicMonitor(scheduler, cfg.intervalSec) {
    override val name = "cpu"

    private var prevTotal = 0L
    private var prevIdle = 0L
    private var procStatReadable = true
    private var consecutive = 0
    private var warnedUnavailable = false

    override fun poll() {
        val usage = (if (procStatReadable) readProcStat() else null) ?: readDumpsysTotal()
        if (usage == null) {
            if (!procStatReadable && !warnedUnavailable) {
                // Untrusted (non-system) installs can read neither /proc/stat nor the cpuinfo service.
                Log.w(TAG, "No CPU usage source available; run as android.uid.system for CPU monitoring")
                warnedUnavailable = true
            }
            return
        }
        health.put("cpu_percent", round1(usage))

        consecutive = if (usage >= cfg.thresholdPercent) consecutive + 1 else 0
        if (consecutive < cfg.sustainedSamples) return

        val cpuinfo = Shell.run(listOf("dumpsys", "cpuinfo"))
        val offenders = TOP_LINE.findAll(cpuinfo).take(5).map { "${it.groupValues[3]} (${it.groupValues[1]}%)" }.toList()
        sink.report(
            Issue(
                type = IssueType.HIGH_CPU,
                severity = if (usage >= 95) Severity.ERROR else Severity.WARNING,
                title = "CPU usage %.0f%% for %d consecutive samples".format(usage, consecutive),
                source = name,
                details = "Top consumers: ${offenders.joinToString()}",
                attributes = mapOf(
                    "cpu_percent" to "%.1f".format(usage),
                    "threshold_percent" to cfg.thresholdPercent.toString(),
                    "top_process" to (offenders.firstOrNull() ?: ""),
                ),
                attachments = mapOf("dumpsys_cpuinfo_at_detection.txt" to cpuinfo.toByteArray()),
            ),
        )
    }

    /** Returns busy % since the previous sample, or null on the first sample / if unreadable. */
    private fun readProcStat(): Double? {
        val line = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        } catch (e: Exception) {
            procStatReadable = false // SELinux denies /proc/stat on some builds.
            return null
        }
        val f = line.trim().split(WS).drop(1).take(8).map { it.toLong() }
        if (f.size < 5) return null
        val idle = f[3] + f[4]
        val total = f.sum()
        val dTotal = total - prevTotal
        val dIdle = idle - prevIdle
        val first = prevTotal == 0L
        prevTotal = total
        prevIdle = idle
        if (first || dTotal <= 0) return null
        return (dTotal - dIdle) * 100.0 / dTotal
    }

    private fun readDumpsysTotal(): Double? =
        TOTAL_LINE.find(Shell.run(listOf("dumpsys", "cpuinfo")))?.groupValues?.get(1)?.toDoubleOrNull()

    companion object {
        private val WS = Regex("\\s+")
        private val TOTAL_LINE = Regex("""^\s*([\d.]+)% TOTAL:""", RegexOption.MULTILINE)
        private val TOP_LINE = Regex("""^\s*([\d.]+)% (\d+)/([^:]+):""", RegexOption.MULTILINE)
    }
}
