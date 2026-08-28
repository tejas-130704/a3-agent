package com.agent.ai.core.wakeword

import android.content.Context
import com.agent.ai.core.AgentResult
import com.agent.ai.data.settings.SettingsKeyResolver

/** Abstraction so the agent can run without wake word when it is not configured. */
interface WakeWordDetector {
    val isConfigured: Boolean
    fun start(): AgentResult<Unit>
    fun stop(): AgentResult<Unit>
}

/** Used when Picovoice key or .ppn models are missing — no mic capture, no errors. */
class NoOpWakeWordDetector : WakeWordDetector {
    override val isConfigured: Boolean = false
    override fun start(): AgentResult<Unit> = AgentResult.Success(Unit)
    override fun stop(): AgentResult<Unit> = AgentResult.Success(Unit)
}

object WakeWordConfig {
    fun isConfigured(context: Context): Boolean {
        val key = SettingsKeyResolver.picovoiceAccessKey(context)
        if (key.isBlank() || key.contains("your_picovoice", ignoreCase = true)) return false
        return PorcupineWakeWordDetector.KEYWORD_PATHS.all { path ->
            try {
                context.assets.open(path).use { true }
            } catch (_: Exception) {
                false
            }
        }
    }
}
