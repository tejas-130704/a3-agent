package com.agent.ai.data.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Opens WhatsApp with a pre-filled message via the official deep link.
 * User may still need to tap Send — WhatsApp does not allow silent send for third-party apps.
 */
class WhatsAppTool(private val context: Context) : AgentTool {

    override val name = "send_whatsapp"
    override val description = "Open WhatsApp to send a message to a contact by name or phone number."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "message": { "type": "string", "description": "Message body to pre-fill" },
            "contact_name": { "type": "string", "description": "Contact display name to look up" },
            "phone": { "type": "string", "description": "Phone with country code, e.g. 919876543210" }
          },
          "required": ["message"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val message = params.optString("message", "").trim()
        if (message.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "message was empty")
        }

        val phone = resolvePhone(params)
            ?: return AgentResult.Error(
                ErrorCode.TOOL_TARGET_NOT_FOUND,
                "Provide contact_name or phone — could not resolve a WhatsApp recipient"
            )

        val encodedText = URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encodedText")

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AgentResult.Success("Opened WhatsApp to message $phone — tap Send to deliver")
        } catch (e: ActivityNotFoundException) {
            // Fallback: WhatsApp Business or browser
            try {
                val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                AgentResult.Success("Opened WhatsApp link for $phone — tap Send to deliver")
            } catch (e2: ActivityNotFoundException) {
                AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "WhatsApp is not installed on this device", e2)
            }
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Failed to open WhatsApp: ${e.message}", e)
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
