package com.agent.ai.core.notifications

data class NotificationEntry(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long
) {
    fun speakableSummary(maxChars: Int = 220): String {
        val body = listOf(title, text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(". ")
        val summary = if (body.isBlank()) appLabel else "$appLabel. $body"
        return if (summary.length <= maxChars) summary else summary.take(maxChars).trimEnd() + "…"
    }
}
