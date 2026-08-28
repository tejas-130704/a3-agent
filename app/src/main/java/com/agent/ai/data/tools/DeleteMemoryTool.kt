package com.agent.ai.data.tools

import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import com.agent.ai.data.memory.AgentMemoryHub
import com.agent.ai.data.memory.MemoryTopic
import org.json.JSONObject

/** Delete memory bubbles or summaries by id or text match (voice/UI). */
class DeleteMemoryTool : AgentTool {

    override val name = "delete_memory"
    override val description =
        "Delete stored memory nodes or conversation summaries. " +
        "Use when user asks to forget/remove something from memory."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Text to match in label/summary, or exact bubble id"
            },
            "memory_type": {
              "type": "string",
              "enum": ["bubble", "summary", "all"],
              "description": "What to delete — default bubble"
            },
            "topic": {
              "type": "string",
              "description": "Optional topic filter e.g. SPOTIFY, WHATSAPP"
            }
          },
          "required": ["query"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        if (!AgentMemoryHub.isReady()) {
            return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Memory not initialized")
        }
        val query = params.optString("query", "").trim()
        if (query.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "query was empty")
        }

        val type = params.optString("memory_type", "bubble").lowercase()
        val topic = params.optString("topic", "").trim().uppercase().takeIf { it.isNotEmpty() }
            ?.let {
                try {
                    MemoryTopic.valueOf(it)
                } catch (_: Exception) {
                    null
                }
            }

        val repo = AgentMemoryHub.repository
        var deleted = 0

        when (type) {
            "summary" -> {
                if (repo.deleteSummaryById(query)) deleted = 1
                else deleted = repo.deleteSummariesMatching(query)
            }
            "all" -> {
                if (repo.deleteBubbleById(query)) deleted++
                else deleted += repo.deleteBubblesMatching(query, topic)
                if (repo.deleteSummaryById(query)) deleted++
                else deleted += repo.deleteSummariesMatching(query)
            }
            else -> {
                if (repo.deleteBubbleById(query)) deleted = 1
                else deleted = repo.deleteBubblesMatching(query, topic)
            }
        }

        return if (deleted > 0) {
            AgentResult.Success("Deleted $deleted memory item${if (deleted == 1) "" else "s"} matching \"$query\"")
        } else {
            AgentResult.Error(ErrorCode.TOOL_TARGET_NOT_FOUND, "No memory matched \"$query\"")
        }
    }
}
