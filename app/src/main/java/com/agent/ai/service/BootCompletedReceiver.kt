package com.agent.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart wake-word listening after device reboot (requires mic permission already granted). */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        AgentServiceStarter.startIfReady(context)
    }
}
