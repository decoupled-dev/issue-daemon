package com.aaos.issuedaemon.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Severity { INFO, WARNING, ERROR, CRITICAL }

enum class IssueType {
    HIGH_CPU,
    HIGH_MEMORY,
    LOW_STORAGE,
    THERMAL,
    APP_CRASH,
    NATIVE_CRASH,
    ANR,
    DEAD_OBJECT,
    OUT_OF_MEMORY,
    LOW_MEMORY_KILL,
    BINDER_FAILURE,
    SYSTEM_SERVER_CRASH,
    WATCHDOG,
    SYSTEM_RESTART,
    CAR_SERVICE_DIED,
    ABNORMAL_BOOT,
    LOG_PATTERN,
    MANUAL,
}

/** A single detected problem plus the evidence that a monitor captured at detection time. */
data class Issue(
    val type: IssueType,
    val severity: Severity,
    val title: String,
    val source: String,
    val process: String? = null,
    val details: String = "",
    val attributes: Map<String, String> = emptyMap(),
    /** Extra files for the report bundle (name -> content), e.g. a dropbox entry or logcat context. */
    val attachments: Map<String, ByteArray> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
) {
    /** Issues sharing this key are merged (within the settle window) or suppressed (within the cooldown). */
    val dedupKey: String
        get() = listOfNotNull(
            type.name,
            process,
            attributes[ATTR_RULE].takeIf { type == IssueType.LOG_PATTERN },
        ).joinToString("|")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("severity", severity.name)
        put("title", title)
        put("source", source)
        put("process", process ?: JSONObject.NULL)
        put("timestamp_ms", timestampMs)
        put("details", details)
        put("attributes", JSONObject(attributes))
        put("attachments", JSONArray(attachments.keys.toList()))
    }

    companion object {
        const val ATTR_RULE = "rule"
    }
}
