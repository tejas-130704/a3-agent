package com.agent.ai.data.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Adds an event to the device's primary calendar. Requires WRITE_CALENDAR. */
class CalendarTool(private val context: Context) : AgentTool {

    override val name = "add_calendar_event"
    override val description = "Add an event to the calendar."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "title": { "type": "string" },
            "start_iso": { "type": "string", "description": "ISO-8601 start time, e.g. 2026-08-27T15:00:00" },
            "duration_minutes": { "type": "integer", "description": "Defaults to 60 if omitted" }
          },
          "required": ["title", "start_iso"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val title = params.optString("title", "").trim()
        val startIso = params.optString("start_iso", "")
        val durationMin = if (params.has("duration_minutes")) params.optInt("duration_minutes") else 60

        if (title.isEmpty() || startIso.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "title or start_iso missing")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return AgentResult.Error(ErrorCode.TOOL_PERMISSION_DENIED, "WRITE_CALENDAR not granted")
        }

        val startMillis = try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            fmt.parse(startIso)?.time
                ?: return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "start_iso could not be parsed: '$startIso'")
        } catch (e: Exception) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "start_iso parse failure: ${e.message}", e)
        }

        val endMillis = startMillis + durationMin * 60_000L

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, primaryCalendarId() ?: 1L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "ContentResolver.insert returned null URI")
            AgentResult.Success("Added '$title' to calendar (${uri.lastPathSegment})")
        } catch (e: SecurityException) {
            AgentResult.Error(ErrorCode.TOOL_PERMISSION_DENIED, "Calendar provider rejected insert at runtime", e)
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Calendar insert failed: ${e.message}", e)
        }
    }

    private fun primaryCalendarId(): Long? {
        val cr = context.contentResolver
        // First try primary calendar
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null, null
        )?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        // Fallback: any available calendar
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null, null, null
        )?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return null
    }
}
