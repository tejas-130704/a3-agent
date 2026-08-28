package com.agent.ai.data.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class AgentMemoryRepository(context: Context) {

    private val file = File(context.filesDir, "memory_sky.json")
    private val lock = ReentrantReadWriteLock()
    private val bubbles = mutableListOf<MemoryBubble>()
    private val summaries = mutableListOf<ConversationSummary>()

    init {
        load()
    }

    fun allBubbles(): List<MemoryBubble> = lock.read { bubbles.sortedByDescending { it.frequency } }

    fun bubblesForTopic(topic: MemoryTopic): List<MemoryBubble> =
        lock.read { bubbles.filter { it.topic == topic }.sortedByDescending { it.frequency } }

    fun recentSummaries(limit: Int = 30): List<ConversationSummary> =
        lock.read { summaries.sortedByDescending { it.timestamp }.take(limit) }

    fun upsertBubble(
        topic: MemoryTopic,
        subTopic: String,
        label: String,
        summary: String,
        metadata: Map<String, String> = emptyMap()
    ) {
        lock.write {
            val key = "${topic.name}|$subTopic|$label".lowercase()
            val idx = bubbles.indexOfFirst {
                "${it.topic.name}|${it.subTopic}|${it.label}".lowercase() == key
            }
            if (idx >= 0) {
                val existing = bubbles[idx]
                bubbles[idx] = existing.copy(
                    frequency = existing.frequency + 1,
                    summary = summary.ifBlank { existing.summary },
                    lastUpdated = System.currentTimeMillis(),
                    metadata = existing.metadata + metadata
                )
            } else {
                bubbles.add(
                    MemoryBubble(
                        topic = topic,
                        subTopic = subTopic,
                        label = label,
                        summary = summary,
                        metadata = metadata
                    )
                )
            }
            persist()
        }
    }

    fun addSummary(summary: ConversationSummary) {
        lock.write {
            summaries.add(0, summary)
            if (summaries.size > 200) summaries.removeAt(summaries.lastIndex)
            persist()
        }
    }

    /** Compact text injected into Groq system prompt. */
    fun buildContextPrompt(): String = lock.read {
        if (bubbles.isEmpty() && summaries.isEmpty()) return ""

        val sb = StringBuilder("USER MEMORY (use for ambiguous follow-ups like \"play him again\" or \"message her\"):\n")
        MemoryTopic.entries.forEach { topic ->
            val topicBubbles = bubbles.filter { it.topic == topic }.take(5)
            if (topicBubbles.isNotEmpty()) {
                sb.append("\n${topic.emoji} ${topic.displayName}:\n")
                topicBubbles.forEach { b ->
                    sb.append("  • [${b.subTopic}] ${b.label} (×${b.frequency}): ${b.summary}\n")
                }
            }
        }
        summaries.take(3).forEach { s ->
            sb.append("\nRecent: \"${s.userUtterance}\" → ${s.summary}\n")
        }
        sb.toString()
    }

    fun topicStats(): Map<MemoryTopic, Int> = lock.read {
        MemoryTopic.entries.associateWith { topic -> bubbles.count { it.topic == topic } }
    }

    fun deleteBubbleById(id: String): Boolean = lock.write {
        val removed = bubbles.removeAll { it.id == id }
        if (removed) persist()
        removed
    }

    /** Delete bubbles whose label or summary contains [query] (case-insensitive). */
    fun deleteBubblesMatching(query: String, topic: MemoryTopic? = null): Int = lock.write {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val before = bubbles.size
        bubbles.removeAll { bubble ->
            (topic == null || bubble.topic == topic) &&
                (bubble.label.lowercase().contains(q) ||
                    bubble.summary.lowercase().contains(q) ||
                    bubble.subTopic.lowercase().contains(q))
        }
        val removed = before - bubbles.size
        if (removed > 0) persist()
        removed
    }

    fun deleteSummaryById(id: String): Boolean = lock.write {
        val removed = summaries.removeAll { it.id == id }
        if (removed) persist()
        removed
    }

    fun deleteSummariesMatching(query: String): Int = lock.write {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val before = summaries.size
        summaries.removeAll { s ->
            s.summary.lowercase().contains(q) ||
                s.userUtterance.lowercase().contains(q) ||
                s.agentResponse.lowercase().contains(q)
        }
        val removed = before - summaries.size
        if (removed > 0) persist()
        removed
    }

    fun clearAll() {
        lock.write {
            bubbles.clear()
            summaries.clear()
            persist()
        }
    }

    private fun persist() {
        val root = JSONObject().apply {
            put("bubbles", JSONArray().apply { bubbles.forEach { put(it.toJson()) } })
            put("summaries", JSONArray().apply {
                summaries.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("timestamp", s.timestamp)
                        put("userUtterance", s.userUtterance)
                        put("agentResponse", s.agentResponse)
                        put("toolUsed", s.toolUsed)
                        put("summary", s.summary)
                        put("topics", JSONArray(s.topics.map { it.name }))
                    })
                }
            })
        }
        file.writeText(root.toString(2))
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            bubbles.clear()
            root.optJSONArray("bubbles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    bubbles.add(MemoryBubble.fromJson(arr.getJSONObject(i)))
                }
            }
            summaries.clear()
            root.optJSONArray("summaries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val topicsArr = o.optJSONArray("topics")
                    val topics = buildList {
                        if (topicsArr != null) {
                            for (j in 0 until topicsArr.length()) {
                                add(MemoryTopic.valueOf(topicsArr.getString(j)))
                            }
                        }
                    }
                    summaries.add(
                        ConversationSummary(
                            id = o.getString("id"),
                            timestamp = o.getLong("timestamp"),
                            userUtterance = o.getString("userUtterance"),
                            agentResponse = o.getString("agentResponse"),
                            toolUsed = o.optString("toolUsed").ifBlank { null },
                            summary = o.getString("summary"),
                            topics = topics
                        )
                    )
                }
            }
        } catch (_: Exception) {
            bubbles.clear()
            summaries.clear()
        }
    }
}
