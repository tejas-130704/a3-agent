package com.agent.ai.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agent.ai.core.AgentController
import com.agent.ai.core.AgentResult
import com.agent.ai.data.GroqApiClient
import com.agent.ai.data.memory.AgentMemoryHub
import com.agent.ai.data.memory.ChatMessage
import com.agent.ai.data.settings.SettingsKeyResolver
import com.agent.ai.data.tools.ContactCallSession
import com.agent.ai.data.tools.ContactLookup
import com.agent.ai.data.tools.DialerTool
import com.agent.ai.data.tools.ToolRegistryFactory
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AgentChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<UiChatMessage>()) }
    var loading by remember { mutableStateOf(false) }
    var chatSession by remember { mutableStateOf(listOf<ChatMessage>()) }

    val keyManager = remember(context) { SettingsKeyResolver.groqKeyManager(context) }
    val hasGroqKeys = remember(context) { keyManager.hasAnyKey() }
    val toolRegistry = remember(context) { ToolRegistryFactory.create(context) }
    val dialerTool = remember(context) { DialerTool(context) }
    val groq = remember(context) {
        GroqApiClient(keyManager = keyManager, toolRegistry = toolRegistry)
    }
    val isVoiceBusy = AgentController.isBusy

    fun appendAssistant(
        text: String,
        isError: Boolean = false,
        choices: List<ContactChoiceUi>? = null,
        query: String? = null,
        pendingSessionId: String? = null
    ) {
        messages = messages + UiChatMessage(
            role = "assistant",
            text = text,
            isError = isError,
            contactChoices = choices,
            pendingQuery = query,
            pendingSessionId = pendingSessionId
        )
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (loading) return
        if (!hasGroqKeys) return
        if (AgentController.isBusy) {
            appendAssistant("Voice agent is busy — wait for it to finish or use Home tab.", isError = true)
            return
        }

        loading = true
        input = ""
        messages = messages + UiChatMessage(role = "user", text = text)

        scope.launch {
            try {
                val memoryCtx = buildAgentChatContext(context)
                val result = groq.chatWithTools(text, chatSession, memoryCtx) { toolName, params ->
                    if (toolName == "call_contact" && AgentController.isBusy) {
                        return@chatWithTools AgentResult.Error(
                            com.agent.ai.core.ErrorCode.TOOL_EXECUTION_FAILED,
                            "Voice turn in progress — try again in a moment"
                        )
                    }
                    val tool = toolRegistry.find(toolName)
                        ?: return@chatWithTools AgentResult.Error(
                            com.agent.ai.core.ErrorCode.TOOL_NOT_FOUND,
                            "Unknown tool '$toolName'"
                        )
                    tool.execute(params).also {
                        if (toolName == "call_contact") AgentController.syncPendingCall()
                    }
                }
                when (result) {
                    is AgentResult.Success -> {
                        val response = result.value
                        val pending = ContactCallSession.getPending()
                        val uiChoices = response.contactChoices?.map {
                            ContactChoiceUi(it.index, it.displayName)
                        }
                        chatSession = chatSession + ChatMessage("user", text) + ChatMessage("assistant", response.text)
                        appendAssistant(
                            text = response.text,
                            choices = uiChoices,
                            query = response.pendingQuery ?: pending?.query,
                            pendingSessionId = response.pendingSessionId ?: pending?.id
                        )
                        AgentController.syncPendingCall()
                        if (AgentMemoryHub.isReady()) {
                            AgentMemoryHub.extractor.recordChatTurn(text, response.text)
                        }
                    }
                    is AgentResult.Error -> {
                        AgentController.reportError(result, "Agent chat")
                        appendAssistant(result.message, isError = true)
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    fun confirmContact(choice: ContactChoiceUi, pendingSessionId: String) {
        if (loading) return
        if (!ContactCallSession.isActive(pendingSessionId)) {
            appendAssistant("That contact list expired — say \"call …\" again to search.", isError = true)
            return
        }

        loading = true
        val label = "Call ${choice.displayName}"
        messages = messages + UiChatMessage(role = "user", text = label)

        scope.launch {
            try {
                val params = JSONObject().apply {
                    put("contact_name", choice.displayName)
                    put("confirmed", true)
                    put("choice_index", choice.index)
                }
                when (val result = dialerTool.execute(params)) {
                    is AgentResult.Success -> {
                        chatSession = chatSession + ChatMessage("user", label) +
                            ChatMessage("assistant", result.value)
                        appendAssistant(result.value)
                        AgentController.syncPendingCall()
                    }
                    is AgentResult.Error -> {
                        AgentController.reportError(result, "Call contact")
                        appendAssistant(result.message, isError = true)
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    ChatScaffold(
        title = "Agent Chat",
        subtitle = "Full tool access — tap a contact card to confirm a call.",
        messages = messages,
        loading = loading,
        input = input,
        onInputChange = { input = it },
        inputPlaceholder = if (hasGroqKeys) "Ask the agent to do something…" else "Add Groq keys in Settings",
        inputEnabled = hasGroqKeys,
        banner = {
            when {
                !hasGroqKeys -> ChatInfoBanner("Add a Groq API key in Settings to use Agent chat.", isError = true)
                isVoiceBusy -> ChatInfoBanner("Voice agent is active on Home — chat tools may wait.")
            }
        },
        onSend = { sendMessage(input.trim()) },
        onContactChoiceClick = { choice, sessionId -> confirmContact(choice, sessionId) },
        suggestions = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChatSuggestionChip("Call Aai") { sendMessage(it) }
                ChatSuggestionChip("Set alarm for 7 AM tomorrow") { input = it }
                ChatSuggestionChip("Play Arijit Singh on Spotify") { input = it }
                ChatSuggestionChip("Turn on flashlight") { input = it }
            }
        }
    )
}

private fun buildAgentChatContext(context: android.content.Context): String {
    val parts = mutableListOf<String>()
    if (AgentMemoryHub.isReady()) {
        parts += AgentMemoryHub.repository.buildContextPrompt()
        val sessionSummary = AgentMemoryHub.session.recentSummary()
        if (sessionSummary.isNotBlank()) parts += "Live session:\n$sessionSummary"
    }
    ContactLookup.buildFrequentContactsPrompt(context).takeIf { it.isNotBlank() }?.let { parts += it }
    ContactCallSession.buildContextPrompt().takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString("\n\n")
}
