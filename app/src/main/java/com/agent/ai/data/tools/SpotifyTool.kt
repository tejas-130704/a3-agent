package com.agent.ai.data.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Controls Spotify playback via media keys (play/pause/skip) and opens Spotify URIs for search/play.
 * Requires Spotify installed; media keys affect whichever app owns the active media session (usually Spotify if recently playing).
 */
class SpotifyTool(private val context: Context) : AgentTool {

    override val name = "spotify_control"
    override val description = "Control Spotify: play, pause, next, previous, search, or play a Spotify URI."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "playback_command": {
              "type": "string",
              "enum": ["PLAY", "PAUSE", "NEXT", "PREV", "PLAY_SEARCH", "PLAY_URI"],
              "description": "Action to perform"
            },
            "query": { "type": "string", "description": "Search query for PLAY_SEARCH" },
            "uri": { "type": "string", "description": "Spotify URI or open.spotify.com URL for PLAY_URI" }
          },
          "required": ["playback_command"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        return when (params.optString("playback_command", "").uppercase()) {
            "PLAY" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Resumed playback")
            "PAUSE" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused playback")
            "NEXT" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipped to next track")
            "PREV" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Skipped to previous track")
            "PLAY_SEARCH" -> openSpotifyUri(buildSearchUri(params.optString("query", "")))
            "PLAY_URI" -> openSpotifyUri(normalizeUri(params.optString("uri", "")))
            "" -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "playback_command missing")
            else -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "Unknown playback_command")
        }
    }

    private fun buildSearchUri(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) throw IllegalArgumentException("query empty")
        return "spotify:search:${URLEncoder.encode(q, "UTF-8")}"
    }

    private fun normalizeUri(raw: String): String {
        val uri = raw.trim()
        if (uri.isEmpty()) throw IllegalArgumentException("uri empty")
        return when {
            uri.startsWith("spotify:") -> uri
            uri.contains("open.spotify.com") -> {
                // https://open.spotify.com/track/ID -> spotify:track:ID
                val path = Uri.parse(uri).pathSegments
                if (path.size >= 2) "spotify:${path[0]}:${path[1]}" else uri
            }
            else -> "spotify:$uri"
        }
    }

    private fun openSpotifyUri(spotifyUri: String): AgentResult<String> {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).apply {
                setPackage("com.spotify.music")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AgentResult.Success("Opened Spotify: $spotifyUri")
        } catch (e: ActivityNotFoundException) {
            try {
                val web = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(web)
                AgentResult.Success("Opened Spotify link in browser")
            } catch (e2: ActivityNotFoundException) {
                AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Spotify is not installed", e2)
            }
        } catch (e: IllegalArgumentException) {
            AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, e.message ?: "Invalid Spotify URI")
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Spotify open failed: ${e.message}", e)
        }
    }

    private fun dispatchMediaKey(keyCode: Int, successMsg: String): AgentResult<String> {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "AudioManager unavailable")
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            AgentResult.Success("$successMsg via media key")
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Media key dispatch failed: ${e.message}", e)
        }
    }
}
