package com.agent.ai.core

import android.util.Log
import com.agent.ai.core.stt.SpeechToText
import com.agent.ai.core.tts.TextToSpeechEngine
import com.agent.ai.core.wakeword.WakeWordDetector
import com.agent.ai.data.AgentIntent
import com.agent.ai.data.GroqApiClient
import com.agent.ai.data.memory.AgentMemoryHub
import com.agent.ai.data.tools.ContactCallSession
import com.agent.ai.data.tools.ContactLookup
import com.agent.ai.data.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class AgentState { IDLE, LISTENING, THINKING, ACTING, SPEAKING, ERROR }

class AgentOrchestrator(
    private val scope: CoroutineScope,
    private val wakeWord: WakeWordDetector,
    private val stt: SpeechToText,
    private val tts: TextToSpeechEngine,
    private val groq: GroqApiClient,
    private val toolRegistry: ToolRegistry,
    private val onStateChanged: (AgentState) -> Unit,
    private val extraContextProvider: () -> String = { "" }
) {
    companion object { private const val TAG = "AgentOrchestrator" }

    @Volatile
    private var turnInProgress = false

    @Volatile
    private var wakeWordDisabled = false

    fun isBusy(): Boolean = turnInProgress

    fun wakeWordEnabled(): Boolean = wakeWord.isConfigured && !wakeWordDisabled

    fun start() {
        if (!wakeWord.isConfigured) {
            Log.i(TAG, "Wake word not configured — manual trigger only")
            onStateChanged(AgentState.IDLE)
            return
        }
        wakeWord.start().onError {
            wakeWordDisabled = true
            Log.w(TAG, "Wake word disabled for this session: [${it.code}] ${it.message}", it.cause)
            onStateChanged(AgentState.IDLE)
        }
    }

    fun stop() {
        if (wakeWord.isConfigured) wakeWord.stop()
    }

    fun onWakeWordTriggered() {
        if (!wakeWordEnabled()) return
        if (turnInProgress) {
            Log.w(TAG, "Wake word ignored — turn already in progress")
            return
        }
        pauseWakeWord()
        scope.launch { runTurn() }
    }

    fun onManualTrigger(): Boolean {
        if (turnInProgress) {
            Log.w(TAG, "Manual trigger ignored — turn already in progress")
            return false
        }
        pauseWakeWord()
        scope.launch { runTurn() }
        return true
    }

    private fun pauseWakeWord() {
        if (wakeWordEnabled()) wakeWord.stop()
    }

    private suspend fun runTurn() {
        turnInProgress = true
        try {
            runTurnInternal()
        } catch (e: Exception) {
            logAndSpeakError(
                AgentResult.Error(ErrorCode.UNKNOWN, e.message ?: "Unexpected failure during agent turn", e),
                "Agent"
            )
        } finally {
            turnInProgress = false
            resumeListening()
        }
    }

    private suspend fun runTurnInternal() {
        onStateChanged(AgentState.LISTENING)

        val sttResult = stt.listenOnce()
        val utterance = when (sttResult) {
            is AgentResult.Success -> sttResult.value
            is AgentResult.Error -> {
                logAndSpeakError(sttResult, "Speech recognition")
                return
            }
        }

        // Voice confirmation for pending call — skip LLM when user picks from list
        if (tryConfirmPendingCall(utterance)) return

        onStateChanged(AgentState.THINKING)

        val session = if (AgentMemoryHub.isReady()) AgentMemoryHub.session else null
        val history = session?.history().orEmpty()
        val memoryCtx = buildMemoryContext()

        val voiceResult = groq.runVoiceWithTools(utterance, history, memoryCtx) { toolName, params ->
            val tool = toolRegistry.find(toolName)
                ?: return@runVoiceWithTools AgentResult.Error(
                    ErrorCode.TOOL_NOT_FOUND,
                    "Unknown tool '$toolName'"
                )
            tool.execute(params).also {
                if (toolName == "call_contact") AgentController.syncPendingCall()
            }
        }

        when (voiceResult) {
            is AgentResult.Success -> {
                onStateChanged(AgentState.SPEAKING)
                val ttsResult = tts.speak(voiceResult.value)
                if (ttsResult is AgentResult.Error) {
                    logAndSpeakError(ttsResult, "Text-to-speech")
                    return
                }
                recordTurnIfReady(
                    session,
                    utterance,
                    AgentIntent.Speak(voiceResult.value),
                    voiceResult.value
                )
            }
            is AgentResult.Error -> logAndSpeakError(voiceResult, "Groq LLM")
        }
    }

    /** If a call is awaiting confirmation, resolve spoken "one" / "Aaai" directly. */
    private suspend fun tryConfirmPendingCall(utterance: String): Boolean {
        val pending = ContactCallSession.getPending() ?: return false
        val match = ContactLookup.resolveFromCandidates(
            candidates = pending.candidates,
            contactName = utterance,
            choiceIndex = ContactLookup.parseChoiceIndex(utterance)
        ) ?: return false

        onStateChanged(AgentState.ACTING)
        val dialer = toolRegistry.find("call_contact") ?: return false
        val choiceIdx = pending.candidates.indexOfFirst {
            it.displayName == match.displayName && it.phone == match.phone
        } + 1
        val result = dialer.execute(
            JSONObject().apply {
                put("contact_name", match.displayName)
                put("confirmed", true)
                put("choice_index", choiceIdx.coerceAtLeast(1))
            }
        )
        onStateChanged(AgentState.SPEAKING)
        return when (result) {
            is AgentResult.Success -> {
                AgentController.syncPendingCall()
                tts.speak(result.value)
                true
            }
            is AgentResult.Error -> {
                logAndSpeakError(result, "Call contact")
                true
            }
        }
    }

    private fun recordTurnIfReady(
        session: com.agent.ai.data.memory.SessionContext?,
        utterance: String,
        intent: AgentIntent,
        toolOrSpeakResult: String?
    ) {
        if (!AgentMemoryHub.isReady() || session == null) return
        AgentMemoryHub.extractor.recordTurn(utterance, intent, toolOrSpeakResult, session)
    }

    private fun buildMemoryContext(): String {
        val parts = mutableListOf<String>()
        if (AgentMemoryHub.isReady()) {
            AgentMemoryHub.repository.buildContextPrompt().takeIf { it.isNotBlank() }?.let { parts += it }
        }
        extraContextProvider().takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString("\n\n")
    }

    private suspend fun logAndSpeakError(error: AgentResult.Error, source: String) {
        Log.e(TAG, "[$source][${error.code}] ${error.message}", error.cause)
        AgentController.reportError(error, source)
        onStateChanged(AgentState.ERROR)
        tts.speak("Sorry, I couldn't do that — ${humanize(error.code)}.")
    }

    private fun humanize(code: ErrorCode): String = when (code) {
        ErrorCode.TOOL_PERMISSION_DENIED -> "a permission is missing"
        ErrorCode.TOOL_TARGET_NOT_FOUND -> "I couldn't find that"
        ErrorCode.STT_NO_MATCH, ErrorCode.STT_TIMEOUT -> "I didn't catch that"
        ErrorCode.LLM_NETWORK_ERROR -> "I'm having connection trouble"
        else -> "something went wrong"
    }

    private fun resumeListening() {
        onStateChanged(AgentState.IDLE)
        if (!wakeWordEnabled()) return
        wakeWord.start().onError {
            wakeWordDisabled = true
            Log.w(TAG, "Wake word resume failed — leaving disabled: [${it.code}] ${it.message}", it.cause)
        }
    }
}
