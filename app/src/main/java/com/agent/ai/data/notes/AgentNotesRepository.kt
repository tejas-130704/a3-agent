package com.agent.ai.data.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class AgentNote(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Headless local notes storage for the agent.
 * Saves and queries notes entirely in the backend without launching any app UI.
 */
class AgentNotesRepository(context: Context) {

    private val file = File(context.filesDir, "agent_notes.json")
    private val lock = ReentrantReadWriteLock()
    private val notes = mutableListOf<AgentNote>()

    init {
        load()
    }

    fun getAllNotes(): List<AgentNote> = lock.read { notes.sortedByDescending { it.timestamp } }

    fun addNote(title: String, content: String): AgentNote {
        val note = AgentNote(
            title = title.trim(),
            content = content.trim()
        )
        lock.write {
            notes.add(0, note)
            save()
        }
        return note
    }

    fun searchNotes(query: String): List<AgentNote> = lock.read {
        if (query.isBlank()) notes.sortedByDescending { it.timestamp }
        else notes.filter {
            it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
        }.sortedByDescending { it.timestamp }
    }

    private fun load() {
        lock.write {
            notes.clear()
            if (!file.exists()) return
            try {
                val json = file.readText()
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    notes.add(
                        AgentNote(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            title = obj.optString("title", ""),
                            content = obj.optString("content", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (note in notes) {
                arr.put(
                    JSONObject().apply {
                        put("id", note.id)
                        put("title", note.title)
                        put("content", note.content)
                        put("timestamp", note.timestamp)
                    }
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }
}
