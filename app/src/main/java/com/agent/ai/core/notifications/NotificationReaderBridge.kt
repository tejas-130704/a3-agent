package com.agent.ai.core.notifications

import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationReaderBridge {

    @Volatile
    private var service: AgentNotificationListenerService? = null

    fun register(svc: AgentNotificationListenerService) {
        service = svc
    }

    fun unregister(svc: AgentNotificationListenerService) {
        if (service === svc) service = null
    }

    fun isConnected(): Boolean = service != null

    fun isAccessGranted(context: Context): Boolean {
        if (isConnected()) return true
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun getRecent(limit: Int): List<NotificationEntry> {
        service?.refreshFromActiveNotifications()
        return NotificationStore.recent(limit)
    }

    fun formatForSpeech(notifications: List<NotificationEntry>): String {
        if (notifications.isEmpty()) return "You have no notifications right now."

        val count = notifications.size
        val header = if (count == 1) {
            "You have 1 notification."
        } else {
            "You have $count notifications."
        }
        val items = notifications.mapIndexed { index, entry ->
            "Number ${index + 1}: ${entry.speakableSummary()}"
        }
        return (listOf(header) + items).joinToString(" ")
    }

    fun componentName(context: Context): ComponentName =
        ComponentName(context, AgentNotificationListenerService::class.java)
}
