package com.agent.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agent.ai.core.AgentController
import com.agent.ai.core.AgentOrchestrator
import com.agent.ai.core.AgentState
import com.agent.ai.core.stt.SpeechToText
import com.agent.ai.core.tts.TextToSpeechEngine
import com.agent.ai.core.wakeword.NoOpWakeWordDetector
import com.agent.ai.core.wakeword.PorcupineWakeWordDetector
import com.agent.ai.core.wakeword.WakeWordConfig
import com.agent.ai.core.wakeword.WakeWordDetector
import com.agent.ai.data.GroqApiClient
import com.agent.ai.data.settings.SettingsKeyResolver
import com.agent.ai.data.tools.ContactLookup
import com.agent.ai.data.tools.ContactCallSession
import com.agent.ai.data.tools.ToolRegistryFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps the wake-word listener alive in the background.
 * On MIUI/HyperOS (POCO M4 Pro 5G): the user MUST disable battery optimization
 * for this app and enable "Autostart" in MIUI security settings, or the OS will
 * kill this service within minutes regardless of the foreground notification —
 * this is a known OEM restriction, not a bug in this code.
 */
class AgentForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob())
    private lateinit var orchestrator: AgentOrchestrator
    private var wakeWordConfigured = false

    private lateinit var stt: SpeechToText
    private lateinit var tts: TextToSpeechEngine
    private var pendingManualTurn = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))

        stt = SpeechToText(this)
        tts = TextToSpeechEngine(this)

        val toolRegistry = ToolRegistryFactory.create(this)

        val keyManager = SettingsKeyResolver.groqKeyManager(this)
        val groq = GroqApiClient(
            keyManager = keyManager,
            toolRegistry = toolRegistry
        )

        wakeWordConfigured = WakeWordConfig.isConfigured(this)
        AgentController.setWakeWordEnabled(wakeWordConfigured)

        val wakeWord: WakeWordDetector = if (wakeWordConfigured) {
            PorcupineWakeWordDetector(
                context = this,
                accessKey = SettingsKeyResolver.picovoiceAccessKey(this),
                onWake = {
                    promoteForeground("Wake word detected — listening…")
                    orchestrator.onWakeWordTriggered()
                },
                onFatalError = { error ->
                    Log.w(TAG, "Wake word runtime error — disabling: ${error.message}")
                }
            )
        } else {
            android.util.Log.i(TAG, "Wake word not configured — skipping Porcupine (manual trigger only)")
            NoOpWakeWordDetector()
        }

        orchestrator = AgentOrchestrator(
            scope = serviceScope,
            wakeWord = wakeWord,
            stt = stt,
            tts = tts,
            groq = groq,
            toolRegistry = toolRegistry,
            onStateChanged = { state ->
                AgentController.updateState(state)
                updateNotification(stateLabel(state))
            },
            extraContextProvider = {
                listOfNotNull(
                    ContactLookup.buildFrequentContactsPrompt(this).takeIf { it.isNotBlank() },
                    ContactCallSession.buildContextPrompt().takeIf { it.isNotBlank() }
                ).joinToString("\n\n")
            }
        )

        AgentController.register(orchestrator)
        orchestrator.start()

        if (pendingManualTurn) {
            pendingManualTurn = false
            orchestrator.onManualTrigger()
        }
    }

    override fun onDestroy() {
        if (::orchestrator.isInitialized) {
            AgentController.unregister(orchestrator)
            orchestrator.stop()
        }
        if (::stt.isInitialized) {
            kotlinx.coroutines.runBlocking { stt.release() }
        }
        if (::tts.isInitialized) {
            tts.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MANUAL_TURN) {
            if (::orchestrator.isInitialized) {
                promoteForeground(stateLabel(AgentController.state.value))
                orchestrator.onManualTrigger()
            } else {
                pendingManualTurn = true
            }
            return START_STICKY
        }
        if (::orchestrator.isInitialized) {
            promoteForeground(stateLabel(AgentController.state.value))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stateLabel(state: AgentState): String = when (state) {
        AgentState.IDLE -> if (wakeWordConfigured) {
            "Listening for \"Hey A3\" or \"Ok A3\""
        } else {
            "Ready — use Talk to Agent in app"
        }
        AgentState.LISTENING -> "Listening..."
        AgentState.THINKING -> "Thinking..."
        AgentState.ACTING -> "Working on it..."
        AgentState.SPEAKING -> "Responding..."
        AgentState.ERROR -> "Error — check logs"
    }

    private fun buildNotification(status: String): Notification {
        val channelId = "agent_status"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Agent Status", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AI Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun promoteForeground(status: String) {
        startForeground(NOTIFICATION_ID, buildNotification(status))
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        private const val TAG = "AgentForegroundService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_MANUAL_TURN = "com.agent.ai.action.MANUAL_TURN"
    }
}
