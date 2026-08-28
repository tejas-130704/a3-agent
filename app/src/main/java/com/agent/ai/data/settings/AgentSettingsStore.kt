package com.agent.ai.data.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Persists API keys and the active Groq key index on device.
 * Keys saved here override gradle.properties / BuildConfig at runtime.
 */
class AgentSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getGroqKeys(): List<String> {
        val raw = prefs.getString(KEY_GROQ_KEYS, null) ?: return emptyList()
        return parseKeyList(raw)
    }

    fun setGroqKeys(keys: List<String>) {
        val cleaned = sanitizeKeys(keys)
        prefs.edit()
            .putString(KEY_GROQ_KEYS, JSONArray(cleaned).toString())
            .apply()
        val primary = getPrimaryGroqKeyIndex()
        if (cleaned.isEmpty()) {
            setPrimaryGroqKeyIndex(0)
        } else if (primary >= cleaned.size) {
            setPrimaryGroqKeyIndex(0)
        }
    }

    fun addGroqKey(keyInput: String): List<String> {
        val parsed = sanitizeKeys(listOf(keyInput))
        if (parsed.isEmpty()) return getGroqKeys()
        val updated = (getGroqKeys() + parsed).distinct()
        setGroqKeys(updated)
        return updated
    }

    fun removeGroqKey(index: Int): List<String> {
        val current = getGroqKeys().toMutableList()
        if (index !in current.indices) return current
        current.removeAt(index)
        setGroqKeys(current)
        val primary = getPrimaryGroqKeyIndex()
        if (primary > index) setPrimaryGroqKeyIndex(primary - 1)
        else if (primary >= current.size && current.isNotEmpty()) setPrimaryGroqKeyIndex(0)
        return current
    }

    fun getPrimaryGroqKeyIndex(): Int = prefs.getInt(KEY_GROQ_PRIMARY_INDEX, 0)

    fun setPrimaryGroqKeyIndex(index: Int) {
        prefs.edit().putInt(KEY_GROQ_PRIMARY_INDEX, index.coerceAtLeast(0)).apply()
    }

    fun getPicovoiceAccessKey(): String = prefs.getString(KEY_PICOVOICE, "").orEmpty()

    fun setPicovoiceAccessKey(key: String) {
        prefs.edit().putString(KEY_PICOVOICE, key.trim().trim('"', '\'')).apply()
    }

    /** Keys in priority order: primary first, then the rest. */
    fun getGroqKeysInPriorityOrder(): List<IndexedKey> {
        val keys = getGroqKeys()
        if (keys.isEmpty()) return emptyList()
        val primary = getPrimaryGroqKeyIndex().coerceIn(0, keys.lastIndex)
        val ordered = mutableListOf<IndexedKey>()
        for (i in keys.indices) {
            val idx = (primary + i) % keys.size
            ordered.add(IndexedKey(index = idx, value = keys[idx]))
        }
        return ordered
    }

    data class IndexedKey(val index: Int, val value: String)

    companion object {
        private const val PREFS_NAME = "agent_settings"
        private const val KEY_GROQ_KEYS = "groq_keys_json"
        private const val KEY_GROQ_PRIMARY_INDEX = "groq_primary_index"
        private const val KEY_PICOVOICE = "picovoice_access_key"

        /** Split raw strings by commas, semicolons, or newlines and clean quotes and spaces. */
        fun sanitizeKeys(rawInputs: List<String>): List<String> {
            return rawInputs
                .flatMap { it.split(Regex("[,;\\r\\n\\s]+")) }
                .map { cleanSingleKey(it) }
                .filter { isValidKey(it) }
                .distinct()
        }

        fun cleanSingleKey(key: String): String {
            return key.trim().trim('"', '\'', '`')
        }

        fun isValidKey(key: String): Boolean {
            if (key.length < 10) return false
            if (key.contains("your_groq", ignoreCase = true)) return false
            // API keys should only contain valid alphanumeric or dash/underscore chars
            return key.matches(Regex("^[A-Za-z0-9_\\-]+$"))
        }

        private fun parseKeyList(raw: String): List<String> {
            return try {
                val arr = JSONArray(raw)
                val rawList = buildList {
                    for (i in 0 until arr.length()) {
                        val k = arr.optString(i, "").trim()
                        if (k.isNotEmpty()) add(k)
                    }
                }
                sanitizeKeys(rawList)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
