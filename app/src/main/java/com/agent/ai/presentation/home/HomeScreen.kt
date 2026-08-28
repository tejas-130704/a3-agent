package com.agent.ai.presentation.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agent.ai.core.AgentController
import com.agent.ai.core.AgentResult
import com.agent.ai.core.AgentState
import com.agent.ai.core.ErrorCode
import com.agent.ai.core.accessibility.AgentAccessibilityBridge
import com.agent.ai.core.accessibility.AgentAccessibilityService
import com.agent.ai.core.notifications.NotificationReaderBridge
import com.agent.ai.data.memory.AgentMemoryHub
import com.agent.ai.data.settings.AgentSettingsStore
import com.agent.ai.data.tools.ContactCallSession
import com.agent.ai.data.tools.DialerTool
import com.agent.ai.presentation.chat.ContactChoiceUi
import com.agent.ai.presentation.chat.ContactChoicesPanel
import com.agent.ai.presentation.theme.AccentCyan
import com.agent.ai.presentation.theme.AccentViolet
import com.agent.ai.presentation.theme.SkyCard
import com.agent.ai.service.AgentForegroundService
import com.agent.ai.service.AgentServiceStarter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun HomeScreen(micGranted: Boolean, lifecycle: Lifecycle) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dialerTool = remember(context) { DialerTool(context) }
    var a11yEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var notifAccessEnabled by remember { mutableStateOf(NotificationReaderBridge.isAccessGranted(context)) }
    var groqCount by remember { mutableIntStateOf(AgentSettingsStore(context).getGroqKeys().size) }
    var memoryCount by remember { mutableIntStateOf(0) }
    var sessionActive by remember { mutableStateOf(false) }
    var agentState by remember { mutableStateOf(AgentState.IDLE) }
    var wakeWordEnabled by remember { mutableStateOf(false) }
    var pendingCall by remember { mutableStateOf<AgentController.PendingCallUi?>(null) }
    var confirmingCall by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AgentController.state.collectLatest { agentState = it }
    }
    LaunchedEffect(Unit) {
        AgentController.wakeWordEnabled.collectLatest { wakeWordEnabled = it }
    }
    LaunchedEffect(Unit) {
        AgentController.pendingCall.collectLatest { pendingCall = it }
    }

    val isTurnActive = agentState != AgentState.IDLE && agentState != AgentState.ERROR
    val manualButtonLabel = when (agentState) {
        AgentState.LISTENING -> "Listening… speak now"
        AgentState.THINKING -> "Thinking…"
        AgentState.ACTING -> "Working on it…"
        AgentState.SPEAKING -> "Speaking…"
        AgentState.ERROR -> "Tap to try again"
        AgentState.IDLE -> "Talk to Agent"
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = isAccessibilityEnabled(context)
                notifAccessEnabled = NotificationReaderBridge.isAccessGranted(context)
                groqCount = AgentSettingsStore(context).getGroqKeys().size
                if (AgentMemoryHub.isReady()) {
                    memoryCount = AgentMemoryHub.repository.allBubbles().size
                    sessionActive = AgentMemoryHub.session.isActive()
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(AccentViolet.copy(alpha = 0.15f), MaterialTheme.colorScheme.background))
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("A3 Agent", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Your voice-first AI with memory", color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Status orb
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SkyCard)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Mic, null, tint = if (micGranted) AccentCyan else MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        when {
                            !micGranted -> "Mic permission needed"
                            wakeWordEnabled -> "Listening for \"Hey A3\" / \"Ok A3\""
                            else -> "Ready — tap Talk to Agent below"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            !micGranted -> "Grant mic access to use voice commands"
                            wakeWordEnabled && sessionActive -> "Live session active — follow-ups work"
                            wakeWordEnabled -> "Say a wake word or tap the button"
                            sessionActive -> "Live session active — follow-ups work"
                            else -> "Wake word not configured — button only (see Settings)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatChip("Groq keys", "$groqCount", Modifier.weight(1f))
            StatChip("Memory nodes", "$memoryCount", Modifier.weight(1f))
            StatChip("Notifs", if (notifAccessEnabled) "ON" else "OFF", Modifier.weight(1f))
        }

        if (groqCount == 0) {
            Text("Add Groq keys in Settings →", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        pendingCall?.let { pending ->
            val choicesActive = ContactCallSession.isActive(pending.pendingId)
            val uiChoices = pending.choices.map { ContactChoiceUi(it.index, it.displayName) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SkyCard)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Confirm call",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (choicesActive) {
                            "Tap a contact or say \"one\", \"two\"… after Talk to Agent"
                        } else {
                            "Contact list expired — ask again to search"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ContactChoicesPanel(
                        query = pending.query,
                        choices = uiChoices,
                        enabled = choicesActive && !confirmingCall && !isTurnActive,
                        expired = !choicesActive,
                        onChoiceClick = { choice ->
                            if (!ContactCallSession.isActive(pending.pendingId)) {
                                AgentController.reportError(
                                    AgentResult.Error(
                                        ErrorCode.TOOL_INVALID_PARAMS,
                                        "That contact list expired — say \"call …\" again."
                                    ),
                                    "Call contact"
                                )
                                return@ContactChoicesPanel
                            }
                            confirmingCall = true
                            scope.launch {
                                try {
                                    val params = JSONObject().apply {
                                        put("contact_name", choice.displayName)
                                        put("confirmed", true)
                                        put("choice_index", choice.index)
                                    }
                                    when (val result = dialerTool.execute(params)) {
                                        is AgentResult.Success -> { /* dial started */ }
                                        is AgentResult.Error -> AgentController.reportError(result, "Call contact")
                                    }
                                    AgentController.syncPendingCall()
                                } finally {
                                    confirmingCall = false
                                }
                            }
                        }
                    )
                }
            }
        }

        // Manual trigger — no wake word needed
        Button(
            onClick = {
                AgentServiceStarter.startIfReady(context)
                if (!AgentController.isServiceReady()) {
                    context.startForegroundService(
                        Intent(context, AgentForegroundService::class.java).apply {
                            action = AgentForegroundService.ACTION_MANUAL_TURN
                        }
                    )
                    AgentController.reportError(
                        AgentResult.Error(
                            ErrorCode.SERVICE_KILLED_BY_OS,
                            "Agent service is starting — wait a moment, then tap again."
                        ),
                        "Agent service"
                    )
                    return@Button
                }
                if (isTurnActive) {
                    AgentController.reportError(
                        AgentResult.Error(
                            ErrorCode.UNKNOWN,
                            "Agent is busy — wait for the current turn to finish."
                        ),
                        "Talk to Agent"
                    )
                    return@Button
                }
                val started = AgentController.triggerManualTurn()
                if (!started) {
                    context.startForegroundService(
                        Intent(context, AgentForegroundService::class.java).apply {
                            action = AgentForegroundService.ACTION_MANUAL_TURN
                        }
                    )
                }
            },
            enabled = micGranted && !isTurnActive,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentViolet,
                disabledContainerColor = SkyCard
            )
        ) {
            Icon(
                if (isTurnActive) Icons.Outlined.Mic else Icons.Outlined.MicNone,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(manualButtonLabel, fontWeight = FontWeight.SemiBold)
        }

        if (micGranted && wakeWordEnabled) {
            Text(
                "Or say \"Hey A3\" / \"Ok A3\" anytime in the background",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!a11yEnabled) {
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enable UI Automation") }
        }

        if (!notifAccessEnabled) {
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enable Notification Reading") }
        }

        Text("Quick tips", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        TipRow("Notifications", "Say \"tell me my top 5 notifications\" after enabling Notification access.")
        TipRow("Multi-turn", "Say \"Spotify\" then \"play Arijit Singh\" — session remembers.")
        TipRow("Memory Sky", "Frequent contacts & commands auto-learn over time.")
        TipRow("Chat tab", "Ask what the agent remembers about you.")
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(SkyCard).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontWeight = FontWeight.Bold, color = AccentCyan)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TipRow(title: String, body: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    if (AgentAccessibilityBridge.isEnabled()) return true
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.name == AgentAccessibilityService::class.java.name }
}
