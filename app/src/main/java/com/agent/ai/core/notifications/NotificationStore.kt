package com.agent.ai.core.notifications

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe rolling buffer of recent notifications. */
object NotificationStore {

    private const val MAX_ENTRIES = 120
    private val entries = ConcurrentHashMap<String, NotificationEntry>()

    fun upsert(entry: NotificationEntry) {
        entries[entry.key] = entry
        trim()
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }

    fun recent(limit: Int): List<NotificationEntry> =
        entries.values
            .sortedByDescending { it.postTimeMs }
            .take(limit.coerceAtLeast(1))

    private fun trim() {
        if (entries.size <= MAX_ENTRIES) return
        entries.values
            .sortedBy { it.postTimeMs }
            .take(entries.size - MAX_ENTRIES)
            .forEach { entries.remove(it.key) }
    }
}
