package com.aaos.issuedaemon.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs platform tools (logcat, dumpsys, getprop, ...) with a timeout and an output cap. */
object Shell {

    fun run(argv: List<String>, timeoutMs: Long = 15_000, maxBytes: Int = 4 * 1024 * 1024): String {
        val proc = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return "<failed to start '${argv.joinToString(" ")}': ${e.message}>"
        }
        val buf = ByteArrayOutputStream()
        var truncated = false
        val reader = thread(name = "shell-reader", isDaemon = true) {
            try {
                proc.inputStream.use { input ->
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        // Keep draining past the cap so the child never blocks on a full pipe.
                        val room = maxBytes - buf.size()
                        if (room > 0) buf.write(chunk, 0, minOf(n, room)) else truncated = true
                    }
                }
            } catch (_: IOException) {
            }
        }
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) proc.destroyForcibly()
        reader.join(2_000)
        return buildString {
            append(buf.toString(Charsets.UTF_8.name()))
            if (truncated) append("\n<truncated at $maxBytes bytes>")
            if (!finished) append("\n<timed out after ${timeoutMs}ms>")
        }
    }

    fun getprop(name: String): String = run(listOf("getprop", name), timeoutMs = 3_000).trim()

    fun readFile(path: String): String? = try {
        File(path).readText()
    } catch (e: Exception) {
        null
    }
}
