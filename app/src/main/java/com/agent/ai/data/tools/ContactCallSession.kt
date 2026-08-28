package com.agent.ai.data.tools

import com.agent.ai.data.ContactChoice
import java.util.UUID

/** Holds pending call disambiguation between voice/chat turns. */
object ContactCallSession {

    private const val TIMEOUT_MS = 5 * 60 * 1000L

    data class PendingCall(
        val id: String = UUID.randomUUID().toString(),
        val query: String,
        val candidates: List<ContactLookup.ContactMatch>,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var pending: PendingCall? = null

    fun setPending(query: String, candidates: List<ContactLookup.ContactMatch>): PendingCall {
        val p = PendingCall(query = query, candidates = candidates)
        pending = p
        com.agent.ai.core.AgentController.syncPendingCall()
        return p
    }

    fun getPending(): PendingCall? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - p.createdAtMs > TIMEOUT_MS) {
            pending = null
            return null
        }
        return p
    }

    fun isActive(pendingId: String?): Boolean {
        if (pendingId.isNullOrBlank()) return false
        return getPending()?.id == pendingId
    }

    fun clear() {
        pending = null
        com.agent.ai.core.AgentController.syncPendingCall()
    }

    fun buildContextPrompt(): String {
        val p = getPending() ?: return ""
        return buildString {
            appendLine("PENDING CALL CONFIRMATION for \"${p.query}\" — user must pick before dialing:")
            p.candidates.forEachIndexed { index, contact ->
                appendLine("${index + 1}. ${contact.displayName}")
            }
            append("When user says a number or name, call call_contact with confirmed=true and choice_index.")
        }
    }

    fun toContactChoices(): List<ContactChoice>? {
        val p = getPending() ?: return null
        return p.candidates.mapIndexed { index, match ->
            ContactChoice(index = index + 1, displayName = match.displayName)
        }
    }
}
