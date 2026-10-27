package com.aaos.issuedaemon.monitor

import android.content.Context
import android.os.PowerManager
import com.aaos.issuedaemon.config.ThermalConfig
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import com.aaos.issuedaemon.util.HealthState
import java.util.concurrent.Executor

class ThermalMonitor(
    context: Context,
    private val cfg: ThermalConfig,
    private val executor: Executor,
    private val sink: IssueSink,
    private val health: HealthState,
) : Monitor() {
    override val name = "thermal"

    private val pm = context.getSystemService(PowerManager::class.java)

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        health.put("thermal_status", STATUS_NAMES.getOrElse(status) { status.toString() })
        if (status < cfg.minStatus) return@OnThermalStatusChangedListener
        sink.report(
            Issue(
                type = IssueType.THERMAL,
                severity = if (status >= PowerManager.THERMAL_STATUS_CRITICAL) Severity.CRITICAL else Severity.WARNING,
                title = "Thermal status ${STATUS_NAMES.getOrElse(status) { status.toString() }}",
                source = name,
                attributes = mapOf("status" to status.toString()),
            ),
        )
    }

    override fun start() = pm.addThermalStatusListener(executor, listener)

    override fun stop() = pm.removeThermalStatusListener(listener)

    companion object {
        private val STATUS_NAMES = listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
    }
}
