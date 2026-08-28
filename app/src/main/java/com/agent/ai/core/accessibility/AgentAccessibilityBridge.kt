package com.agent.ai.core.accessibility

import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Commands dispatched from tools to [AgentAccessibilityService]. */
sealed class UiCommand {
    data class Click(val target: String) : UiCommand()
    data class InputText(
        val target: String = "",
        val text: String = "",
        val useClipboard: Boolean = false,
        val submit: Boolean = false
    ) : UiCommand()
    data class Scroll(val forward: Boolean = true) : UiCommand()
    data object InspectScreen : UiCommand()
}

/**
 * Thread-safe bridge between tool layer and the accessibility service instance.
 * The service registers itself on connect and clears on destroy.
 */
object AgentAccessibilityBridge {

    @Volatile
    private var service: AgentAccessibilityService? = null

    fun register(svc: AgentAccessibilityService) {
        service = svc
    }

    fun unregister(svc: AgentAccessibilityService) {
        if (service === svc) service = null
    }

    fun isEnabled(): Boolean = service != null

    suspend fun dispatch(command: UiCommand, timeoutMs: Long = 8_000L): AgentResult<String> {
        val svc = service
            ?: return AgentResult.Error(
                ErrorCode.TOOL_PERMISSION_DENIED,
                "Accessibility service not enabled — turn on AI Agent in Settings → Accessibility"
            )

        val deferred = CompletableDeferred<AgentResult<String>>()
        val accepted = svc.enqueue(command, deferred)
        if (!accepted) {
            return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Accessibility service busy with another command")
        }

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "UI automation timed out after ${timeoutMs}ms")
    }
}
