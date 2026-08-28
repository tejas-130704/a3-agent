package com.agent.ai.data.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject

/** Sets a countdown timer via the system clock app. */
class TimerTool(private val context: Context) : AgentTool {

    override val name = "set_timer"
    override val description = "Set a countdown timer for a number of seconds."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "seconds": { "type": "integer", "description": "Timer length in seconds (1-86400)" },
            "label": { "type": "string", "description": "Optional timer label" }
          },
          "required": ["seconds"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val seconds = params.optInt("seconds", -1)
        if (seconds !in 1..86400) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "seconds must be 1-86400, got $seconds")
        }
        val label = params.optString("label", "AI Agent")

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AgentResult.Success("Timer set for ${formatDuration(seconds)}")
        } catch (e: ActivityNotFoundException) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "No clock app handles ACTION_SET_TIMER", e)
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Timer intent failed: ${e.message}", e)
        }
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
