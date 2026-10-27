package com.aaos.issuedaemon.monitor

import android.util.Log
import com.aaos.issuedaemon.config.LogcatConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import com.aaos.issuedaemon.util.Shell
import java.io.IOException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Streams `logcat` and matches each line against configurable regex rules (FATAL EXCEPTION,
 * DeadObjectException, ANR, Fatal signal, lmkd kills, ...). On a match it keeps collecting the
 * following lines from the same pid (stack trace) before raising the issue.
 */
class LogcatMonitor(
    private val cfg: LogcatConfig,
    private val scheduler: ScheduledExecutorService,
    private val sink: IssueSink,
) : Monitor() {
    override val name = "logcat"

    private class Rule(val id: String, val regex: Regex, val type: IssueType, val severity: Severity, val processRegex: Regex?)

    private class Pending(val rule: Rule, val pid: Int, val matchLine: String, val before: List<String>, val deadline: Long) {
        val after = ArrayList<String>()
    }

    private val rules: List<Rule> = cfg.rules.filter { it.enabled }.mapNotNull { r ->
        try {
            Rule(r.id, Regex(r.pattern), r.type, r.severity, r.processRegex?.let(::Regex))
        } catch (e: Exception) {
            Log.e(TAG, "Skipping invalid rule ${r.id}", e)
            null
        }
    }

    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val pending = ArrayList<Pending>()
    private val myPid = android.os.Process.myPid()

    @Volatile private var running = false
    @Volatile private var process: Process? = null
    private var reader: Thread? = null
    private var flusher: ScheduledFuture<*>? = null

    override fun start() {
        running = true
        reader = thread(name = "logcat-monitor", isDaemon = true) { readLoop() }
        flusher = scheduler.scheduleWithFixedDelay(::flushExpired, 1, 1, TimeUnit.SECONDS)
    }

    override fun stop() {
        running = false
        process?.destroy()
        reader?.interrupt()
        flusher?.cancel(false)
    }

    private fun readLoop() {
        var backoffMs = 1_000L
        while (running) {
            try {
                // -T 1: start at the tail instead of replaying the whole buffer.
                val argv = listOf("logcat", "-v", "threadtime", "-T", "1", "-b", cfg.buffers.joinToString(","))
                val p = ProcessBuilder(argv).redirectErrorStream(true).start()
                process = p
                p.inputStream.bufferedReader().use { input ->
                    while (running) {
                        val line = input.readLine() ?: break
                        onLine(line)
                        backoffMs = 1_000L
                    }
                }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "logcat stream failed: $e")
            } finally {
                process?.destroy()
            }
            if (!running) break
            Log.w(TAG, "logcat exited, restarting in ${backoffMs}ms")
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(60_000)
        }
    }

    internal fun onLine(line: String) {
        val pid = LINE.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (pid == myPid) return // never react to our own logging

        val ready = ArrayList<Pending>()
        synchronized(lock) {
            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.pid == pid) p.after.add(line)
                if (p.after.size >= cfg.contextLinesAfter) {
                    it.remove()
                    ready.add(p)
                }
            }
            for (rule in rules) {
                if (!rule.regex.containsMatchIn(line)) continue
                // A stack trace repeats the pattern; keep one pending match per rule+pid.
                if (pending.any { it.rule === rule && it.pid == pid }) continue
                pending.add(Pending(rule, pid, line, ring.toList(), System.currentTimeMillis() + cfg.contextWindowMs))
            }
            ring.addLast(line)
            while (ring.size > cfg.contextLinesBefore) ring.removeFirst()
        }
        ready.forEach(::emit)
    }

    internal fun flushExpired() {
        val now = System.currentTimeMillis()
        val ready = synchronized(lock) {
            pending.filter { it.deadline <= now }.also { pending.removeAll(it.toSet()) }
        }
        ready.forEach(::emit)
    }

    private fun emit(p: Pending) {
        try {
            val own = (listOf(p.matchLine) + p.after).joinToString("\n")
            val context = (p.before + p.matchLine + p.after).joinToString("\n")
            val process = p.rule.processRegex?.find(own)?.groupValues?.getOrNull(1)
                ?: processName(p.pid)
            val message = LINE.matchEntire(p.matchLine)?.groupValues?.get(5) ?: p.matchLine
            sink.report(
                Issue(
                    type = p.rule.type,
                    severity = p.rule.severity,
                    title = "${p.rule.id}: ${message.trim().take(160)}",
                    source = name,
                    process = process,
                    details = own.take(4000),
                    attributes = mapOf(Issue.ATTR_RULE to p.rule.id, "pid" to p.pid.toString()),
                    attachments = mapOf("logcat_context_${p.rule.id}.txt" to context.toByteArray()),
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to emit ${p.rule.id}", t)
        }
    }

    private fun processName(pid: Int): String? {
        if (pid <= 0) return null
        return Shell.readFile("/proc/$pid/cmdline")?.substringBefore('\u0000')?.takeIf { it.isNotBlank() }
            ?: "pid:$pid"
    }

    companion object {
        private const val TAG = "IssueDaemon.Logcat"
        // threadtime: "10-27 13:21:00.123  1234  1250 E Tag     : message"
        private val LINE = Regex("""^\S+\s+\S+\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*: (.*)$""")
    }
}
