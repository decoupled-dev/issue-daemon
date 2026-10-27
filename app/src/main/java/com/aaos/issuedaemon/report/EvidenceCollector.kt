package com.aaos.issuedaemon.report

import com.aaos.issuedaemon.config.ReportingConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.util.Shell
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the report bundle (zip) for an issue:
 *
 *   issue.json            metadata (issue, device, health snapshot)
 *   attachments/...       evidence captured by the monitor (dropbox entry, tombstone, logcat context)
 *   logcat/<buffer>.txt    recent logcat from all / crash / events buffers
 *   dumpsys/<service>.txt  configured dumpsys services
 *   system/<name>         configured commands (top, ps, getprop, /proc/meminfo, ...)
 */
class EvidenceCollector(private val cfg: ReportingConfig) {

    fun buildBundle(issue: Issue, metadata: JSONObject, out: File) {
        val timeoutMs = cfg.commandTimeoutSec * 1000
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.put("issue.json", metadata.toString(2).toByteArray())

            issue.attachments.forEach { (name, bytes) -> zip.put("attachments/$name", bytes) }

            if (cfg.includeLogs) {
                val tail = cfg.logcatLines.toString()
                zip.put("logcat/all.txt", run(listOf("logcat", "-d", "-b", "all", "-v", "threadtime", "-t", tail), timeoutMs))
                zip.put("logcat/crash.txt", run(listOf("logcat", "-d", "-b", "crash", "-v", "threadtime"), timeoutMs))
                zip.put("logcat/events.txt", run(listOf("logcat", "-d", "-b", "events", "-v", "threadtime", "-t", tail), timeoutMs))
            }

            for (service in cfg.dumpsys) {
                val argv = listOf("dumpsys") + service.split(" ").filter { it.isNotEmpty() }
                zip.put("dumpsys/${service.replace(UNSAFE, "_")}.txt", run(argv, timeoutMs))
            }

            for (cmd in cfg.commands) {
                zip.put("system/${cmd.name.replace(UNSAFE, "_")}", run(cmd.argv, timeoutMs))
            }
        }
    }

    private fun run(argv: List<String>, timeoutMs: Long): ByteArray =
        Shell.run(argv, timeoutMs, cfg.maxOutputBytes).toByteArray()

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
    }
}
