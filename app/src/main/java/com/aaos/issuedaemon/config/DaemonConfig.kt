package com.aaos.issuedaemon.config

import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import org.json.JSONArray
import org.json.JSONObject

data class ServerConfig(
    val url: String,
    val apiKey: String,
    val deviceId: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val heartbeatIntervalSec: Long,
    val retryMinSec: Long,
    val retryMaxSec: Long,
) {
    val isConfigured: Boolean
        get() = url.startsWith("http://") || url.startsWith("https://")
}

data class CpuConfig(val enabled: Boolean, val intervalSec: Long, val thresholdPercent: Double, val sustainedSamples: Int)

data class MemoryConfig(val enabled: Boolean, val intervalSec: Long, val availableMinPercent: Double, val sustainedSamples: Int)

data class StorageConfig(val enabled: Boolean, val intervalSec: Long, val path: String, val freeMinPercent: Double)

data class ThermalConfig(val enabled: Boolean, val minStatus: Int)

data class BootConfig(val enabled: Boolean, val abnormalPattern: String)

data class DropBoxConfig(val enabled: Boolean, val tags: List<String>, val maxEntryBytes: Int)

data class LogRule(
    val id: String,
    val pattern: String,
    val type: IssueType,
    val severity: Severity,
    val processRegex: String?,
    val enabled: Boolean,
)

data class LogcatConfig(
    val enabled: Boolean,
    val buffers: List<String>,
    val contextLinesBefore: Int,
    val contextLinesAfter: Int,
    val contextWindowMs: Long,
    val rules: List<LogRule>,
)

data class Command(val name: String, val argv: List<String>)

data class ReportingConfig(
    val cooldownSec: Long,
    val settleSec: Long,
    val maxReportsPerHour: Int,
    val maxQueueMb: Long,
    val includeLogs: Boolean,
    val logcatLines: Int,
    val commandTimeoutSec: Long,
    val maxOutputBytes: Int,
    val dumpsys: List<String>,
    val commands: List<Command>,
)

