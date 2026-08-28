package com.agent.ai.data.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject

/** Launch an installed app by friendly name or package id. */
class OpenAppTool(private val context: Context) : AgentTool {

    override val name = "open_app"
    override val description =
        "Open an installed app by name (e.g. WhatsApp, Chrome, Camera, Settings) or package name."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "app_name": { "type": "string", "description": "Friendly app name or package name" }
          },
          "required": ["app_name"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val query = params.optString("app_name", "").trim()
        if (query.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "app_name was empty")
        }

        val resolved = AppLookup.resolveInstalled(context, query)
            ?: return AgentResult.Error(
                ErrorCode.TOOL_TARGET_NOT_FOUND,
                "No installed app matched '$query' — try the exact name shown on your home screen"
            )

        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(resolved.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            } ?: return AgentResult.Error(
                ErrorCode.TOOL_EXECUTION_FAILED,
                "App '${resolved.packageName}' has no launcher activity"
            )

            context.startActivity(launch)
            AgentResult.Success("Opened ${resolved.label}")
        } catch (e: ActivityNotFoundException) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Could not launch ${resolved.packageName}", e)
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "open_app failed: ${e.message}", e)
        }
    }
}
