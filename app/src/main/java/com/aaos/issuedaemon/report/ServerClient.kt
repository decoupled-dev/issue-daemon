package com.aaos.issuedaemon.report

import com.aaos.issuedaemon.config.ServerConfig
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * HTTP client for the ECU server.
 *
 *   POST {url}/api/v1/issues     multipart/form-data: "metadata" (JSON) + "bundle" (zip)
 *   POST {url}/api/v1/heartbeat  application/json
 */
class ServerClient(private val cfg: ServerConfig, private val deviceId: () -> String) {

    /** Returns the HTTP status code; throws IOException on network failure. */
    fun uploadReport(metadata: String, bundle: File): Int {
        val boundary = "issuedaemon-" + UUID.randomUUID()
        val conn = open("/api/v1/issues")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setChunkedStreamingMode(64 * 1024)
        conn.outputStream.buffered().use { out ->
            out.part(boundary, "metadata", null, "application/json")
            out.write(metadata.toByteArray())
            out.write(CRLF)
            if (bundle.exists()) {
                out.part(boundary, "bundle", bundle.name, "application/zip")
                bundle.inputStream().use { it.copyTo(out) }
                out.write(CRLF)
            }
            out.write("--$boundary--\r\n".toByteArray())
        }
        return finish(conn)
    }

    fun sendHeartbeat(json: String): Int {
        val conn = open("/api/v1/heartbeat")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(json.toByteArray()) }
        return finish(conn)
    }

    private fun open(path: String): HttpURLConnection {
        val conn = URL(cfg.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = cfg.connectTimeoutMs
        conn.readTimeout = cfg.readTimeoutMs
        conn.setRequestProperty("X-Device-Id", deviceId())
        if (cfg.apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        return conn
    }

    private fun finish(conn: HttpURLConnection): Int = try {
        val code = conn.responseCode
        (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
        code
    } finally {
        conn.disconnect()
    }

    private fun OutputStream.part(boundary: String, name: String, filename: String?, type: String) {
        val disposition = "form-data; name=\"$name\"" + (filename?.let { "; filename=\"$it\"" } ?: "")
        write("--$boundary\r\nContent-Disposition: $disposition\r\nContent-Type: $type\r\n\r\n".toByteArray())
    }

    companion object {
        private val CRLF = "\r\n".toByteArray()
    }
}
