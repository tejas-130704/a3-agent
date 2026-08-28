package com.agent.ai.data.tools

import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import com.agent.ai.core.accessibility.AgentAccessibilityBridge
import com.agent.ai.core.accessibility.UiCommand
import org.json.JSONObject

/**
 * UI automation via AccessibilityService — click, type, scroll, inspect.
 * Requires the user to enable AI Agent in Settings → Accessibility.
 */
class UiAutomationTool : AgentTool {

    override val name = "ui_automation"
    override val description =
        "Tap, type, scroll, or inspect on-screen elements. " +
        "For typing messages: use UI_INPUT_TEXT with payload_text (emoji/numbers OK). " +
        "target_identifier is optional — omit to type into the focused or main message field. " +
        "Set submit=true to press Send/Enter after typing. Set use_clipboard=true to paste clipboard."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "action_type": {
              "type": "string",
              "enum": ["UI_CLICK", "UI_INPUT_TEXT", "UI_SCROLL", "UI_INSPECT"]
            },
            "target_identifier": {
              "type": "string",
              "description": "Optional for UI_INPUT_TEXT. Button label, field hint, or view id. Omit to use focused/main text field."
            },
            "payload_text": {
              "type": "string",
              "description": "Text to type — emoji, numbers, symbols supported. Ignored when use_clipboard=true."
            },
            "use_clipboard": {
              "type": "boolean",
              "description": "Paste from system clipboard instead of payload_text"
            },
            "submit": {
              "type": "boolean",
              "description": "After UI_INPUT_TEXT, press Send/Enter (default false)"
            },
            "scroll_forward": { "type": "boolean", "description": "For UI_SCROLL, default true" }
          },
          "required": ["action_type"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        return when (params.optString("action_type", "")) {
            "UI_CLICK" -> {
                val target = params.optString("target_identifier", "").trim()
                if (target.isEmpty()) {
                    return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "target_identifier required for UI_CLICK")
                }
                AgentAccessibilityBridge.dispatch(UiCommand.Click(target))
            }
            "UI_INPUT_TEXT" -> {
                val target = params.optString("target_identifier", "").trim()
                val useClipboard = params.optBoolean("use_clipboard", false)
                val text = params.optString("payload_text", "")
                val submit = if (params.has("submit")) params.optBoolean("submit") else true

                if (!useClipboard && text.isEmpty()) {
                    return AgentResult.Error(
                        ErrorCode.TOOL_INVALID_PARAMS,
                        "UI_INPUT_TEXT needs payload_text or use_clipboard=true"
                    )
                }
                AgentAccessibilityBridge.dispatch(
                    UiCommand.InputText(
                        target = target,
                        text = text,
                        useClipboard = useClipboard,
                        submit = submit
                    )
                )
            }
            "UI_SCROLL" -> {
                val forward = params.optBoolean("scroll_forward", true)
                AgentAccessibilityBridge.dispatch(UiCommand.Scroll(forward))
            }
            "UI_INSPECT" -> AgentAccessibilityBridge.dispatch(UiCommand.InspectScreen)
            "" -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "action_type missing")
            else -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "Unknown action_type")
        }
    }
}
