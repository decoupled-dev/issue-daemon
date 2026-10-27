package com.aaos.issuedaemon.monitor

import android.car.Car
import android.content.Context
import android.util.Log
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import com.aaos.issuedaemon.util.HealthState

/** Watches the car service binder; the Car library reconnects automatically and tells us when it died. */
class CarServiceMonitor(
    private val context: Context,
    private val sink: IssueSink,
    private val health: HealthState,
) : Monitor() {
    override val name = "car_service"

    private var car: Car? = null
    private var wasReady = false

    override fun start() {
        car = try {
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { _, ready -> onLifecycle(ready) }
        } catch (t: Throwable) {
            Log.w(TAG, "Car API unavailable, car service monitoring disabled: $t")
            null
        }
    }

    private fun onLifecycle(ready: Boolean) {
        health.put("car_service_ready", ready)
        if (wasReady && !ready) {
            sink.report(
                Issue(
                    type = IssueType.CAR_SERVICE_DIED,
                    severity = Severity.CRITICAL,
                    title = "Car service binder died",
                    source = name,
                    process = "com.android.car",
                ),
            )
        }
        wasReady = ready
    }

    override fun stop() {
        runCatching { car?.disconnect() }
        car = null
    }

    companion object {
        private const val TAG = "IssueDaemon.Car"
    }
}
