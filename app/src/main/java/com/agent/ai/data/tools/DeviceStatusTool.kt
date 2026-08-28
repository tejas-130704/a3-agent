package com.agent.ai.data.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read battery, network, storage, and optional latency for voice answers. */
class DeviceStatusTool(private val context: Context) : AgentTool {

    override val name = "device_status"
    override val description =
        "Report phone status: battery level, charging, network type, WiFi/mobile connectivity, " +
        "estimated link speed, free storage, and optional internet latency. " +
        "Use when user asks about battery, internet, network speed, or phone status."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "metrics": {
              "type": "array",
              "items": {
                "type": "string",
                "enum": ["battery", "network", "storage", "latency", "all"]
              },
              "description": "What to report. Default all."
            }
          },
          "required": []
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> = withContext(Dispatchers.IO) {
        val requested = parseMetrics(params.optJSONArray("metrics"))
        val parts = mutableListOf<String>()

        if ("battery" in requested) parts += readBattery()
        if ("network" in requested) parts += readNetwork()
        if ("storage" in requested) parts += readStorage()
        if ("latency" in requested) parts += readLatency()

        if (parts.isEmpty()) {
            AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "No metrics to report")
        } else {
            AgentResult.Success(parts.joinToString(". "))
        }
    }

    private fun parseMetrics(array: JSONArray?): Set<String> {
        if (array == null || array.length() == 0) return setOf("battery", "network", "storage")
        val items = buildSet {
            for (i in 0 until array.length()) {
                when (val v = array.optString(i, "").lowercase()) {
                    "all" -> {
                        add("battery"); add("network"); add("storage"); add("latency")
                    }
                    in setOf("battery", "network", "storage", "latency") -> add(v)
                }
            }
        }
        return items.ifEmpty { setOf("battery", "network", "storage") }
    }

    private fun readBattery(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery status unavailable"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless charger"
            else -> if (charging) "charger" else "battery"
        }
        return if (charging) {
            "Battery $pct percent, charging via $source"
        } else {
            "Battery $pct percent, not charging"
        }
    }

    private fun readNetwork(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
            ?: return "No active network connection"
        val caps = cm.getNetworkCapabilities(network)
            ?: return "Network connected but details unavailable"

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "network"
        }

        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        val downKbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.linkDownstreamBandwidthKbps
        } else 0
        val upKbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.linkUpstreamBandwidthKbps
        } else 0

        val speedPart = when {
            downKbps > 0 && upKbps > 0 ->
                "estimated download ${formatSpeed(downKbps)}, upload ${formatSpeed(upKbps)}"
            downKbps > 0 -> "estimated download speed ${formatSpeed(downKbps)}"
            else -> "speed estimate unavailable"
        }

        val conn = when {
            validated -> "internet working"
            internet -> "connected, internet not verified yet"
            else -> "limited connectivity"
        }
        return "Connected via $type, $conn, $speedPart"
    }

    private fun readStorage(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
            val totalGb = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
            "Storage ${"%.1f".format(freeGb)} gigabytes free of ${"%.0f".format(totalGb)} gigabytes"
        } catch (_: Exception) {
            "Storage info unavailable"
        }
    }

    private suspend fun readLatency(): String {
        val ms = withTimeoutOrNull(4_000L) { measureLatencyMs() }
        return when (ms) {
            null -> "Internet latency check timed out"
            -1 -> "Internet latency check failed — may be offline"
            else -> "Internet latency about ${ms} milliseconds"
        }
    }

    private fun measureLatencyMs(): Int {
        return try {
            val start = System.currentTimeMillis()
            val conn = URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399 || code == 204) {
                (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            } else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun formatSpeed(kbps: Int): String = when {
        kbps >= 1_000_000 -> "${kbps / 1_000_000} gigabits per second"
        kbps >= 1_000 -> "${kbps / 1_000} megabits per second"
        else -> "$kbps kilobits per second"
    }
}
