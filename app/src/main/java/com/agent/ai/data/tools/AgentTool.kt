package com.agent.ai.data.tools

import com.agent.ai.core.AgentResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every capability the agent can perform implements this. To add a new tool
 * for V2 (Spotify, WhatsApp, etc.): implement this interface, register it in
 * ToolRegistry.allTools — nothing else in the app needs to change.
 */
interface AgentTool {
    /** Must match the "name" the LLM will use in tool_calls. */
    val name: String

    /** Shown to the LLM so it knows when to call this tool. */
    val description: String

    /** JSON Schema for this tool's parameters, OpenAI/Groq function-calling format. */
    val parametersSchema: JSONObject

    /** Execute with LLM-provided params. Must never throw — return AgentResult.Error instead. */
    suspend fun execute(params: JSONObject): AgentResult<String>
}

class ToolRegistry(val allTools: List<AgentTool>) {

    private val byName = allTools.associateBy { it.name }

    fun find(name: String): AgentTool? = byName[name]

    /** Builds the "tools" array Groq's API expects. */
    fun toGroqToolSchema(): JSONArray {
        val arr = JSONArray()
        for (tool in allTools) {
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema)
                })
            })
        }
        return arr
    }
}
