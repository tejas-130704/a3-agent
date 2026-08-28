package com.agent.ai.data

import android.util.Log
import com.agent.ai.core.ErrorCode
import com.agent.ai.data.settings.AgentSettingsStore

/**
 * Manages multiple Groq API keys with sticky primary selection.
 * On success the working key becomes primary; on key-specific failures we try the next key.
 */
class GroqKeyManager(
    private val store: AgentSettingsStore,
    private val buildConfigFallbackKey: String = ""
) {
    companion object {
        private const val TAG = "GroqKeyManager"

        fun isRotationEligible(code: ErrorCode): Boolean = when (code) {
            ErrorCode.LLM_AUTH_ERROR,
            ErrorCode.LLM_RATE_LIMITED,
            ErrorCode.LLM_NETWORK_ERROR -> true
            else -> false
        }
    }

    /** All keys to try this request, primary first. Includes BuildConfig fallback if store is empty. */
    fun keysForRequest(): List<AgentSettingsStore.IndexedKey> {
        val stored = store.getGroqKeysInPriorityOrder()
        if (stored.isNotEmpty()) return stored

        val fallbacks = AgentSettingsStore.sanitizeKeys(listOf(buildConfigFallbackKey))
        if (fallbacks.isNotEmpty()) {
            return fallbacks.mapIndexed { index, key ->
                AgentSettingsStore.IndexedKey(index = index, value = key)
            }
        }
        return emptyList()
    }

    fun onKeySuccess(used: AgentSettingsStore.IndexedKey) {
        if (store.getGroqKeys().isNotEmpty()) {
            store.setPrimaryGroqKeyIndex(used.index)
            Log.i(TAG, "Groq key #${used.index} succeeded — now primary")
        }
    }

    fun hasAnyKey(): Boolean = keysForRequest().isNotEmpty()
}
