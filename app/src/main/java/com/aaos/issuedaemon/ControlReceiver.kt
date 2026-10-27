package com.aaos.issuedaemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aaos.issuedaemon.config.ConfigManager
import com.aaos.issuedaemon.model.Issue
import com.aaos.issuedaemon.model.IssueType
import com.aaos.issuedaemon.model.Severity
import org.json.JSONObject

/**
 * Runtime control over adb (sender must hold android.permission.DUMP, which shell has), e.g.:
 *
 *   adb shell am broadcast --user 0 -n com.aaos.issuedaemon/.ControlReceiver \
 *       -a com.aaos.issuedaemon.action.SET_SERVER_URL --es url http://10.0.2.2:8080
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ConfigManager.init(context)
        try {
            when (intent.action) {
                ACTION_SET_SERVER_URL -> {
                    val url = intent.getStringExtra("url").orEmpty().trim()
                    require(url.startsWith("http://") || url.startsWith("https://")) { "url must be http(s)://" }
                    ConfigManager.applyOverride(JSONObject().put("server", JSONObject().put("url", url)))
                    resultData = "server url set to $url"
                }
                ACTION_SET_CONFIG -> {
                    val json = JSONObject(intent.getStringExtra("json") ?: "{}")
                    ConfigManager.applyOverride(json)
                    resultData = "config override applied"
                }
                ACTION_RESET_CONFIG -> {
                    ConfigManager.resetOverride()
                    resultData = "config override cleared"
                }
                ACTION_TEST_ISSUE -> {
                    val runtime = DaemonRuntime.active ?: error("daemon not running")
                    runtime.dispatcher.report(
                        Issue(
                            type = IssueType.MANUAL,
                            severity = Severity.INFO,
                            title = intent.getStringExtra("title") ?: "Manual test issue",
                            source = "control",
                        ),
                    )
                    resultData = "test issue submitted"
                }
                ACTION_FLUSH -> {
                    (DaemonRuntime.active ?: error("daemon not running")).uploader.trigger()
                    resultData = "upload triggered"
                }
                ACTION_STATUS -> {
                    resultData = DaemonRuntime.active?.status()?.toString(2) ?: "daemon not running"
                }
            }
            DaemonService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Control action ${intent.action} failed", e)
            resultData = "error: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "IssueDaemon.Control"
        private const val PREFIX = "com.aaos.issuedaemon.action."
        const val ACTION_SET_SERVER_URL = PREFIX + "SET_SERVER_URL"
        const val ACTION_SET_CONFIG = PREFIX + "SET_CONFIG"
        const val ACTION_RESET_CONFIG = PREFIX + "RESET_CONFIG"
        const val ACTION_TEST_ISSUE = PREFIX + "TEST_ISSUE"
        const val ACTION_FLUSH = PREFIX + "FLUSH"
        const val ACTION_STATUS = PREFIX + "STATUS"
    }
}
