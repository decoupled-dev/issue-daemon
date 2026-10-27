package com.aaos.issuedaemon.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DaemonConfigTest {
    private val defaults = JSONObject(File("src/main/assets/default_config.json").readText())

    @Test
    fun defaultsParseAndAllRulesAreValid() {
        val cfg = DaemonConfig.fromJson(defaults)
        assertFalse(cfg.server.isConfigured)
        assertEquals(12, cfg.logcat.rules.size)
        cfg.logcat.rules.forEach { Regex(it.pattern); it.processRegex?.let(::Regex) }
        assertTrue(cfg.dropbox.tags.contains("SYSTEM_TOMBSTONE"))
    }

    @Test
    fun overrideMergesDeeplyAndKeepsSiblings() {
        val override = JSONObject("""{"server":{"url":"https://ecu.local:8443"},"monitors":{"cpu":{"threshold_percent":70}}}""")
        val cfg = DaemonConfig.fromJson(deepMerge(defaults, override))
        assertEquals("https://ecu.local:8443", cfg.server.url)
        assertTrue(cfg.server.isConfigured)
        assertEquals(70.0, cfg.cpu.thresholdPercent, 0.0)
        assertEquals(3, cfg.cpu.sustainedSamples) // untouched sibling survives
        assertEquals(300L, cfg.server.heartbeatIntervalSec)
    }
}
