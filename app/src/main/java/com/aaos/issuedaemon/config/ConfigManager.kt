package com.aaos.issuedaemon.config

import android.content.Context
import android.util.Log
import com.aaos.issuedaemon.util.Shell
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loads the daemon configuration from several layers, later layers winning:
 *
 *  1. assets/default_config.json (built into the APK)
 *  2. /system/etc, /system_ext/etc, /product/etc, /vendor/etc  issue-daemon/config.json (OEM image)
 *  3. <device-protected data>/config_override.json (written at runtime by ControlReceiver)
 *  4. system properties persist.sys.issuedaemon.server_url / .api_key (fleet provisioning)
 */
object ConfigManager {
    private const val TAG = "IssueDaemon.Config"
    private const val OVERRIDE_FILE = "config_override.json"
    const val PROP_SERVER_URL = "persist.sys.issuedaemon.server_url"
    const val PROP_API_KEY = "persist.sys.issuedaemon.api_key"

    private val SYSTEM_CONFIG_PATHS = listOf(
        "/system/etc/issue-daemon/config.json",
        "/system_ext/etc/issue-daemon/config.json",
        "/product/etc/issue-daemon/config.json",
        "/vendor/etc/issue-daemon/config.json",
    )

    private lateinit var context: Context
    private val listeners = CopyOnWriteArrayList<(DaemonConfig) -> Unit>()

    @Volatile
    lateinit var current: DaemonConfig
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (::context.isInitialized) return
        context = ctx.applicationContext.createDeviceProtectedStorageContext()
        current = load()
        Log.i(TAG, "Loaded config, server=${current.server.url.ifEmpty { "<unset>" }}")
    }

    fun addListener(l: (DaemonConfig) -> Unit) = listeners.add(l)

    fun removeListener(l: (DaemonConfig) -> Unit) = listeners.remove(l)

    /** Merges [partial] into the persisted runtime override and reloads. */
    @Synchronized
    fun applyOverride(partial: JSONObject) {
        val file = overrideFile()
        val existing = readJson(file) ?: JSONObject()
        file.writeText(deepMerge(existing, partial).toString(2))
        reload()
    }

    @Synchronized
    fun resetOverride() {
        overrideFile().delete()
        reload()
    }

    @Synchronized
    fun reload() {
        current = load()
        Log.i(TAG, "Config reloaded, server=${current.server.url.ifEmpty { "<unset>" }}")
        listeners.forEach { runCatching { it(current) }.onFailure { e -> Log.e(TAG, "Listener failed", e) } }
    }

    private fun load(): DaemonConfig {
        var merged = context.assets.open("default_config.json").bufferedReader().use { JSONObject(it.readText()) }
        for (path in SYSTEM_CONFIG_PATHS) {
            readJson(File(path))?.let {
                Log.i(TAG, "Applying $path")
                merged = deepMerge(merged, it)
            }
        }
        readJson(overrideFile())?.let { merged = deepMerge(merged, it) }

        val propOverrides = JSONObject()
        Shell.getprop(PROP_SERVER_URL).takeIf { it.isNotEmpty() }?.let { propOverrides.put("url", it) }
        Shell.getprop(PROP_API_KEY).takeIf { it.isNotEmpty() }?.let { propOverrides.put("api_key", it) }
        if (propOverrides.length() > 0) {
            merged = deepMerge(merged, JSONObject().put("server", propOverrides))
        }
        return DaemonConfig.fromJson(merged)
    }

    private fun overrideFile() = File(context.filesDir, OVERRIDE_FILE)

    private fun readJson(file: File): JSONObject? = try {
        if (file.canRead()) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        Log.e(TAG, "Ignoring unreadable config $file", e)
        null
    }
}
