package com.agent.ai.data.tools

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.core.net.toUri
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/** Open Google Maps (or fallback) with navigation from current location to destination. */
class MapsNavigationTool(private val context: Context) : AgentTool {

    override val name = "navigate_maps"
    override val description =
        "Open maps navigation from current location to a destination. " +
        "Destination can be an address, place name, or latitude,longitude. " +
        "Set start_navigation=true for turn-by-turn (default)."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "destination": {
              "type": "string",
              "description": "Address, place name, or lat,lng e.g. 19.0760,72.8777"
            },
            "travel_mode": {
              "type": "string",
              "enum": ["driving", "walking", "transit", "bicycling"],
              "description": "Default driving"
            },
            "start_navigation": {
              "type": "boolean",
              "description": "Start turn-by-turn navigation (default true). false = show route only."
            }
          },
          "required": ["destination"]
        }
    """.trimIndent())

    private val coordPattern = Pattern.compile(
        "^\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$"
    )

    override suspend fun execute(params: JSONObject): AgentResult<String> = withContext(Dispatchers.IO) {
        val destination = params.optString("destination", "").trim()
        if (destination.isEmpty()) {
            return@withContext AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "destination was empty")
        }

        val mode = params.optString("travel_mode", "driving").lowercase()
        val startNav = params.optBoolean("start_navigation", true)

        val destQuery = resolveDestinationQuery(destination)
            ?: return@withContext AgentResult.Error(
                ErrorCode.TOOL_TARGET_NOT_FOUND,
                "Could not resolve location '$destination' — try a fuller address"
            )

        val launched = if (startNav) {
            tryNavigationIntents(destQuery, mode) || tryMapsUrl(destQuery, mode, navigation = true)
        } else {
            tryMapsUrl(destQuery, mode, navigation = false) || tryGeoIntent(destQuery)
        }

        if (!launched) {
            return@withContext AgentResult.Error(
                ErrorCode.TOOL_EXECUTION_FAILED,
                "No maps app available — install Google Maps"
            )
        }

        val modeLabel = mode.replaceFirstChar { it.uppercase() }
        AgentResult.Success(
            if (startNav) {
                "Started $modeLabel navigation to $destQuery from your current location"
            } else {
                "Opened map for $destQuery"
            }
        )
    }

    private fun resolveDestinationQuery(raw: String): String? {
        coordPattern.matcher(raw).let { m ->
            if (m.matches()) {
                val lat = m.group(1)!!.toDouble()
                val lng = m.group(2)!!.toDouble()
                if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                    return "$lat,$lng"
                }
            }
        }

        if (Geocoder.isPresent()) {
            try {
                @Suppress("DEPRECATION")
                val results = Geocoder(context, Locale.getDefault()).getFromLocationName(raw, 1)
                val addr = results?.firstOrNull()
                if (addr != null) {
                    return "${addr.latitude},${addr.longitude}"
                }
            } catch (_: Exception) {
                // Fall through to raw address — Maps app can geocode
            }
        }
        return raw
    }

    private fun tryNavigationIntents(dest: String, mode: String): Boolean {
        val encoded = Uri.encode(dest)
        val navMode = when (mode) {
            "walking" -> "w"
            "bicycling" -> "b"
            else -> "d"
        }
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, "google.navigation:q=$encoded&mode=$navMode".toUri()),
            Intent(Intent.ACTION_VIEW, "google.navigation:q=$encoded".toUri())
        )
        for (intent in intents) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (tryStart(intent, "com.google.android.apps.maps")) return true
            if (tryStart(intent, null)) return true
        }
        return false
    }

    private fun tryMapsUrl(dest: String, mode: String, navigation: Boolean): Boolean {
        val encoded = Uri.encode(dest)
        val travelMode = when (mode) {
            "walking" -> "walking"
            "transit" -> "transit"
            "bicycling" -> "bicycling"
            else -> "driving"
        }
        val url = if (navigation) {
            "https://www.google.com/maps/dir/?api=1&origin=My+Location&destination=$encoded&travelmode=$travelMode&dir_action=navigate"
        } else {
            "https://www.google.com/maps/search/?api=1&query=$encoded"
        }
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return tryStart(intent, "com.google.android.apps.maps") || tryStart(intent, null)
    }

    private fun tryGeoIntent(dest: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(dest)}".toUri()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return tryStart(intent, null)
    }

    private fun tryStart(intent: Intent, packageName: String?): Boolean {
        packageName?.let { intent.setPackage(it) }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
