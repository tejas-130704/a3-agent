package com.agent.ai.data

import android.util.Log
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import com.agent.ai.data.memory.ChatMessage
import com.agent.ai.data.settings.AgentSettingsStore
import com.agent.ai.data.tools.ContactCallSession
import com.agent.ai.data.tools.ContactLookup
import com.agent.ai.data.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class AgentIntent {
    data class Speak(val text: String) : AgentIntent()
    data class InvokeTool(val toolName: String, val params: JSONObject) : AgentIntent()
}

class GroqApiClient(
    private val keyManager: GroqKeyManager,
    private val models: List<String> = DEFAULT_MODELS,
    private val toolRegistry: ToolRegistry
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

    suspend fun resolveIntent(
        userUtterance: String,
        sessionHistory: List<ChatMessage> = emptyList(),
        memoryContext: String = ""
    ): AgentResult<AgentIntent> = withContext(Dispatchers.IO) {
        executeWithFailover { key ->
            callWithModelFallback(key) { model ->
                callGroqWithTools(key, model, userUtterance, sessionHistory, memoryContext)
            }
        }
    }

    /**
     * Voice path with multi-tool chaining (web_search → save_note, etc.).
     * Returns final speakable text after all tool rounds complete.
     */
    suspend fun runVoiceWithTools(
        userUtterance: String,
        sessionHistory: List<ChatMessage> = emptyList(),
        memoryContext: String = "",
        toolExecutor: suspend (toolName: String, params: JSONObject) -> AgentResult<String>
    ): AgentResult<String> = withContext(Dispatchers.IO) {
        executeWithFailoverSuspend { key ->
            callWithModelFallbackSuspend(key) { model ->
                runVoiceToolLoop(key, model, userUtterance, sessionHistory, memoryContext, toolExecutor)
            }
        }
    }

    suspend fun chat(
        userMessage: String,
        sessionHistory: List<ChatMessage> = emptyList(),
        memoryContext: String = ""
    ): AgentResult<String> = withContext(Dispatchers.IO) {
        executeWithFailover { key ->
            callWithModelFallback(key) { model ->
                callGroqChatOnly(key, model, userMessage, sessionHistory, memoryContext)
            }
        }
    }

    /**
     * Text chat with full tool access — runs tool calls and feeds results back to the LLM.
     */
    suspend fun chatWithTools(
        userMessage: String,
        sessionHistory: List<ChatMessage> = emptyList(),
        memoryContext: String = "",
        toolExecutor: suspend (toolName: String, params: JSONObject) -> AgentResult<String>
    ): AgentResult<AgentChatResponse> = withContext(Dispatchers.IO) {
        executeWithFailoverSuspend { key ->
            callWithModelFallbackSuspend(key) { model ->
                runToolChatLoop(key, model, userMessage, sessionHistory, memoryContext, toolExecutor)
            }
        }
    }

    private suspend fun <T> executeWithFailoverSuspend(call: suspend (String) -> AgentResult<T>): AgentResult<T> {
        val keys = keyManager.keysForRequest()
        if (keys.isEmpty()) {
            return AgentResult.Error(
                ErrorCode.LLM_AUTH_ERROR,
                "No Groq API keys configured — add keys in Settings tab"
            )
        }
        var lastError: AgentResult.Error? = null
        for ((attempt, keyEntry) in keys.withIndex()) {
            when (val result = call(keyEntry.value)) {
                is AgentResult.Success -> {
                    keyManager.onKeySuccess(keyEntry)
                    if (attempt > 0) Log.i(TAG, "Groq failover succeeded on key #${keyEntry.index}")
                    return result
                }
                is AgentResult.Error -> {
                    lastError = result
                    if (GroqKeyManager.isRotationEligible(result.code)) continue
                    return result
                }
            }
        }
        return lastError ?: AgentResult.Error(ErrorCode.LLM_NETWORK_ERROR, "All Groq API keys failed")
    }

    private fun <T> executeWithFailover(call: (String) -> AgentResult<T>): AgentResult<T> {
        val keys = keyManager.keysForRequest()
        if (keys.isEmpty()) {
            return AgentResult.Error(
                ErrorCode.LLM_AUTH_ERROR,
                "No Groq API keys configured — add keys in Settings tab"
            )
        }
        var lastError: AgentResult.Error? = null
        for ((attempt, keyEntry) in keys.withIndex()) {
            when (val result = call(keyEntry.value)) {
                is AgentResult.Success -> {
                    keyManager.onKeySuccess(keyEntry)
                    if (attempt > 0) Log.i(TAG, "Groq failover succeeded on key #${keyEntry.index}")
                    return result
                }
                is AgentResult.Error -> {
                    lastError = result
                    if (GroqKeyManager.isRotationEligible(result.code)) continue
                    return result
                }
            }
        }
        return lastError ?: AgentResult.Error(ErrorCode.LLM_NETWORK_ERROR, "All Groq API keys failed")
    }

    /** Try each Groq model in order; 404 or 429 (rate limit) will failover to next model. */
    private suspend fun <T> callWithModelFallbackSuspend(
        apiKey: String,
        call: suspend (model: String) -> AgentResult<T>
    ): AgentResult<T> {
        var lastError: AgentResult.Error? = null
        for ((index, model) in models.withIndex()) {
            when (val result = call(model)) {
                is AgentResult.Success -> {
                    if (index > 0) Log.i(TAG, "Groq model failover succeeded on $model")
                    return result
                }
                is AgentResult.Error -> {
                    lastError = result
                    if (result.code == ErrorCode.LLM_MODEL_NOT_FOUND || result.code == ErrorCode.LLM_RATE_LIMITED) {
                        Log.w(TAG, "Model $model encountered ${result.code} — trying next model in chain")
                        continue
                    }
                    return result
                }
            }
        }
        return lastError ?: AgentResult.Error(
            ErrorCode.LLM_MODEL_NOT_FOUND,
            "All configured Groq models failed — check console.groq.com/docs/models"
        )
    }

    /** Try each Groq model in order; 404 or 429 (rate limit) will failover to next model. */
    private fun <T> callWithModelFallback(
        apiKey: String,
        call: (model: String) -> AgentResult<T>
    ): AgentResult<T> {
        var lastError: AgentResult.Error? = null
        for ((index, model) in models.withIndex()) {
            when (val result = call(model)) {
                is AgentResult.Success -> {
                    if (index > 0) Log.i(TAG, "Groq model failover succeeded on $model")
                    return result
                }
                is AgentResult.Error -> {
                    lastError = result
                    if (result.code == ErrorCode.LLM_MODEL_NOT_FOUND || result.code == ErrorCode.LLM_RATE_LIMITED) {
                        Log.w(TAG, "Model $model encountered ${result.code} — trying next model in chain")
                        continue
                    }
                    return result
                }
            }
        }
        return lastError ?: AgentResult.Error(
            ErrorCode.LLM_MODEL_NOT_FOUND,
            "All configured Groq models failed — check console.groq.com/docs/models"
        )
    }

    private fun buildSystemPrompt(memoryContext: String, includeTools: Boolean): String {
        val mem = if (memoryContext.isNotBlank()) "\n\n$memoryContext" else ""
        val toolHint = if (includeTools) """
Available capabilities:
- Alarms, timers, calendar, phone calls
- WhatsApp/Telegram (pre-fill message — user taps Send)
- Spotify play/pause/skip/search
- Open apps, flashlight, WiFi/BT panels, UI automation
- Query device/component info (get_info) — alarms, calendar events, contacts, settings, apps, device, volume, notifications, notes
- Volume get/set (volume_control), web search (web_search), save notes (save_note)
- Read notifications aloud (read_notifications) — e.g. "tell me my top 5 notifications"
- Install/uninstall apps (manage_app), device status (device_status), maps navigation (navigate_maps)

GET INFO / COMPONENT QUERY: When the user asks about the state or details of mobile components (e.g., "what alarms are set?", "what events do I have today/tomorrow?", "is WhatsApp installed?", "what is my WiFi/Bluetooth status?", "search contact number for Mom"), call get_info with component ("alarms" | "calendar" | "contacts" | "settings" | "apps" | "device" | "volume" | "notifications" | "notes") and optional query or timeframe ("today" | "tomorrow" | "week").

MULTI-TOOL CHAINING: You may call tools sequentially when needed. For web search + save: call web_search ONCE, then on the next round call save_note with a concise summary. Do NOT run repetitive web searches in a loop. After saving notes or executing actions, return a final concise summary.
VOLUME: volume_control GET_STATUS for "what's my volume"; SET_PERCENT with percent 0-100 for "set volume to 80%".
WEB SEARCH: web_search for current facts, admission processes, news — not for device actions.

Use session history for follow-ups: if user said "Spotify" then "play Arijit Singh", call spotify_control PLAY_SEARCH.
Prefer frequent contacts/artists from USER MEMORY when user says "message her", "call him", "play that again".
CALL CONTACT RULE (mandatory two-step):
1. First request: call_contact(contact_name=spoken name, confirmed=false) — always list top matches, never dial yet.
2. Read the list to the user and ask which one.
3. After user picks: call_contact(confirmed=true, choice_index=N or contact_name=exact name from list).
Never skip confirmation. Never dial on the first call_contact invocation.
APP INSTALL/UNINSTALL: use manage_app with action INSTALL or UNINSTALL (lowercase ok). INSTALL opens Play Store or MIUI GetApps — user taps Install. UNINSTALL opens system uninstall screen. Always pass app_name.
DELETE MEMORY: delete_memory with query matching label/summary when user asks to forget/remove from memory.
SAVE NOTES: save_note with content (+ optional title) when user asks to save/remember something in Notes.
DEVICE STATUS: device_status for battery, network, storage, latency questions — metrics array optional, default battery+network+storage.
MAPS: navigate_maps with destination address or place name, start_navigation=true for turn-by-turn from current GPS location.
UI TYPING & SENDING: When asked to type or send a message on screen using the keyboard/UI, call ui_automation with action_type=UI_INPUT_TEXT, payload_text=the message, and submit=true. The agent must write the message and hit Enter/Send immediately. Do NOT ask for confirmation and do NOT ask the user to tap Send.
NOTIFICATIONS: When user asks to read/hear notifications, call read_notifications with count (default 5). Summarize the tool result naturally when speaking.
""" else ""
        return BASE_PROMPT + toolHint + mem
    }

    private fun buildMessagesArray(
        systemPrompt: String,
        sessionHistory: List<ChatMessage>,
        userUtterance: String
    ): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", systemPrompt))
        sessionHistory.forEach { msg ->
            put(JSONObject().put("role", msg.role).put("content", msg.content))
        }
        put(JSONObject().put("role", "user").put("content", userUtterance))
    }

    private fun callGroqWithTools(
        apiKey: String,
        model: String,
        userUtterance: String,
        sessionHistory: List<ChatMessage>,
        memoryContext: String
    ): AgentResult<AgentIntent> {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", buildMessagesArray(
                buildSystemPrompt(memoryContext, includeTools = true),
                sessionHistory,
                userUtterance
            ))
            put("tools", toolRegistry.toGroqToolSchema())
            put("tool_choice", "auto")
            put("temperature", 0.2)
        }
        return executeRequest(apiKey, model, body) { raw -> parseGroqResponse(raw) }
    }

    private fun callGroqChatOnly(
        apiKey: String,
        model: String,
        userMessage: String,
        sessionHistory: List<ChatMessage>,
        memoryContext: String
    ): AgentResult<String> {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", buildMessagesArray(
                buildSystemPrompt(memoryContext, includeTools = false) +
                    "\nYou are in the Memory Chat interface. Answer questions about stored user context. Be concise.",
                sessionHistory,
                userMessage
            ))
            put("temperature", 0.3)
        }
        return executeRequest(apiKey, model, body) { raw ->
            try {
                val content = JSONObject(raw).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").optString("content", "")
                if (content.isBlank()) AgentResult.Error(ErrorCode.LLM_EMPTY_RESPONSE, "Empty chat response")
                else AgentResult.Success(content)
            } catch (e: Exception) {
                AgentResult.Error(ErrorCode.LLM_BAD_RESPONSE, e.message ?: "parse error", e)
            }
        }
    }

    private fun <T> executeRequest(
        apiKey: String,
        model: String,
        body: JSONObject,
        parser: (String) -> AgentResult<T>
    ): AgentResult<T> {
        val cleanKey = AgentSettingsStore.cleanSingleKey(apiKey)
        val request = try {
            Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $cleanKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: Exception) {
            return AgentResult.Error(ErrorCode.LLM_AUTH_ERROR, "Invalid API key format: ${e.message}", e)
        }
        return try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string()
                when (response.code) {
                    401, 403 -> return AgentResult.Error(
                        ErrorCode.LLM_AUTH_ERROR,
                        groqErrorMessage(raw, response.code, model)
                    )
                    429 -> return AgentResult.Error(
                        ErrorCode.LLM_RATE_LIMITED,
                        groqErrorMessage(raw, response.code, model)
                    )
                    402 -> return AgentResult.Error(
                        ErrorCode.LLM_AUTH_ERROR,
                        groqErrorMessage(raw, response.code, model)
                    )
                    404 -> return AgentResult.Error(
                        ErrorCode.LLM_MODEL_NOT_FOUND,
                        groqErrorMessage(raw, response.code, model)
                    )
                    in 500..599 -> return AgentResult.Error(
                        ErrorCode.LLM_NETWORK_ERROR,
                        groqErrorMessage(raw, response.code, model)
                    )
                }
                if (!response.isSuccessful || raw == null) {
                    return AgentResult.Error(
                        ErrorCode.LLM_NETWORK_ERROR,
                        groqErrorMessage(raw, response.code, model)
                    )
                }
                parser(raw)
            }
        } catch (e: IOException) {
            AgentResult.Error(ErrorCode.LLM_NETWORK_ERROR, e.message ?: "network", e)
        }
    }

    private fun groqErrorMessage(raw: String?, httpCode: Int, model: String): String {
        val apiMsg = parseGroqApiError(raw)
        return buildString {
            append("HTTP $httpCode")
            append(" (model=$model)")
            if (apiMsg != null) append(" — ").append(apiMsg)
        }
    }

    private fun parseGroqApiError(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            raw.take(200)
        }
    }

    private fun parseGroqResponse(raw: String): AgentResult<AgentIntent> {
        return try {
            when (val turn = parseGroqTurn(raw)) {
                is GroqTurn.Text -> {
                    if (turn.content.isBlank()) AgentResult.Error(ErrorCode.LLM_EMPTY_RESPONSE, "No content or tool")
                    else AgentResult.Success(AgentIntent.Speak(turn.content))
                }
                is GroqTurn.ToolCalls -> {
                    val first = turn.calls.firstOrNull()
                        ?: return AgentResult.Error(ErrorCode.LLM_EMPTY_RESPONSE, "No tools in turn")
                    AgentResult.Success(AgentIntent.InvokeTool(first.name, first.arguments))
                }
            }
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.LLM_BAD_RESPONSE, e.message ?: "parse", e)
        }
    }

    private suspend fun runToolChatLoop(
        apiKey: String,
        model: String,
        userMessage: String,
        sessionHistory: List<ChatMessage>,
        memoryContext: String,
        toolExecutor: suspend (toolName: String, params: JSONObject) -> AgentResult<String>
    ): AgentResult<AgentChatResponse> {
        if (toolRegistry.allTools.isEmpty()) {
            return when (val chat = callGroqChatOnly(apiKey, model, userMessage, sessionHistory, memoryContext)) {
                is AgentResult.Success -> AgentResult.Success(AgentChatResponse(chat.value))
                is AgentResult.Error -> chat
            }
        }

        val messages = buildInitialMessages(
            buildSystemPrompt(memoryContext, includeTools = true) +
                "\nYou are in the Agent Chat interface. Execute actions via tools, then summarize what you did. " +
                "Chain tools when needed (web_search then save_note). Finish in 2-3 tool steps.",
            sessionHistory,
            userMessage
        )

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("tools", toolRegistry.toGroqToolSchema())
                put("tool_choice", "auto")
                put("temperature", 0.2)
            }
            val rawResult = executeRequest(apiKey, model, body) { raw -> AgentResult.Success(raw) }
            val raw = when (rawResult) {
                is AgentResult.Success -> rawResult.value
                is AgentResult.Error -> return rawResult
            }

            when (val turn = parseGroqTurn(raw)) {
                is GroqTurn.Text -> {
                    if (turn.content.isBlank()) {
                        return AgentResult.Error(ErrorCode.LLM_EMPTY_RESPONSE, "Empty agent chat response")
                    }
                    val pending = ContactCallSession.getPending()
                    return AgentResult.Success(
                        AgentChatResponse(
                            text = turn.content,
                            contactChoices = ContactCallSession.toContactChoices(),
                            pendingQuery = pending?.query,
                            pendingSessionId = pending?.id
                        )
                    )
                }
                is GroqTurn.ToolCalls -> {
                    val results = mutableListOf<String>()
                    for (call in turn.calls) {
                        val execResult = toolExecutor(call.name, call.arguments)
                        if (call.name == "call_contact" && !call.arguments.optBoolean("confirmed", false) && execResult is AgentResult.Success) {
                            val pending = ContactCallSession.getPending()
                            val choices = ContactCallSession.toContactChoices()
                            val text = if (choices.isNullOrEmpty()) {
                                execResult.value
                            } else {
                                ContactLookup.formatConfirmationPrompt(
                                    pending?.query ?: call.arguments.optString("contact_name"),
                                    pending?.candidates.orEmpty()
                                )
                            }
                            return AgentResult.Success(
                                AgentChatResponse(
                                    text = text,
                                    contactChoices = choices,
                                    pendingQuery = pending?.query,
                                    pendingSessionId = pending?.id
                                )
                            )
                        }
                        val toolContent = when (execResult) {
                            is AgentResult.Success -> execResult.value.ifBlank { "Done." }
                            is AgentResult.Error -> "Error [${execResult.code}]: ${execResult.message}"
                        }
                        results.add(toolContent)
                    }
                    appendToolExchange(messages, turn.calls, results)
                }
            }
        }
        return AgentResult.Error(ErrorCode.LLM_BAD_RESPONSE, "Too many tool round-trips in one chat turn")
    }

    private suspend fun runVoiceToolLoop(
        apiKey: String,
        model: String,
        userUtterance: String,
        sessionHistory: List<ChatMessage>,
        memoryContext: String,
        toolExecutor: suspend (toolName: String, params: JSONObject) -> AgentResult<String>
    ): AgentResult<String> {
        val messages = buildInitialMessages(
            buildSystemPrompt(memoryContext, includeTools = true) +
                "\nVoice mode: chain tools when needed, then give a brief spoken summary.",
            sessionHistory,
            userUtterance
        )

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("tools", toolRegistry.toGroqToolSchema())
                put("tool_choice", "auto")
                put("temperature", 0.2)
            }
            val rawResult = executeRequest(apiKey, model, body) { raw -> AgentResult.Success(raw) }
            val raw = when (rawResult) {
                is AgentResult.Success -> rawResult.value
                is AgentResult.Error -> return rawResult
            }

            when (val turn = parseGroqTurn(raw)) {
                is GroqTurn.Text -> {
                    if (turn.content.isBlank()) {
                        return AgentResult.Error(ErrorCode.LLM_EMPTY_RESPONSE, "Empty voice response")
                    }
                    return AgentResult.Success(turn.content)
                }
                is GroqTurn.ToolCalls -> {
                    val results = mutableListOf<String>()
                    for (call in turn.calls) {
                        val execResult = toolExecutor(call.name, call.arguments)
                        if (call.name == "call_contact" && !call.arguments.optBoolean("confirmed", false) && execResult is AgentResult.Success) {
                            val pending = ContactCallSession.getPending()
                            val choices = ContactCallSession.toContactChoices()
                            val text = if (choices.isNullOrEmpty()) {
                                execResult.value
                            } else {
                                ContactLookup.formatConfirmationPrompt(
                                    pending?.query ?: call.arguments.optString("contact_name"),
                                    pending?.candidates.orEmpty()
                                )
                            }
                            return AgentResult.Success(text)
                        }
                        val toolContent = when (execResult) {
                            is AgentResult.Success -> execResult.value.ifBlank { "Done." }
                            is AgentResult.Error -> "Error [${execResult.code}]: ${execResult.message}"
                        }
                        results.add(toolContent)
                    }
                    appendToolExchange(messages, turn.calls, results)
                }
            }
        }
        return AgentResult.Error(ErrorCode.LLM_BAD_RESPONSE, "Too many tool steps — try a simpler request")
    }

    private fun buildInitialMessages(
        systemPrompt: String,
        sessionHistory: List<ChatMessage>,
        userUtterance: String
    ): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", systemPrompt))
        sessionHistory.forEach { msg ->
            put(JSONObject().put("role", msg.role).put("content", msg.content))
        }
        put(JSONObject().put("role", "user").put("content", userUtterance))
    }

    private fun appendToolExchange(messages: JSONArray, calls: List<ToolCallItem>, results: List<String>) {
        messages.put(
            JSONObject().apply {
                put("role", "assistant")
                put("content", JSONObject.NULL)
                put("tool_calls", JSONArray().apply {
                    calls.forEach { call ->
                        put(
                            JSONObject().apply {
                                put("id", call.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", call.name)
                                    put("arguments", call.arguments.toString())
                                })
                            }
                        )
                    }
                })
            }
        )
        for (i in calls.indices) {
            val call = calls[i]
            val content = results.getOrNull(i) ?: "Done."
            messages.put(
                JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", call.id)
                    put("content", content.take(1500))
                }
            )
        }
    }

    private data class ToolCallItem(val id: String, val name: String, val arguments: JSONObject)

    private sealed class GroqTurn {
        data class Text(val content: String) : GroqTurn()
        data class ToolCalls(val calls: List<ToolCallItem>) : GroqTurn()
    }

    private fun parseGroqTurn(raw: String): GroqTurn {
        val message = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val toolCallsArr = message.optJSONArray("tool_calls")
        if (toolCallsArr != null && toolCallsArr.length() > 0) {
            val list = mutableListOf<ToolCallItem>()
            for (i in 0 until toolCallsArr.length()) {
                val call = toolCallsArr.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                val rawArgs = fn.optString("arguments", "{}")
                val argsObj = try {
                    JSONObject(rawArgs)
                } catch (_: Exception) {
                    JSONObject()
                }
                list.add(
                    ToolCallItem(
                        id = call.optString("id", "call_${System.currentTimeMillis()}_$i"),
                        name = fn.optString("name", ""),
                        arguments = argsObj
                    )
                )
            }
            if (list.isNotEmpty()) return GroqTurn.ToolCalls(list)
        }
        return GroqTurn.Text(message.optString("content", ""))
    }

    companion object {
        private const val TAG = "GroqApiClient"
        private const val MAX_TOOL_ROUNDS = 4

        val DEFAULT_MODELS = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "qwen/qwen3.6-27b"
        )

        private const val BASE_PROMPT = """
You are A3, a concise on-device voice assistant. Call tools instead of describing actions.
Never invent tool names. Reply briefly for TTS when not using a tool.
"""
    }
}
