package com.agent.ai.data.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject

/** Sets an alarm via the system AlarmClock intent — no permission needed, delegates to default clock app. */
class AlarmTool(private val context: Context) : AgentTool {

    override val name = "set_alarm"
    override val description = "Set an alarm for a given hour and minute (24h clock)."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "hour": { "type": "integer", "description": "0-23" },
            "minute": { "type": "integer", "description": "0-59" },
            "label": { "type": "string", "description": "Optional alarm label" }
          },
          "required": ["hour", "minute"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val hour = params.optInt("hour", -1)
        val minute = params.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "hour/minute out of range: hour=$hour minute=$minute")
        }
        val label = params.optString("label", "AI Agent")

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
            AgentResult.Success("Alarm set for %02d:%02d".format(hour, minute))
        } catch (e: ActivityNotFoundException) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "No clock app handles ACTION_SET_ALARM on this device", e)
        } catch (e: SecurityException) {
            AgentResult.Error(ErrorCode.TOOL_PERMISSION_DENIED, "SET_ALARM blocked by system policy", e)
        }
    }
}
