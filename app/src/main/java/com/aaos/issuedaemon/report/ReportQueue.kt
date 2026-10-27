package com.aaos.issuedaemon.report

import android.util.Log
import java.io.File

/**
 * On-disk store-and-forward queue so reports survive reboots and offline periods.
 * Each report is a pair: <seq>_<id>.json (metadata) + <seq>_<id>.zip (bundle).
 */
class ReportQueue(private val dir: File, private val maxBytes: Long) {

    data class Entry(val meta: File, val bundle: File) {
        val name: String get() = meta.nameWithoutExtension
    }

    private val tmpDir = File(dir, "tmp")

    init {
        dir.mkdirs()
        tmpDir.mkdirs()
        tmpDir.listFiles()?.forEach { it.delete() } // half-written bundles from a previous run
    }

    fun newTempFile(id: String): File = File(tmpDir, "$id.zip")

    @Synchronized
    fun commit(id: String, metadata: String, tmpBundle: File) {
        val base = "%013d_%s".format(System.currentTimeMillis(), id)
        val bundle = File(dir, "$base.zip")
        if (!tmpBundle.renameTo(bundle)) tmpBundle.copyTo(bundle, overwrite = true).also { tmpBundle.delete() }
        File(dir, "$base.json").writeText(metadata) // written last: marks the entry complete
        enforceLimit()
    }

    @Synchronized
    fun pending(): List<Entry> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }
            .map { Entry(it, File(dir, it.nameWithoutExtension + ".zip")) }

    @Synchronized
    fun remove(entry: Entry) {
        entry.meta.delete()
        entry.bundle.delete()
    }

    fun size(): Int = pending().size

    private fun enforceLimit() {
        val entries = pending()
        var total = entries.sumOf { it.meta.length() + it.bundle.length() }
        for (e in entries) {
            if (total <= maxBytes) break
            Log.w(TAG, "Queue over ${maxBytes / (1024 * 1024)} MB, dropping oldest report ${e.name}")
            total -= e.meta.length() + e.bundle.length()
            remove(e)
        }
    }

    companion object {
        private const val TAG = "IssueDaemon.Queue"
    }
}
