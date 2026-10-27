package com.aaos.issuedaemon.util

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/** Static identity of this vehicle/head unit, attached to every report and heartbeat. */
class DeviceInfo(private val context: Context, private val configuredId: String) {

    val vin: String? by lazy { readVin() }

    val bootId: String by lazy { Shell.readFile("/proc/sys/kernel/random/boot_id")?.trim() ?: "unknown" }

    private val androidId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private val daemonVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    /** Explicitly configured id, else the VIN, else ANDROID_ID. */
    val deviceId: String
        get() = configuredId.ifBlank { null } ?: vin ?: androidId

    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("vin", vin ?: JSONObject.NULL)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("fingerprint", Build.FINGERPRINT)
        put("build_type", Build.TYPE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("boot_id", bootId)
        put("uptime_ms", SystemClock.elapsedRealtime())
        put("daemon_version", daemonVersion)
    }

    private fun readVin(): String? = try {
        val car = Car.createCar(context)
        if (car == null) {
            null
        } else {
            try {
                val props = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                props?.getProperty(String::class.java, VehiclePropertyIds.INFO_VIN, 0)?.value?.takeIf { it.isNotBlank() }
            } finally {
                car.disconnect()
            }
        }
    } catch (t: Throwable) {
        // Not an automotive build, car service unavailable, or permission missing.
        Log.w(TAG, "VIN unavailable: $t")
        null
    }

    companion object {
        private const val TAG = "IssueDaemon.Device"
    }
}
