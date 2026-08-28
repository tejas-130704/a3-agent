package com.agent.ai.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Central entry point for keeping the wake-word foreground service alive. */
object AgentServiceStarter {

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Start (or re-assert) the microphone foreground service when mic is granted. */
    fun startIfReady(context: Context) {
        if (!hasMicPermission(context)) return
        val appContext = context.applicationContext
        val intent = Intent(appContext, AgentForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    /** Stop then start — used after settings changes. */
    fun restart(context: Context) {
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, AgentForegroundService::class.java))
        startIfReady(appContext)
    }
}
