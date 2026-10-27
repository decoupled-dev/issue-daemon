package com.aaos.issuedaemon.util

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Latest metric values published by monitors; sent with heartbeats and embedded in each report. */
class HealthState {
    private val values = ConcurrentHashMap<String, Any>()

    fun put(key: String, value: Any) {
        values[key] = value
    }

    fun toJson(): JSONObject = JSONObject().also { json ->
        values.toSortedMap().forEach { (k, v) -> json.put(k, v) }
    }
}

/** Rounds to one decimal without going through locale-dependent formatting. */
fun round1(value: Double): Double = Math.round(value * 10) / 10.0
