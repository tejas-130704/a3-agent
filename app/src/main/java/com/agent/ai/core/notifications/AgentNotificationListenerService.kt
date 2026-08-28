package com.agent.ai.core.notifications

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Captures notification shade content so the agent can read it aloud on request.
 * User must enable in Settings → Notification access → AI Agent.
 */
class AgentNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationReaderBridge.register(this)
        refreshFromActiveNotifications()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        NotificationReaderBridge.unregister(this)
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        parseNotification(sbn)?.let { NotificationStore.upsert(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationStore.remove(notificationKey(sbn))
    }

    fun refreshFromActiveNotifications() {
        try {
            activeNotifications?.forEach { sbn ->
                parseNotification(sbn)?.let { NotificationStore.upsert(it) }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not read active notifications", e)
        }
    }

    private fun parseNotification(sbn: StatusBarNotification): NotificationEntry? {
        if (sbn.packageName == packageName) return null

        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()

        val body = when {
            bigText.isNotBlank() -> bigText
            text.isNotBlank() -> text
            subText.isNotBlank() -> subText
            else -> ""
        }
        if (title.isBlank() && body.isBlank()) return null

        val appLabel = appLabelFor(sbn.packageName) ?: sbn.packageName
        return NotificationEntry(
            key = notificationKey(sbn),
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = body,
            postTimeMs = sbn.postTime
        )
    }

    private fun appLabelFor(packageName: String): String? = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun notificationKey(sbn: StatusBarNotification): String =
        "${sbn.packageName}|${sbn.id}|${sbn.tag.orEmpty()}|${sbn.key}"

    companion object {
        private const val TAG = "AgentNotificationListener"
    }
}
