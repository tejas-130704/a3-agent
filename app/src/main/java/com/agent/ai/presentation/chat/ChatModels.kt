package com.agent.ai.presentation.chat

import java.util.UUID

data class ContactChoiceUi(
    val index: Int,
    val displayName: String
)

data class UiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val contactChoices: List<ContactChoiceUi>? = null,
    val pendingQuery: String? = null,
    val pendingSessionId: String? = null
)
