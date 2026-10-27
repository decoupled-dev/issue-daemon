package com.aaos.issuedaemon.monitor

import android.content.Context
import android.util.Log
import com.aaos.issuedaemon.config.BootConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import com.aaos.issuedaemon.util.DeviceInfo
import com.aaos.issuedaemon.util.HealthState
import com.aaos.issuedaemon.util.Shell
import java.util.concurrent.Executor

/** Once per boot: reports ABNORMAL_BOOT if the boot reason indicates a panic, watchdog reset, etc. */
class BootMonitor(
    private val storageContext: Context,
    private val cfg: BootConfig,
    private val device: DeviceInfo,
    private val executor: Executor,
    private val sink: IssueSink,
    private val health: HealthState,
) : Monitor() {
    override val name = "boot"

    override fun start() = executor.execute {
        val reason = Shell.getprop("sys.boot.reason")
        val bootloaderReason = Shell.getprop("ro.boot.bootreason")
        health.put("boot_reason", reason)

        val prefs = storageContext.getSharedPreferences("boot_monitor", Context.MODE_PRIVATE)
        if (prefs.getString(KEY_BOOT_ID, null) == device.bootId) return@execute // already checked this boot
        prefs.edit().putString(KEY_BOOT_ID, device.bootId).apply()

        val abnormal = try {
            Regex(cfg.abnormalPattern).containsMatchIn("$reason $bootloaderReason")
        } catch (e: Exception) {
            Log.e(TAG, "Invalid abnormal_pattern", e)
            false
        }
        if (!abnormal) return@execute

        val history = Shell.getprop("persist.sys.boot.reason.history")
        sink.report(
            Issue(
                type = IssueType.ABNORMAL_BOOT,
                severity = Severity.CRITICAL,
                title = "Abnormal boot: $reason",
                source = name,
                details = "sys.boot.reason=$reason\nro.boot.bootreason=$bootloaderReason\nhistory:\n$history",
                attributes = mapOf("boot_reason" to reason, "bootloader_reason" to bootloaderReason),
            ),
        )
    }

    override fun stop() {}

    companion object {
        private const val TAG = "IssueDaemon.Boot"
        private const val KEY_BOOT_ID = "last_boot_id"
    }
}
