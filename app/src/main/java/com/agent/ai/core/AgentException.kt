package com.agent.ai.core

/**
 * Every fallible operation in the agent returns AgentResult instead of throwing.
 * Callers must handle Error explicitly — this is what "proper error handling"
 * means here: no swallowed exceptions, no silent no-ops.
 */
sealed class AgentResult<out T> {
    data class Success<T>(val value: T) : AgentResult<T>()
    data class Error(val code: ErrorCode, val message: String, val cause: Throwable? = null) : AgentResult<Nothing>()
}

inline fun <T> AgentResult<T>.onSuccess(block: (T) -> Unit): AgentResult<T> {
    if (this is AgentResult.Success) block(value)
    return this
}

inline fun <T> AgentResult<T>.onError(block: (AgentResult.Error) -> Unit): AgentResult<T> {
    if (this is AgentResult.Error) block(this)
    return this
}

/** UI-facing error with enough detail for on-device debugging. */
data class AgentErrorEvent(
    val code: ErrorCode,
    val message: String,
    val source: String,
    val causeSummary: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val debugText: String
        get() = buildString {
            appendLine("Source: $source")
            appendLine("Code: ${code.name}")
            appendLine("Hint: ${code.note}")
            appendLine("Message: $message")
            if (causeSummary != null) {
                appendLine()
                appendLine("Stack trace:")
                append(causeSummary)
            }
        }

    val title: String
        get() = when (code) {
            ErrorCode.STT_NOT_AVAILABLE, ErrorCode.STT_NO_MATCH, ErrorCode.STT_TIMEOUT, ErrorCode.STT_NETWORK_ERROR ->
                "Speech recognition failed"
            ErrorCode.LLM_NETWORK_ERROR, ErrorCode.LLM_AUTH_ERROR, ErrorCode.LLM_RATE_LIMITED,
            ErrorCode.LLM_MODEL_NOT_FOUND, ErrorCode.LLM_BAD_RESPONSE, ErrorCode.LLM_EMPTY_RESPONSE ->
                "AI request failed"
            ErrorCode.TOOL_NOT_FOUND, ErrorCode.TOOL_PERMISSION_DENIED, ErrorCode.TOOL_INVALID_PARAMS,
            ErrorCode.TOOL_TARGET_NOT_FOUND, ErrorCode.TOOL_EXECUTION_FAILED ->
                "Action failed"
            ErrorCode.TTS_INIT_FAILED, ErrorCode.TTS_LANGUAGE_UNSUPPORTED ->
                "Voice output failed"
            ErrorCode.WAKEWORD_INIT_FAILED, ErrorCode.WAKEWORD_MIC_PERMISSION_DENIED, ErrorCode.WAKEWORD_AUDIO_ENGINE_ERROR ->
                "Wake word failed"
            ErrorCode.SERVICE_KILLED_BY_OS ->
                "Agent service stopped"
            ErrorCode.UNKNOWN ->
                "Unexpected error"
        }
}

fun AgentResult.Error.toEvent(source: String): AgentErrorEvent = AgentErrorEvent(
    code = code,
    message = message,
    source = source,
    causeSummary = cause?.let { formatThrowable(it) }
)

fun formatThrowable(t: Throwable, depth: Int = 0): String {
    if (depth > 3) return "  … (truncated)\n"
    return buildString {
        append(t.javaClass.simpleName)
        if (!t.message.isNullOrBlank()) append(": ").append(t.message)
        append('\n')
        t.stackTrace.take(6).forEach { frame -> append("  at $frame\n") }
        t.cause?.let { cause ->
            append("Caused by: ")
            append(formatThrowable(cause, depth + 1))
        }
    }
}

/**
 * Every error code the agent can surface. Keep this flat and specific —
 * when something breaks on-device (MIUI kills the service, permission revoked
 * mid-session, etc.) we need to know exactly which subsystem failed, not just
 * "something went wrong".
 */
enum class ErrorCode(val note: String) {
    // Wake word
    WAKEWORD_INIT_FAILED("Porcupine engine failed to initialize — check AccessKey and .ppn model path"),
    WAKEWORD_MIC_PERMISSION_DENIED("RECORD_AUDIO not granted — cannot start hotword listener"),
    WAKEWORD_AUDIO_ENGINE_ERROR("Audio capture dropped mid-stream — device may have reclaimed the mic"),

    // STT
    STT_NOT_AVAILABLE("No speech recognition service available on this device"),
    STT_NO_MATCH("Speech was captured but not recognized — ask user to repeat"),
    STT_TIMEOUT("No speech detected within timeout window"),
    STT_NETWORK_ERROR("STT requires network and none was available (offline model not present)"),

    // LLM / Groq
    LLM_NETWORK_ERROR("Could not reach Groq API — check connectivity"),
    LLM_AUTH_ERROR("Groq API key missing or rejected (401/403)"),
    LLM_RATE_LIMITED("Groq API rate limit hit — backoff and retry"),
    LLM_MODEL_NOT_FOUND("Groq model ID not available — may be deprecated (HTTP 404)"),
    LLM_BAD_RESPONSE("Groq response was not valid JSON / did not match tool schema"),
    LLM_EMPTY_RESPONSE("Groq returned no content and no tool call"),

    // Tool execution
    TOOL_NOT_FOUND("LLM requested a tool_name not present in ToolRegistry"),
    TOOL_PERMISSION_DENIED("Required Android permission not granted for this tool"),
    TOOL_INVALID_PARAMS("Tool received parameters it could not use (missing/malformed field)"),
    TOOL_TARGET_NOT_FOUND("Tool's target could not be resolved (e.g. contact name not found)"),
    TOOL_EXECUTION_FAILED("Tool ran but the underlying Android API call failed"),

    // TTS
    TTS_INIT_FAILED("TextToSpeech engine failed to initialize"),
    TTS_LANGUAGE_UNSUPPORTED("Requested TTS language/locale not available on device"),

    // Service lifecycle
    SERVICE_KILLED_BY_OS("Foreground service was killed — likely MIUI/HyperOS battery restriction"),
    UNKNOWN("Unclassified failure — check cause for stack trace")
}