data class DaemonConfig(
    val server: ServerConfig,
    val cpu: CpuConfig,
    val memory: MemoryConfig,
    val storage: StorageConfig,
    val thermal: ThermalConfig,
    val carServiceEnabled: Boolean,
    val boot: BootConfig,
    val dropbox: DropBoxConfig,
    val logcat: LogcatConfig,
    val reporting: ReportingConfig,
    /** The merged JSON this config was parsed from (reported by the STATUS action). */
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(root: JSONObject): DaemonConfig {
            val server = root.obj("server")
            val monitors = root.obj("monitors")
            val cpu = monitors.obj("cpu")
            val mem = monitors.obj("memory")
            val storage = monitors.obj("storage")
            val thermal = monitors.obj("thermal")
            val boot = monitors.obj("boot")
            val dropbox = monitors.obj("dropbox")
            val logcat = monitors.obj("logcat")
            val reporting = root.obj("reporting")

            return DaemonConfig(
                server = ServerConfig(
                    url = server.optString("url").trim(),
                    apiKey = server.optString("api_key"),
                    deviceId = server.optString("device_id"),
                    connectTimeoutMs = server.optInt("connect_timeout_ms", 10_000),
                    readTimeoutMs = server.optInt("read_timeout_ms", 30_000),
                    heartbeatIntervalSec = server.optLong("heartbeat_interval_sec", 300),
                    retryMinSec = server.optLong("retry_min_sec", 30),
                    retryMaxSec = server.optLong("retry_max_sec", 1800),
                ),
                cpu = CpuConfig(
                    enabled = cpu.optBoolean("enabled", true),
                    intervalSec = cpu.optLong("interval_sec", 10),
                    thresholdPercent = cpu.optDouble("threshold_percent", 85.0),
                    sustainedSamples = cpu.optInt("sustained_samples", 3),
                ),
                memory = MemoryConfig(
                    enabled = mem.optBoolean("enabled", true),
                    intervalSec = mem.optLong("interval_sec", 15),
                    availableMinPercent = mem.optDouble("available_min_percent", 10.0),
                    sustainedSamples = mem.optInt("sustained_samples", 2),
                ),
                storage = StorageConfig(
                    enabled = storage.optBoolean("enabled", true),
                    intervalSec = storage.optLong("interval_sec", 300),
                    path = storage.optString("path", "/data"),
                    freeMinPercent = storage.optDouble("free_min_percent", 10.0),
                ),
                thermal = ThermalConfig(
                    enabled = thermal.optBoolean("enabled", true),
                    minStatus = thermal.optInt("min_status", 3),
                ),
                carServiceEnabled = monitors.obj("car_service").optBoolean("enabled", true),
                boot = BootConfig(
                    enabled = boot.optBoolean("enabled", true),
                    abnormalPattern = boot.optString("abnormal_pattern", "(?i)(panic|watchdog)"),
                ),
                dropbox = DropBoxConfig(
                    enabled = dropbox.optBoolean("enabled", true),
                    tags = dropbox.strings("tags"),
                    maxEntryBytes = dropbox.optInt("max_entry_bytes", 2 * 1024 * 1024),
                ),
                logcat = LogcatConfig(
                    enabled = logcat.optBoolean("enabled", true),
                    buffers = logcat.strings("buffers").ifEmpty { listOf("main", "system", "crash") },
                    contextLinesBefore = logcat.optInt("context_lines_before", 30),
                    contextLinesAfter = logcat.optInt("context_lines_after", 60),
                    contextWindowMs = logcat.optLong("context_window_ms", 3000),
                    rules = logcat.objects("rules").mapNotNull(::parseRule),
                ),
                reporting = ReportingConfig(
                    cooldownSec = reporting.optLong("cooldown_sec", 300),
                    settleSec = reporting.optLong("settle_sec", 5),
                    maxReportsPerHour = reporting.optInt("max_reports_per_hour", 30),
                    maxQueueMb = reporting.optLong("max_queue_mb", 200),
                    includeLogs = reporting.optBoolean("include_logs", true),
                    logcatLines = reporting.optInt("logcat_lines", 5000),
                    commandTimeoutSec = reporting.optLong("command_timeout_sec", 20),
                    maxOutputBytes = reporting.optInt("max_output_bytes", 4 * 1024 * 1024),
                    dumpsys = reporting.strings("dumpsys"),
                    commands = reporting.objects("commands").map {
                        Command(it.getString("name"), it.strings("argv"))
                    }.filter { it.argv.isNotEmpty() },
                ),
                raw = root,
            )
        }

        private fun parseRule(o: JSONObject): LogRule? {
            val type = runCatching { IssueType.valueOf(o.optString("type", "LOG_PATTERN")) }.getOrNull() ?: return null
            val severity = runCatching { Severity.valueOf(o.optString("severity", "WARNING")) }.getOrNull() ?: return null
            val pattern = o.optString("pattern").takeIf { it.isNotEmpty() } ?: return null
            return LogRule(
                id = o.optString("id", pattern),
                pattern = pattern,
                type = type,
                severity = severity,
                processRegex = o.optString("process_regex").takeIf { it.isNotEmpty() },
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

internal fun JSONObject.obj(name: String): JSONObject = optJSONObject(name) ?: JSONObject()

internal fun JSONObject.strings(name: String): List<String> {
    val arr = optJSONArray(name) ?: return emptyList()
    return (0 until arr.length()).map { arr.getString(it) }
}

internal fun JSONObject.objects(name: String): List<JSONObject> {
    val arr = optJSONArray(name) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

/** Deep-merges [overlay] into a copy of [base]. Objects merge recursively; arrays and scalars replace. */
internal fun deepMerge(base: JSONObject, overlay: JSONObject): JSONObject {
    val out = JSONObject(base.toString())
    for (key in overlay.keys()) {
        val o = overlay.get(key)
        val b = out.opt(key)
        out.put(key, if (o is JSONObject && b is JSONObject) deepMerge(b, o) else o)
    }
    return out
}

