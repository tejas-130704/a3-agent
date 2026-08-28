package com.agent.ai.data.tools

import android.content.Context

/** Shared tool registry used by foreground service and Agent chat. */
object ToolRegistryFactory {

    fun create(context: Context): ToolRegistry = ToolRegistry(
        listOf(
            AlarmTool(context),
            TimerTool(context),
            DialerTool(context),
            CalendarTool(context),
            SettingsTool(context),
            WhatsAppTool(context),
            TelegramTool(context),
            SpotifyTool(context),
            OpenAppTool(context),
            UiAutomationTool(),
            ReadNotificationsTool(context),
            ManageAppTool(context),
            SaveNoteTool(context),
            DeviceStatusTool(context),
            MapsNavigationTool(context),
            DeleteMemoryTool(),
            VolumeTool(context),
            WebSearchTool(),
            GetInfoTool(context)
        )
    )
}
