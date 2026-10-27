package com.aaos.issuedaemon.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.DropBoxManager
import android.util.Log
import com.aaos.issuedaemon.config.DropBoxConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * DropBox is where the framework files crashes, ANRs (with traces), tombstones and watchdog dumps.
 * Each new entry becomes an issue with the full entry attached to the report bundle.
 */
class DropBoxMonitor(
    private val context: Context,
    private val cfg: DropBoxConfig,
    private val executor: Executor,
    private val sink: IssueSink,
) : Monitor() {
    override val name = "dropbox"

    private val dbm = context.getSystemService(DropBoxManager::class.java)
    private val lastSeen = HashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val tag = intent.getStringExtra(DropBoxManager.EXTRA_TAG) ?: return
            if (tag in TAG_TYPES && tag in cfg.tags) executor.execute { drain(tag) }
        }
    }

    override fun start() {
        val now = System.currentTimeMillis()
        cfg.tags.forEach { lastSeen[it] = now } // only report entries created from now on
        context.registerReceiver(receiver, IntentFilter(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED), Context.RECEIVER_EXPORTED)
    }

    override fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Broadcasts can be rate limited/coalesced, so read every entry since the last one seen. */
    private fun drain(tag: String) {
        while (true) {
            val since = synchronized(lastSeen) { lastSeen[tag] ?: 0L }
            val entry = try {
                dbm.getNextEntry(tag, since)
            } catch (e: SecurityException) {
                Log.e(TAG, "No permission to read dropbox (READ_LOGS + PACKAGE_USAGE_STATS/PEEK_DROPBOX_DATA)", e)
                return
            } ?: return
            entry.use {
                synchronized(lastSeen) { lastSeen[tag] = it.timeMillis }
                handle(tag, it)
            }
        }
    }

    private fun handle(tag: String, entry: DropBoxManager.Entry) {
        val bytes = readBytes(entry)
        val isText = entry.flags and DropBoxManager.IS_TEXT != 0
        val text = if (isText) String(bytes, Charsets.UTF_8) else ""
        val (type, severity) = TAG_TYPES.getValue(tag)

        val process = PROCESS_HEADER.find(text)?.groupValues?.get(1)
            ?: TOMBSTONE_PROCESS.find(text)?.groupValues?.get(1)
            ?: if (tag.startsWith("SYSTEM_TOMBSTONE_PROTO")) TombstoneProto.processName(bytes) else null
        val exception = EXCEPTION_LINE.find(text)?.groupValues?.get(1)?.take(200)
        val subject = SUBJECT_HEADER.find(text)?.groupValues?.get(1)

        sink.report(
            Issue(
                type = type,
                severity = severity,
                title = listOfNotNull("$tag:", process, exception ?: subject).joinToString(" "),
                source = name,
                process = process,
                timestampMs = entry.timeMillis,
                details = text.take(4000),
                attributes = mapOf("dropbox_tag" to tag, "dropbox_time" to entry.timeMillis.toString()),
                attachments = mapOf("dropbox_${tag}_${entry.timeMillis}.${if (isText) "txt" else "bin"}" to bytes),
            ),
        )
    }

    private fun readBytes(entry: DropBoxManager.Entry): ByteArray {
        val input = entry.inputStream ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        input.use {
            val buf = ByteArray(16 * 1024)
            while (out.size() < cfg.maxEntryBytes) {
                val n = it.read(buf, 0, minOf(buf.size, cfg.maxEntryBytes - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "IssueDaemon.DropBox"

        private val TAG_TYPES: Map<String, Pair<IssueType, Severity>> = mapOf(
            "data_app_crash" to (IssueType.APP_CRASH to Severity.ERROR),
            "system_app_crash" to (IssueType.APP_CRASH to Severity.ERROR),
            "system_server_crash" to (IssueType.SYSTEM_SERVER_CRASH to Severity.CRITICAL),
            "data_app_anr" to (IssueType.ANR to Severity.ERROR),
            "system_app_anr" to (IssueType.ANR to Severity.ERROR),
            "system_server_anr" to (IssueType.ANR to Severity.CRITICAL),
            "data_app_native_crash" to (IssueType.NATIVE_CRASH to Severity.ERROR),
            "system_app_native_crash" to (IssueType.NATIVE_CRASH to Severity.ERROR),
            "system_server_native_crash" to (IssueType.NATIVE_CRASH to Severity.CRITICAL),
            "SYSTEM_TOMBSTONE" to (IssueType.NATIVE_CRASH to Severity.ERROR),
            "SYSTEM_TOMBSTONE_PROTO" to (IssueType.NATIVE_CRASH to Severity.ERROR),
            "SYSTEM_TOMBSTONE_PROTO_WITH_HEADERS" to (IssueType.NATIVE_CRASH to Severity.ERROR),
            "system_server_watchdog" to (IssueType.WATCHDOG to Severity.CRITICAL),
            "SYSTEM_RESTART" to (IssueType.SYSTEM_RESTART to Severity.CRITICAL),
            "system_server_wtf" to (IssueType.LOG_PATTERN to Severity.WARNING),
            "system_app_wtf" to (IssueType.LOG_PATTERN to Severity.WARNING),
        )

        private val PROCESS_HEADER = Regex("""^Process: (\S+)""", RegexOption.MULTILINE)
        private val TOMBSTONE_PROCESS = Regex(""">>> (\S+) <<<""")
        private val SUBJECT_HEADER = Regex("""^Subject: (.+)$""", RegexOption.MULTILINE)
        private val EXCEPTION_LINE = Regex("""^([\w.$]+(?:Exception|Error)\b.*)$""", RegexOption.MULTILINE)
    }
}
