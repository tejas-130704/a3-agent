package com.agent.ai.data.tools

import android.content.Context
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import com.agent.ai.data.notes.AgentNotesRepository
import org.json.JSONObject

/**
 * Saves text directly to the agent's headless backend notes storage without opening any app screen.
 */
class SaveNoteTool(private val context: Context) : AgentTool {

    private val notesRepo = AgentNotesRepository(context)

    override val name = "save_note"
    override val description =
        "Save text to backend Notes. Performs silently in the background without opening any app UI. " +
        "Use when user says 'save in notes', 'remember this in notes', 'note down: ...', etc."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "content": {
              "type": "string",
              "description": "Note body text to save"
            },
            "title": {
              "type": "string",
              "description": "Optional note title"
            }
          },
          "required": ["content"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "content was empty")
        }
        val title = params.optString("title", "").trim()

        val saved = notesRepo.addNote(title, content)
        val noteLabel = if (saved.title.isNotBlank()) "\"${saved.title}\"" else "\"${saved.content.take(30)}\""
        return AgentResult.Success("Saved note $noteLabel in notes.")
    }
}
