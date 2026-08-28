package com.agent.ai.data.tools

import android.content.Context
import android.media.AudioManager
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject

/** Get or set device volume by percentage (0–100). */
class VolumeTool(private val context: Context) : AgentTool {

    override val name = "volume_control"
    override val description =
        "Get current volume level or set volume to a percentage (0-100). " +
        "Use GET_STATUS when user asks volume level; SET_PERCENT for 'set volume to 80%'."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["GET_STATUS", "SET_PERCENT"]
            },
            "percent": {
              "type": "integer",
              "description": "Target volume 0-100 for SET_PERCENT"
            },
            "stream": {
              "type": "string",
              "enum": ["media", "ring", "alarm", "notification"],
              "description": "Audio stream — default media"
            }
          },
          "required": ["action"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val action = params.optString("action", "").trim().uppercase().let {
            when (it.lowercase()) {
                "get", "status", "read" -> "GET_STATUS"
                "set", "set_percent" -> "SET_PERCENT"
                else -> it
            }
        }
        val stream = parseStream(params.optString("stream", "media"))

        return when (action) {
            "GET_STATUS" -> getStatus(stream)
            "SET_PERCENT" -> {
                val percent = parsePercent(params)
                    ?: return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "percent required (0-100)")
                setPercent(stream, percent)
            }
            "" -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "action missing")
            else -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "Unknown action '$action'")
        }
    }

    private fun getStatus(stream: Int): AgentResult<String> {
        val am = audioManager() ?: return audioUnavailable()
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val current = am.getStreamVolume(stream)
        val pct = current * 100 / max
        val label = streamLabel(stream)
        val muted = if (am.isStreamMute(stream)) ", muted" else ""
        return AgentResult.Success("$label volume is $pct percent ($current of $max)$muted")
    }

    private fun setPercent(stream: Int, percent: Int): AgentResult<String> {
        val am = audioManager() ?: return audioUnavailable()
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val target = (percent * max / 100.0).toInt().coerceIn(0, max)
        return try {
            am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            val label = streamLabel(stream)
            val actualPct = target * 100 / max
            AgentResult.Success("Set $label volume to $actualPct percent")
        } catch (e: SecurityException) {
            AgentResult.Error(ErrorCode.TOOL_PERMISSION_DENIED, "Volume control blocked", e)
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Could not set volume: ${e.message}", e)
        }
    }

    private fun parsePercent(params: JSONObject): Int? {
        if (params.has("percent")) {
            return params.optInt("percent", -1).takeIf { it in 0..100 }
        }
        params.optString("level", "").trim().removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 }?.let {
            return it
        }
        return null
    }

    private fun parseStream(raw: String): Int = when (raw.lowercase()) {
        "ring", "ringtone" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification", "notify" -> AudioManager.STREAM_NOTIFICATION
        else -> AudioManager.STREAM_MUSIC
    }

    private fun streamLabel(stream: Int): String = when (stream) {
        AudioManager.STREAM_RING -> "Ring"
        AudioManager.STREAM_ALARM -> "Alarm"
        AudioManager.STREAM_NOTIFICATION -> "Notification"
        else -> "Media"
    }

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun audioUnavailable(): AgentResult.Error =
        AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "AudioManager unavailable")
}
