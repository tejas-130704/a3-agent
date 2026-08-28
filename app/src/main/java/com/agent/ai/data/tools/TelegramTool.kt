package com.agent.ai.data.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject
import java.net.URLEncoder

/** Opens Telegram with a pre-filled message via deep link or share URL. */
class TelegramTool(private val context: Context) : AgentTool {

    override val name = "send_telegram"
    override val description = "Open Telegram to send a message by username, phone, or contact name."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "message": { "type": "string", "description": "Message body" },
            "username": { "type": "string", "description": "Telegram @username without @" },
            "contact_name": { "type": "string", "description": "Contact name to look up phone for" },
            "phone": { "type": "string", "description": "Phone with country code" }
          },
          "required": ["message"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val message = params.optString("message", "").trim()
        if (message.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "message was empty")
        }

        val encoded = URLEncoder.encode(message, "UTF-8")
        val username = params.optString("username", "").trim().removePrefix("@")

        val uri = when {
            username.isNotEmpty() -> Uri.parse("https://t.me/$username?text=$encoded")
            else -> {
                val phone = resolvePhone(params)
                    ?: return AgentResult.Error(
                        ErrorCode.TOOL_TARGET_NOT_FOUND,
                        "Provide username, contact_name, or phone for Telegram"
                    )
                Uri.parse("tg://msg?phone=$phone&text=$encoded")
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val target = if (username.isNotEmpty()) "@$username" else "phone recipient"
            AgentResult.Success("Opened Telegram for $target — confirm and send")
        } catch (e: ActivityNotFoundException) {
            // Share fallback when tg:// scheme unavailable
            try {
                val share = Uri.parse("https://t.me/share/url?text=$encoded")
                val intent = Intent(Intent.ACTION_VIEW, share).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                AgentResult.Success("Opened Telegram share — pick a chat and send")
            } catch (e2: ActivityNotFoundException) {
                AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Telegram is not installed", e2)
            }
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Failed to open Telegram: ${e.message}", e)
        }
    }

    private fun resolvePhone(params: JSONObject): String? {
        val direct = ContactLookup.digitsOnly(params.optString("phone", ""))
        if (direct.length >= 10) return direct
        val name = params.optString("contact_name", "").trim()
        if (name.isNotEmpty()) {
            val match = ContactLookup.resolveSpokenName(context, name)
            if (match != null) return ContactLookup.digitsOnly(match.phone)
        }
        return null
    }
}
