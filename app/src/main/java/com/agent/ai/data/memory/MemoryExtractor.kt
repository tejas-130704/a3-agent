package com.agent.ai.data.memory

import com.agent.ai.data.AgentIntent
import org.json.JSONObject

/** Updates Memory Sky from each voice/chat turn and tool invocation. */
class MemoryExtractor(private val repository: AgentMemoryRepository) {

    fun recordTurn(
        userUtterance: String,
        intent: AgentIntent,
        toolResult: String?,
        session: SessionContext
    ) {
        session.addUser(userUtterance)

        val (response, toolName, params) = when (intent) {
            is AgentIntent.Speak -> Triple(intent.text, null, null)
            is AgentIntent.InvokeTool -> Triple(toolResult ?: "Done", intent.toolName, intent.params)
        }

        session.addAssistant(response)

        val topics = mutableListOf<MemoryTopic>()
        val summary = buildSummary(userUtterance, toolName, params, response)
        topics.addAll(classifyTopics(userUtterance, toolName))

        if (toolName != null && params != null) {
            extractFromTool(toolName, params, userUtterance)
            topics.addAll(toolTopics(toolName))
        }

        repository.addSummary(
            ConversationSummary(
                userUtterance = userUtterance,
                agentResponse = response,
                toolUsed = toolName,
                summary = summary,
                topics = topics.distinct()
            )
        )

        repository.upsertBubble(
            topic = MemoryTopic.SESSION,
            subTopic = MemorySubTopics.CONVERSATION_SUMMARY,
            label = userUtterance.take(40),
            summary = summary
        )
    }

    fun recordChatTurn(userMessage: String, assistantReply: String) {
        repository.addSummary(
            ConversationSummary(
                userUtterance = userMessage,
                agentResponse = assistantReply,
                summary = "Chat: ${userMessage.take(60)}",
                topics = listOf(MemoryTopic.GENERAL)
            )
        )
    }

    private fun extractFromTool(toolName: String, params: JSONObject, utterance: String) {
        when (toolName) {
            "send_whatsapp" -> {
                val contact = params.optString("contact_name", params.optString("phone", "unknown"))
                val msg = params.optString("message", "")
                repository.upsertBubble(
                    MemoryTopic.WHATSAPP, MemorySubTopics.WA_FREQUENT_CONTACTS,
                    contact, "Often WhatsApp'd", mapOf("phone" to params.optString("phone"))
                )
                if (msg.isNotBlank()) {
                    repository.upsertBubble(
                        MemoryTopic.WHATSAPP, MemorySubTopics.WA_FREQUENT_MESSAGES,
                        msg.take(50), "Common message pattern"
                    )
                }
                repository.upsertBubble(
                    MemoryTopic.WHATSAPP, MemorySubTopics.WA_FREQUENT_COMMANDS,
                    utterance.take(60), "Voice command used"
                )
                repository.upsertBubble(
                    MemoryTopic.WHATSAPP, MemorySubTopics.WA_CORRECT_COMMANDS,
                    "$contact → ${msg.take(30)}", "Successful WA pattern"
                )
            }
            "send_telegram" -> {
                val target = params.optString("username", params.optString("contact_name", "unknown"))
                repository.upsertBubble(
                    MemoryTopic.TELEGRAM, "Frequent contacts", target, "Telegram recipient"
                )
            }
            "spotify_control" -> {
                val cmd = params.optString("playback_command", "")
                val query = params.optString("query", params.optString("uri", ""))
                repository.upsertBubble(
                    MemoryTopic.SPOTIFY, MemorySubTopics.SPOTIFY_COMMANDS,
                    cmd.ifBlank { "PLAY" }, "Spotify action"
                )
                if (query.isNotBlank()) {
                    repository.upsertBubble(
                        MemoryTopic.SPOTIFY, MemorySubTopics.SPOTIFY_ARTISTS,
                        query, "Played or searched on Spotify"
                    )
                }
            }
            "call_contact" -> {
                val name = params.optString("contact_name", "")
                repository.upsertBubble(
                    MemoryTopic.CONTACTS, MemorySubTopics.CONTACT_CALLS,
                    name, "Called via voice"
                )
                repository.upsertBubble(
                    MemoryTopic.FRIENDS, "Call history", name, "Friend/contact called"
                )
            }
            "toggle_setting" -> {
                val setting = params.optString("setting_name", "")
                repository.upsertBubble(
                    MemoryTopic.SETTINGS, MemorySubTopics.SETTINGS_TOGGLES,
                    setting, "Setting used via voice"
                )
            }
            "add_calendar_event" -> {
                repository.upsertBubble(
                    MemoryTopic.DAILY, "Calendar events",
                    params.optString("title", "event"), "Scheduled event"
                )
            }
            "set_alarm", "set_timer" -> {
                repository.upsertBubble(
                    MemoryTopic.DAILY, "Alarms & timers", toolName, utterance.take(50)
                )
            }
        }
    }

    private fun buildSummary(
        utterance: String,
        toolName: String?,
        params: JSONObject?,
        response: String
    ): String = when {
        toolName != null && params != null ->
            "User asked: \"$utterance\" → used $toolName → $response"
        else -> "User: \"$utterance\" → Agent: \"$response\""
    }

    private fun classifyTopics(utterance: String, toolName: String?): List<MemoryTopic> {
        val lower = utterance.lowercase()
        return buildList {
            if (toolName != null) add(toolTopics(toolName).firstOrNull() ?: MemoryTopic.GENERAL)
            if ("health" in lower || "medicine" in lower || "doctor" in lower) add(MemoryTopic.HEALTH)
            if ("friend" in lower) add(MemoryTopic.FRIENDS)
        }.distinct()
    }

    private fun toolTopics(toolName: String): List<MemoryTopic> = when (toolName) {
        "send_whatsapp" -> listOf(MemoryTopic.WHATSAPP)
        "send_telegram" -> listOf(MemoryTopic.TELEGRAM)
        "spotify_control" -> listOf(MemoryTopic.SPOTIFY)
        "call_contact" -> listOf(MemoryTopic.CONTACTS, MemoryTopic.FRIENDS)
        "toggle_setting" -> listOf(MemoryTopic.SETTINGS)
        "set_alarm", "set_timer", "add_calendar_event" -> listOf(MemoryTopic.DAILY)
        else -> listOf(MemoryTopic.GENERAL)
    }
}
