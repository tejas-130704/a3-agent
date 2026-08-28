package com.agent.ai.data.tools

import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject

/** Search the web and return text the LLM can summarize or save to notes. */
class WebSearchTool : AgentTool {

    override val name = "web_search"
    override val description =
        "Search the internet for facts, processes, news, or how-to info. " +
        "Returns snippets to summarize. Chain with save_note when user wants results stored in Notes."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Search query e.g. 'M.Tech admission process IIT'"
            },
            "max_results": {
              "type": "integer",
              "description": "Max snippet count hint (default 5)"
            }
          },
          "required": ["query"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val query = params.optString("query", "").trim()
        if (query.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "query was empty")
        }
        val max = params.optInt("max_results", 5).coerceIn(1, 10)
        return try {
            val results = WebSearchClient.search(query, max)
            AgentResult.Success("Web search results for \"$query\":\n\n$results")
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Web search failed: ${e.message}", e)
        }
    }
}
