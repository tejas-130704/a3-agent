package com.agent.ai.data.memory

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live session memory — keeps recent turns so follow-ups like
 * "play Arijit Singh" after "open Spotify" resolve with context.
 * Expires after [SESSION_TIMEOUT_MS] of inactivity.
 */
class SessionContext(
    private val maxMessages: Int = 20,
    private val sessionTimeoutMs: Long = SESSION_TIMEOUT_MS
) {
    private val messages = CopyOnWriteArrayList<ChatMessage>()
    @Volatile private var lastActivityMs: Long = System.currentTimeMillis()

    fun isActive(): Boolean =
        messages.isNotEmpty() && System.currentTimeMillis() - lastActivityMs < sessionTimeoutMs

    fun addUser(content: String) {
        touch()
        messages.add(ChatMessage("user", content))
        trim()
    }

    fun addAssistant(content: String) {
        touch()
        messages.add(ChatMessage("assistant", content))
        trim()
    }

    fun history(): List<ChatMessage> {
        if (!isActive()) {
            messages.clear()
            return emptyList()
        }
        return messages.toList()
    }

    fun clear() {
        messages.clear()
        lastActivityMs = 0L
    }

    fun recentSummary(): String {
        if (!isActive()) return ""
        return history().takeLast(6).joinToString("\n") { "${it.role}: ${it.content}" }
    }

    private fun touch() {
        lastActivityMs = System.currentTimeMillis()
    }

    private fun trim() {
        while (messages.size > maxMessages) messages.removeAt(0)
    }

    companion object {
        const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
