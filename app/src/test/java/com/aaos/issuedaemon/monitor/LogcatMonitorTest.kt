package com.aaos.issuedaemon.monitor

import com.aaos.issuedaemon.config.DaemonConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

class LogcatMonitorTest {
    private val issues = mutableListOf<Issue>()
    private lateinit var monitor: LogcatMonitor

    @Before
    fun setUp() {
        val json = JSONObject(File("src/main/assets/default_config.json").readText())
        val cfg = DaemonConfig.fromJson(json).logcat
        monitor = LogcatMonitor(cfg, Executors.newSingleThreadScheduledExecutor()) { issues.add(it) }
    }

    private fun feed(vararg lines: String) {
        lines.forEach(monitor::onLine)
        Thread.sleep(3_100) // past context_window_ms
        monitor.flushExpired()
    }

    @Test
    fun javaCrashCapturesProcessAndStack() {
        feed(
            "10-27 13:37:19.600  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main",
            "10-27 13:37:19.600  4321  4321 E AndroidRuntime: Process: com.example.media, PID: 4321",
            "10-27 13:37:19.600  4321  4321 E AndroidRuntime: java.lang.IllegalStateException: boom",
            "10-27 13:37:19.600  4321  4321 E AndroidRuntime: \tat com.example.media.Player.play(Player.kt:42)",
            "10-27 13:37:19.601   999   999 I Other   : unrelated line from another pid",
        )
        val crash = issues.single { it.type == IssueType.APP_CRASH }
        assertEquals("com.example.media", crash.process)
        assertTrue(crash.details.contains("Player.kt:42"))
        assertTrue(!crash.details.contains("unrelated"))
    }

    @Test
    fun deadObjectNativeCrashAnrAndLmk() {
        feed(
            "10-27 13:40:00.000  1500  1600 E CarAudioService: android.os.DeadObjectException: Transaction failed",
            "10-27 13:40:01.000  2000  2010 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 2010 (RenderThread), pid 2000 (com.example.nav)",
            "10-27 13:40:02.000   600   700 E ActivityManager: ANR in com.example.radio (com.example.radio/.MainActivity)",
            "10-27 13:40:03.000   300   300 I lowmemorykiller: Kill 'com.example.weather' (2222), uid 10111, oom_score_adj 900",
        )
        val byType = issues.associateBy { it.type }
        assertTrue(IssueType.DEAD_OBJECT in byType)
        assertEquals("com.example.nav", byType.getValue(IssueType.NATIVE_CRASH).process)
        assertEquals("com.example.radio", byType.getValue(IssueType.ANR).process)
        assertEquals("com.example.weather", byType.getValue(IssueType.LOW_MEMORY_KILL).process)
    }

    @Test
    fun systemServerCrashIsNotAlsoAnAppCrash() {
        feed("10-27 13:41:00.000   600   600 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: main")
        assertEquals(listOf(IssueType.SYSTEM_SERVER_CRASH), issues.map { it.type })
    }

    @Test
    fun repeatedPatternInOneTraceIsReportedOnce() {
        feed(
            "10-27 13:42:00.000  1500  1600 E Svc     : android.os.DeadObjectException",
            "10-27 13:42:00.000  1500  1600 E Svc     : Caused by: android.os.DeadObjectException",
        )
        assertEquals(1, issues.count { it.type == IssueType.DEAD_OBJECT })
    }
}
