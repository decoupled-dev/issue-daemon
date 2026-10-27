package com.aaos.issuedaemon.report

import android.util.Log
import com.aaos.issuedaemon.config.ReportingConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.monitor.IssueSink
import com.aaos.issuedaemon.util.DeviceInfo
import com.aaos.issuedaemon.util.HealthState
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Entry point for every detected issue. All state lives on one thread:
 *
 *  - settle window: issues with the same dedup key arriving within settle_sec are merged
 *    (e.g. a crash seen in logcat and then filed to dropbox becomes one report with both).
 *  - cooldown: the same key is not reported again for cooldown_sec (suppressed count is sent next time).
 *  - rate limit: at most max_reports_per_hour bundles are produced.
 *
 * Accepted issues get an evidence bundle built and are committed to the queue for upload.
 */
class IssueDispatcher(
    private val cfg: ReportingConfig,
    private val collector: EvidenceCollector,
    private val queue: ReportQueue,
    private val uploader: Uploader,
    private val device: DeviceInfo,
    private val health: HealthState,
) : IssueSink {

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "issue-dispatcher") }
    private val pending = HashMap<String, Issue>()
    private val lastSent = HashMap<String, Long>()
    private val suppressed = HashMap<String, Int>()
    private val sentTimes = ArrayDeque<Long>()

    override fun report(issue: Issue) {
        if (executor.isShutdown) return
        executor.execute { accept(issue) }
    }

    /** Lets in-flight settle windows finish and bundles get written, but accepts nothing new. */
    fun shutdown() = executor.shutdown()

    private fun accept(issue: Issue) {
        val key = issue.dedupKey
        pending[key]?.let {
            pending[key] = merge(it, issue)
            return
        }
        val now = System.currentTimeMillis()
        val last = lastSent[key]
        if (last != null && now - last < cfg.cooldownSec * 1000) {
            suppressed[key] = (suppressed[key] ?: 0) + 1
            return
        }
        Log.i(TAG, "Detected ${issue.severity} ${issue.type}: ${issue.title}")
        pending[key] = issue
        executor.schedule({ finalize(key) }, cfg.settleSec, TimeUnit.SECONDS)
    }

    private fun finalize(key: String) {
        val issue = pending.remove(key) ?: return
        val now = System.currentTimeMillis()
        lastSent[key] = now

        while (sentTimes.isNotEmpty() && now - sentTimes.first() > HOUR_MS) sentTimes.removeFirst()
        if (sentTimes.size >= cfg.maxReportsPerHour) {
            Log.w(TAG, "Rate limit reached, dropping ${issue.type} report")
            return
        }
        sentTimes.addLast(now)

        val metadata = JSONObject().apply {
            put("schema_version", 1)
            put("report_id", issue.id)
            put("issue", issue.toJson())
            put("suppressed_since_last_report", suppressed.remove(key) ?: 0)
            put("device", device.toJson())
            put("health", health.toJson())
            put("created_at_ms", now)
        }
        val tmp = queue.newTempFile(issue.id)
        try {
            collector.buildBundle(issue, metadata, tmp)
            Log.i(TAG, "Queued report ${issue.id} (${tmp.length() / 1024} KB)")
            queue.commit(issue.id, metadata.toString(), tmp)
            uploader.trigger()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build report for ${issue.type}", t)
            tmp.delete()
        }
    }

    private fun merge(a: Issue, b: Issue): Issue = a.copy(
        severity = maxOf(a.severity, b.severity),
        source = if (b.source in a.source.split("+")) a.source else "${a.source}+${b.source}",
        // Prefer the richer description (dropbox entries beat a single logcat line).
        details = if (b.details.length > a.details.length) b.details else a.details,
        title = if (b.source == "dropbox") b.title else a.title,
        attributes = b.attributes + a.attributes,
        attachments = a.attachments + b.attachments,
    )

    companion object {
        private const val TAG = "IssueDaemon.Dispatch"
        private const val HOUR_MS = 3_600_000L
    }
}
