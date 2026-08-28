package com.agent.ai.data.memory

import org.json.JSONObject
import java.util.UUID

enum class MemoryTopic(val displayName: String, val emoji: String) {
    HEALTH("Personal Health", "💊"),
    FRIENDS("Friends", "👥"),
    CONTACTS("Contacts Used", "📇"),
    SETTINGS("Settings Used", "⚙️"),
    DAILY("Daily Word", "📅"),
    SPOTIFY("Spotify Logs", "🎵"),
    WHATSAPP("WhatsApp", "💬"),
    TELEGRAM("Telegram", "✈️"),
    SESSION("Live Session", "🔴"),
    GENERAL("General", "✨")
}

/** Sub-cloud categories within a topic sky. */
object MemorySubTopics {
    const val WA_FREQUENT_CONTACTS = "Frequent contacts"
    const val WA_FREQUENT_MESSAGES = "Frequent messages"
    const val WA_FREQUENT_COMMANDS = "Frequent commands"
    const val WA_CORRECT_COMMANDS = "Correct commands"
    const val SPOTIFY_ARTISTS = "Artists & searches"
    const val SPOTIFY_COMMANDS = "Playback commands"
    const val CONTACT_CALLS = "Called often"
    const val SETTINGS_TOGGLES = "Toggles used"
    const val CONVERSATION_SUMMARY = "Conversation summaries"
}

data class MemoryBubble(
    val id: String = UUID.randomUUID().toString(),
    val topic: MemoryTopic,
    val subTopic: String,
    val label: String,
    val summary: String,
    val frequency: Int = 1,
    val lastUpdated: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("topic", topic.name)
        put("subTopic", subTopic)
        put("label", label)
        put("summary", summary)
        put("frequency", frequency)
        put("lastUpdated", lastUpdated)
        put("metadata", JSONObject(metadata))
    }

    companion object {
        fun fromJson(obj: JSONObject): MemoryBubble = MemoryBubble(
            id = obj.getString("id"),
            topic = MemoryTopic.valueOf(obj.getString("topic")),
            subTopic = obj.getString("subTopic"),
            label = obj.getString("label"),
            summary = obj.getString("summary"),
            frequency = obj.optInt("frequency", 1),
            lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis()),
            metadata = jsonToMap(obj.optJSONObject("metadata"))
        )

        private fun jsonToMap(json: JSONObject?): Map<String, String> {
            if (json == null) return emptyMap()
            return buildMap {
                json.keys().forEach { k -> put(k, json.optString(k)) }
            }
        }
    }
}

data class ConversationSummary(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val userUtterance: String,
    val agentResponse: String,
    val toolUsed: String? = null,
    val summary: String,
    val topics: List<MemoryTopic> = emptyList()
)

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
