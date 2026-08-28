package com.agent.ai.data.settings

import android.content.Context
import com.agent.ai.BuildConfig

object SettingsKeyResolver {

    fun picovoiceAccessKey(context: Context): String {
        val store = AgentSettingsStore(context)
        val fromStore = store.getPicovoiceAccessKey().trim()
        if (fromStore.isNotEmpty()) return fromStore
        val fromBuild = BuildConfig.PICOVOICE_ACCESS_KEY.trim()
        if (fromBuild.isNotEmpty() && !fromBuild.contains("your_picovoice", ignoreCase = true)) {
            return fromBuild
        }
        return ""
    }

    fun groqKeyManager(context: Context): com.agent.ai.data.GroqKeyManager {
        return com.agent.ai.data.GroqKeyManager(
            store = AgentSettingsStore(context),
            buildConfigFallbackKey = BuildConfig.GROQ_API_KEY
        )
    }
}
