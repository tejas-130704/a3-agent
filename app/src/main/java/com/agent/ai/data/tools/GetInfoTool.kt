package com.agent.ai.data.tools

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Universal information fetcher tool for Android device components.
 * Retrieves live data for alarms, calendar events, contacts, system settings,
 * installed apps, device status, volume levels, and notifications.
 */
class GetInfoTool(private val context: Context) : AgentTool {

    override val name = "get_info"
    override val description =
        "Fetch real-time information about device components (alarms, calendar, contacts, settings, apps, device, volume, notifications). " +
        "Use when user asks 'what alarms are set?', 'what events do I have today?', 'is WhatsApp installed?', 'what is my WiFi/volume/battery status?'."

    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "component": {
              "type": "string",
              "enum": ["alarms", "calendar", "contacts", "settings", "apps", "device", "volume", "notifications", "notes"],
              "description": "The mobile component or subsystem to query"
            },
            "query": {
              "type": "string",
              "description": "Optional search term, contact name, app name, or keyword"
            },
            "timeframe": {
              "type": "string",
              "enum": ["today", "tomorrow", "week", "all"],
              "description": "Optional time filter for calendar events (default: today)"
            }
          },
          "required": ["component"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val component = params.optString("component", "").lowercase().trim()
        val query = params.optString("query", "").trim()
        val timeframe = params.optString("timeframe", "today").lowercase().trim()

        return when (component) {
            "alarms", "alarm", "clock" -> fetchAlarmInfo()
            "calendar", "events", "schedule" -> fetchCalendarInfo(timeframe, query)
            "contacts", "contact", "phonebook" -> fetchContactInfo(query)
            "settings", "setting", "toggles" -> fetchSettingsInfo(query)
            "apps", "app", "installed_apps" -> fetchAppInfo(query)
            "volume", "audio", "sound" -> fetchVolumeInfo()
            "device", "battery", "storage", "network", "system" -> fetchDeviceInfo()
            "notifications", "notification" -> fetchNotificationInfo(query)
            "notes", "note" -> fetchNotesInfo(query)
            "" -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "component parameter is required")
            else -> AgentResult.Error(
                ErrorCode.TOOL_INVALID_PARAMS,
                "Unknown component '$component'. Supported: alarms, calendar, contacts, settings, apps, device, volume, notifications, notes."
            )
        }
    }

    private fun fetchAlarmInfo(): AgentResult<String> {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "AlarmManager unavailable")

        val nextAlarm = alarmManager.nextAlarmClock
        if (nextAlarm == null) {
            return AgentResult.Success("No upcoming alarms are scheduled.")
        }

        val triggerTime = nextAlarm.triggerTime
        val now = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dateFmt = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())

        val formattedTime = timeFmt.format(Date(triggerTime))
        val formattedDate = dateFmt.format(Date(triggerTime))

        val diffMs = triggerTime - now
        val relTime = if (diffMs > 0) {
            val totalMins = diffMs / 60_000
            val hours = totalMins / 60
            val mins = totalMins % 60
            when {
                hours > 0 && mins > 0 -> " (in ${hours}h ${mins}m)"
                hours > 0 -> " (in ${hours}h)"
                mins > 0 -> " (in ${mins}m)"
                else -> " (due now)"
            }
        } else ""

        return AgentResult.Success("Next alarm is set for $formattedTime on $formattedDate$relTime.")
    }

    private fun fetchCalendarInfo(timeframe: String, query: String): AgentResult<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return AgentResult.Error(
                ErrorCode.TOOL_PERMISSION_DENIED,
                "Calendar read permission not granted — grant Calendar access in App Settings"
            )
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val (startMillis, endMillis, label) = when (timeframe) {
            "tomorrow" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val end = cal.timeInMillis - 1
                Triple(start, end, "tomorrow")
            }
            "week" -> {
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 7)
                val end = cal.timeInMillis - 1
                Triple(start, end, "the next 7 days")
            }
            "all" -> {
                val start = cal.timeInMillis - (30L * 86_400_000L)
                val end = cal.timeInMillis + (60L * 86_400_000L)
                Triple(start, end, "all upcoming dates")
            }
            else -> { // "today"
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val end = cal.timeInMillis - 1
                Triple(start, end, "today")
            }
        }

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )

        val events = mutableListOf<String>()
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dateFmt = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())

        try {
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)

                while (cursor.moveToNext() && events.size < 15) {
                    val title = cursor.getString(titleIdx) ?: "Untitled event"
                    if (query.isNotEmpty() && !title.contains(query, ignoreCase = true)) continue

                    val begin = cursor.getLong(beginIdx)
                    val end = cursor.getLong(endIdx)
                    val loc = cursor.getString(locIdx)
                    val isAllDay = cursor.getInt(allDayIdx) == 1

                    val timeStr = if (isAllDay) {
                        "All day (${dateFmt.format(Date(begin))})"
                    } else {
                        "${dateFmt.format(Date(begin))} from ${timeFmt.format(Date(begin))} to ${timeFmt.format(Date(end))}"
                    }

                    val locStr = if (!loc.isNullOrBlank()) " at $loc" else ""
                    events.add("• $title: $timeStr$locStr")
                }
            }
        } catch (e: Exception) {
            return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Failed to query calendar: ${e.message}", e)
        }

        if (events.isEmpty()) {
            return AgentResult.Success(
                if (query.isNotEmpty()) "No calendar events matching '$query' found for $label."
                else "No calendar events scheduled for $label."
            )
        }

        return AgentResult.Success("Calendar events for $label (${events.size}):\n" + events.joinToString("\n"))
    }

    private fun fetchContactInfo(query: String): AgentResult<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return AgentResult.Error(
                ErrorCode.TOOL_PERMISSION_DENIED,
                "Contacts read permission not granted — grant Contacts access in App Settings"
            )
        }

        if (query.isBlank()) {
            val frequent = ContactLookup.topFrequentContacts(context, 10)
            if (frequent.isEmpty()) return AgentResult.Success("No contacts found.")
            val list = frequent.joinToString("\n") { "• ${it.displayName}: ${it.phone}" }
            return AgentResult.Success("Top contacts:\n$list")
        }

        val matches = ContactLookup.findTopMatches(context, query, limit = 5)
        if (matches.isEmpty()) {
            return AgentResult.Success("No contact found matching '$query'.")
        }

        val list = matches.joinToString("\n") { "• ${it.displayName}: ${it.phone}" }
        return AgentResult.Success("Contacts matching '$query':\n$list")
    }

    private fun fetchSettingsInfo(query: String): AgentResult<String> {
        val details = mutableListOf<String>()

        // 1. Audio / Ringer
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            val ringerMode = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "Normal"
                else -> "Unknown"
            }
            val mediaVol = ((am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)) * 100).toInt()
            details.add("Ringer Mode: $ringerMode")
            details.add("Media Volume: $mediaVol%")
        }

        // 2. DND Status
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dnd = when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE -> "Total silence"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Alarms only"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority only"
                NotificationManager.INTERRUPTION_FILTER_ALL -> "Off (Normal)"
                else -> "Unknown"
            }
            details.add("Do Not Disturb (DND): $dnd")
        }

        // 3. Network & WiFi
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        if (isWifi) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ssid = wm?.connectionInfo?.ssid?.replace("\"", "") ?: "Connected"
            details.add("Internet: WiFi ($ssid)")
        } else if (isCellular) {
            details.add("Internet: Mobile Data")
        } else {
            details.add("Internet: Disconnected")
        }

        // 4. Bluetooth
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null) {
            details.add("Bluetooth: ${if (btAdapter.isEnabled) "ON" else "OFF"}")
        }

        // 5. Airplane Mode
        val airplaneOn = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        details.add("Airplane Mode: ${if (airplaneOn) "ON" else "OFF"}")

        // 6. Battery
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (batteryPct >= 0) {
            details.add("Battery: $batteryPct%")
        }

        return AgentResult.Success("Device Settings Status:\n" + details.joinToString("\n"))
    }

    private fun fetchAppInfo(query: String): AgentResult<String> {
        if (query.isBlank()) {
            val apps = AppLookup.loadLauncherApps(context)
            return AgentResult.Success("Found ${apps.size} installed launchable apps on device.")
        }

        val resolved = AppLookup.resolveInstalled(context, query)
        val pm = context.packageManager
        if (resolved == null) {
            val knownPkg = AppLookup.knownPackageFor(query) ?: query
            return AgentResult.Success("App '$query' (package: $knownPkg) is NOT installed on this device.")
        }
        return try {
            val pkgInfo = pm.getPackageInfo(resolved.packageName, 0)
            val appName = resolved.label.ifBlank { pm.getApplicationLabel(pkgInfo.applicationInfo).toString() }
            val versionName = pkgInfo.versionName ?: "N/A"
            val isSystem = (pkgInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

            AgentResult.Success(
                "App Info for '$query':\n" +
                "• Name: $appName\n" +
                "• Package: ${resolved.packageName}\n" +
                "• Version: $versionName\n" +
                "• Type: ${if (isSystem) "System app" else "User installed app"}\n" +
                "• Status: Installed & available"
            )
        } catch (_: Exception) {
            AgentResult.Success("App '$query' is installed (package: ${resolved.packageName}).")
        }
    }

    private fun fetchVolumeInfo(): AgentResult<String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "AudioManager unavailable")

        fun pct(stream: Int): Int {
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val current = am.getStreamVolume(stream)
            return ((current.toFloat() / max) * 100).toInt()
        }

        val media = pct(AudioManager.STREAM_MUSIC)
        val ring = pct(AudioManager.STREAM_RING)
        val alarm = pct(AudioManager.STREAM_ALARM)
        val voice = pct(AudioManager.STREAM_VOICE_CALL)
        val notif = pct(AudioManager.STREAM_NOTIFICATION)

        return AgentResult.Success(
            "Volume Status:\n" +
            "• Media: $media%\n" +
            "• Ring: $ring%\n" +
            "• Alarm: $alarm%\n" +
            "• Notifications: $notif%\n" +
            "• Voice Call: $voice%"
        )
    }

    private fun fetchDeviceInfo(): AgentResult<String> {
        val details = mutableListOf<String>()

        // Battery
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = bm?.isCharging == true
        if (batteryPct >= 0) {
            details.add("Battery: $batteryPct% (${if (isCharging) "Charging" else "Discharging"})")
        }

        // Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L * 1024L)
        val totalGb = (stat.blockCountLong * stat.blockSizeLong) / (1024L * 1024L * 1024L)
        details.add("Internal Storage: ${freeGb}GB free / ${totalGb}GB total")

        // OS & Model
        details.add("Device: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
        details.add("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        return AgentResult.Success("Device Status:\n" + details.joinToString("\n"))
    }

    private suspend fun fetchNotificationInfo(query: String): AgentResult<String> {
        val tool = ReadNotificationsTool(context)
        val params = JSONObject().put("count", 5)
        return tool.execute(params)
    }

    private fun fetchNotesInfo(query: String): AgentResult<String> {
        val notesRepo = com.agent.ai.data.notes.AgentNotesRepository(context)
        val list = notesRepo.searchNotes(query)
        if (list.isEmpty()) {
            return AgentResult.Success(
                if (query.isNotBlank()) "No saved notes matching '$query' found."
                else "No saved notes found. You can say 'save in notes: ...' to create one."
            )
        }
        val dateFmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val formatted = list.take(10).joinToString("\n") {
            val titlePart = if (it.title.isNotBlank()) "${it.title}: " else ""
            "• ${titlePart}${it.content} (${dateFmt.format(Date(it.timestamp))})"
        }
        return AgentResult.Success("Saved Notes (${list.size}):\n$formatted")
    }
}
