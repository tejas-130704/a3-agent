package com.agent.ai

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import com.agent.ai.data.memory.AgentMemoryHub
import com.agent.ai.data.settings.AgentSettingsStore
import com.agent.ai.core.AgentController
import com.agent.ai.core.AgentErrorEvent
import com.agent.ai.presentation.chat.AgentChatScreen
import com.agent.ai.presentation.chat.ChatScreen
import com.agent.ai.presentation.components.AgentErrorDialog
import com.agent.ai.presentation.home.HomeScreen
import com.agent.ai.presentation.memory.MemorySkyScreen
import com.agent.ai.presentation.settings.SettingsScreen
import com.agent.ai.presentation.theme.AgentTheme
import com.agent.ai.presentation.tools.ToolsScreen
import com.agent.ai.service.AgentForegroundService
import com.agent.ai.service.AgentServiceStarter
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private var micGranted by mutableStateOf(false)
    private val settingsStore by lazy { AgentSettingsStore(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            AgentServiceStarter.startIfReady(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentMemoryHub.init(this)
        setContent {
            AgentTheme {
                MainScreen(
                    micGranted = micGranted,
                    settingsStore = settingsStore,
                    lifecycle = lifecycle
                )
            }
        }
        permissionLauncher.launch(requiredPermissions.toTypedArray())
    }
}

private data class NavTab(val label: String, val icon: ImageVector)

@Composable
private fun MainScreen(
    micGranted: Boolean,
    settingsStore: AgentSettingsStore,
    lifecycle: Lifecycle
) {
    val tabs = listOf(
        NavTab("Home", Icons.Outlined.Home),
        NavTab("Tools", Icons.Outlined.Build),
        NavTab("Memory", Icons.Outlined.Cloud),
        NavTab("Agent", Icons.Outlined.SmartToy),
        NavTab("Recall", Icons.Outlined.Forum),
        NavTab("Settings", Icons.Outlined.Settings)
    )
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var lastError by remember { mutableStateOf<AgentErrorEvent?>(null) }

    LaunchedEffect(Unit) {
        AgentController.lastError.collectLatest { lastError = it }
    }

    AgentErrorDialog(
        error = lastError,
        onDismiss = { AgentController.clearError() }
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen(micGranted = micGranted, lifecycle = lifecycle)
                1 -> ToolsScreen()
                2 -> MemorySkyScreen()
                3 -> AgentChatScreen()
                4 -> ChatScreen()
                5 -> SettingsScreen(settingsStore = settingsStore)
            }
        }
    }
}
