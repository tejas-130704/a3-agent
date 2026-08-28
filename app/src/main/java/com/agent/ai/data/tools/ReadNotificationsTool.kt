package com.agent.ai.data.tools

import android.content.Context
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import com.agent.ai.core.notifications.NotificationReaderBridge
import org.json.JSONObject

/** Read recent notifications aloud — requires Notification access permission. */
class ReadNotificationsTool(private val context: Context) : AgentTool {

    override val name = "read_notifications"
    override val description =
        "Read the user's recent notification shade aloud. Use when they ask what notifications they have, " +
        "e.g. 'tell me my top 5 notifications', 'read my notifications', 'any new messages?'."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "count": {
              "type": "integer",
              "description": "How many recent notifications to read (default 5, max 20)"
            }
          },
          "required": []
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        if (!NotificationReaderBridge.isAccessGranted(context)) {
            return AgentResult.Error(
                ErrorCode.TOOL_PERMISSION_DENIED,
                "Notification access not enabled — open Settings → Notification access → enable AI Agent"
            )
        }

        val count = params.optInt("count", 5).coerceIn(1, 20)
        val notifications = NotificationReaderBridge.getRecent(count)
        return AgentResult.Success(NotificationReaderBridge.formatForSpeech(notifications))
    }
}
