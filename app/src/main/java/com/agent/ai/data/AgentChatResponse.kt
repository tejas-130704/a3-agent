package com.agent.ai.data

data class ContactChoice(val index: Int, val displayName: String)

data class AgentChatResponse(
    val text: String,
    val contactChoices: List<ContactChoice>? = null,
    val pendingQuery: String? = null,
    val pendingSessionId: String? = null
)
