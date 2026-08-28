package com.agent.ai.core

import com.agent.ai.data.ContactChoice
import com.agent.ai.data.tools.ContactCallSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI ↔ foreground service bridge for manual agent triggers and live state. */
object AgentController {

    private var orchestrator: AgentOrchestrator? = null

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<AgentErrorEvent?>(null)
    val lastError: StateFlow<AgentErrorEvent?> = _lastError.asStateFlow()

    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    data class PendingCallUi(
        val pendingId: String,
        val query: String,
        val choices: List<ContactChoice>
    )

    private val _pendingCall = MutableStateFlow<PendingCallUi?>(null)
    val pendingCall: StateFlow<PendingCallUi?> = _pendingCall.asStateFlow()

    fun register(orchestrator: AgentOrchestrator) {
        this.orchestrator = orchestrator
    }

    fun unregister(orchestrator: AgentOrchestrator) {
        if (this.orchestrator === orchestrator) this.orchestrator = null
        syncPendingCall()
    }

    fun updateState(state: AgentState) {
        _state.value = state
    }

    fun reportError(error: AgentResult.Error, source: String) {
        _lastError.value = error.toEvent(source)
    }

    fun clearError() {
        _lastError.value = null
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        _wakeWordEnabled.value = enabled
    }

    fun syncPendingCall() {
        val p = ContactCallSession.getPending()
        _pendingCall.value = if (p == null) null else PendingCallUi(
            pendingId = p.id,
            query = p.query,
            choices = ContactCallSession.toContactChoices().orEmpty()
        )
    }

    fun clearPendingCall() {
        ContactCallSession.clear()
        _pendingCall.value = null
    }

    fun isServiceReady(): Boolean = orchestrator != null

    fun triggerManualTurn(): Boolean = orchestrator?.onManualTrigger() ?: false

    val isBusy: Boolean get() = orchestrator?.isBusy() ?: false
}
