package com.agent.ai.data.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject

/**
 * Two-step calling: always list top matches and require confirmation before dialing.
 * confirmed=true is rejected unless a pending session exists from step 1.
 */
class DialerTool(private val context: Context) : AgentTool {

    override val name = "call_contact"
    override val description =
        "Call a phone contact. Step 1: confirmed=false lists top 4 matches and asks user to pick. " +
        "Step 2: after user confirms, call with confirmed=true and choice_index (1-4) or exact contact_name from that list."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "contact_name": {
              "type": "string",
              "description": "Spoken name to search, or exact name from the confirmation list"
            },
            "confirmed": {
              "type": "boolean",
              "description": "false = search and ask which contact; true = dial after user confirmed"
            },
            "choice_index": {
              "type": "integer",
              "description": "1-4 when user picks by number from the list"
            }
          },
          "required": ["contact_name"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val queryName = params.optString("contact_name", "").trim()
        if (queryName.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "contact_name was empty")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return AgentResult.Error(ErrorCode.TOOL_PERMISSION_DENIED, "READ_CONTACTS not granted")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return AgentResult.Error(ErrorCode.TOOL_PERMISSION_DENIED, "CALL_PHONE not granted")
        }

        val confirmed = params.optBoolean("confirmed", false)
        val choiceIndex = params.optInt("choice_index", -1).takeIf { it > 0 }
            ?: ContactLookup.parseChoiceIndex(queryName)

        if (!confirmed) {
            val matches = ContactLookup.findTopMatches(context, queryName, limit = 4)
            if (matches.isEmpty()) {
                ContactCallSession.clear()
                return AgentResult.Error(
                    ErrorCode.TOOL_TARGET_NOT_FOUND,
                    "No contacts matched '$queryName'. Check READ_CONTACTS permission and your contact list."
                )
            }
            ContactCallSession.setPending(queryName, matches)
            return AgentResult.Success(ContactLookup.formatConfirmationPrompt(queryName, matches))
        }

        val pending = ContactCallSession.getPending()
            ?: return AgentResult.Error(
                ErrorCode.TOOL_INVALID_PARAMS,
                "No active call confirmation — search first with confirmed=false, then pick from the list"
            )

        val match = ContactLookup.resolveFromCandidates(
            candidates = pending.candidates,
            contactName = queryName,
            choiceIndex = choiceIndex
        ) ?: return AgentResult.Error(
            ErrorCode.TOOL_TARGET_NOT_FOUND,
            "Could not confirm '$queryName' — pick number 1-${pending.candidates.size} or say the exact name from the list"
        )

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${match.phone}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ContactCallSession.clear()
            AgentResult.Success("Calling ${match.displayName}")
        } catch (e: SecurityException) {
            AgentResult.Error(ErrorCode.TOOL_PERMISSION_DENIED, "ACTION_CALL rejected at runtime despite granted permission", e)
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "startActivity(ACTION_CALL) failed: ${e.message}", e)
        }
    }
}
