package com.agent.ai.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agent.ai.core.AgentController
import com.agent.ai.core.AgentResult
import com.agent.ai.data.GroqApiClient
import com.agent.ai.data.memory.AgentMemoryHub
import com.agent.ai.data.memory.ChatMessage
import com.agent.ai.data.settings.SettingsKeyResolver
import com.agent.ai.data.tools.ToolRegistry
import kotlinx.coroutines.launch

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<UiChatMessage>()) }
    var loading by remember { mutableStateOf(false) }
    var chatSession by remember { mutableStateOf(listOf<ChatMessage>()) }

    val groq = remember(context) {
        GroqApiClient(
            keyManager = SettingsKeyResolver.groqKeyManager(context),
            toolRegistry = ToolRegistry(emptyList())
        )
    }

    ChatScaffold(
        title = "Memory Chat",
        subtitle = "Ask about stored context, contacts, Spotify history, or session memory.",
        messages = messages,
        loading = loading,
        input = input,
        onInputChange = { input = it },
        inputPlaceholder = "Ask about your memory…",
        onSend = {
            val text = input.trim()
            if (text.isEmpty() || loading) return@ChatScaffold
            input = ""
            messages = messages + UiChatMessage(role = "user", text = text)
            loading = true
            scope.launch {
                val memoryCtx = if (AgentMemoryHub.isReady()) {
                    AgentMemoryHub.repository.buildContextPrompt() +
                        "\n\nLive session:\n" + AgentMemoryHub.session.recentSummary()
                } else ""
                val result = groq.chat(text, chatSession, memoryCtx)
                loading = false
                when (result) {
                    is AgentResult.Success -> {
                        chatSession = chatSession + ChatMessage("user", text) + ChatMessage("assistant", result.value)
                        messages = messages + UiChatMessage(role = "assistant", text = result.value)
                        if (AgentMemoryHub.isReady()) {
                            AgentMemoryHub.extractor.recordChatTurn(text, result.value)
                        }
                    }
                    is AgentResult.Error -> {
                        AgentController.reportError(result, "Memory chat")
                        messages = messages + UiChatMessage(role = "assistant", text = result.message, isError = true)
                    }
                }
            }
        },
        suggestions = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChatSuggestionChip("What WhatsApp contacts do I use most?") { input = it }
                ChatSuggestionChip("What did I ask in this session?") { input = it }
                ChatSuggestionChip("Summarize my Spotify preferences") { input = it }
            }
        }
    )
}
