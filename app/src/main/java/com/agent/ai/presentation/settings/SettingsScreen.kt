package com.agent.ai.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.agent.ai.data.settings.AgentSettingsStore
import com.agent.ai.service.AgentServiceStarter

@Composable
fun SettingsScreen(
    settingsStore: AgentSettingsStore,
    onSaved: () -> Unit = {}
) {
    val context = LocalContext.current

    var groqKeys by remember { mutableStateOf(settingsStore.getGroqKeys()) }
    var primaryIndex by remember { mutableIntStateOf(settingsStore.getPrimaryGroqKeyIndex()) }
    var newGroqKey by remember { mutableStateOf("") }
    var picovoiceKey by remember { mutableStateOf(settingsStore.getPicovoiceAccessKey()) }
    var showKeys by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        groqKeys = settingsStore.getGroqKeys()
        primaryIndex = settingsStore.getPrimaryGroqKeyIndex()
        picovoiceKey = settingsStore.getPicovoiceAccessKey()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("API Keys", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Groq keys failover automatically — if one hits a limit or fails, the next is used and becomes primary until it fails too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Groq API keys (${groqKeys.size})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showKeys = !showKeys }) {
                    Text(if (showKeys) "Hide" else "Show")
                }
            }
        }

        if (groqKeys.isEmpty()) {
            item {
                Text(
                    "No keys saved yet. Add at least one Groq key below.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            itemsIndexed(groqKeys) { index, key ->
                val isPrimary = index == primaryIndex.coerceIn(0, groqKeys.lastIndex)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isPrimary) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Primary key",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.width(28.dp))
                        }
                        Text(
                            text = maskKey(key, showKeys),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(onClick = {
                            settingsStore.removeGroqKey(index)
                            refresh()
                            statusMessage = "Key removed"
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove key")
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = newGroqKey,
                onValueChange = { newGroqKey = it },
                label = { Text("Add Groq API key(s)") },
                placeholder = { Text("gsk_... (paste single or comma-separated keys)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (newGroqKey.isBlank()) {
                        statusMessage = "Enter a key first"
                        return@Button
                    }
                    val previousCount = settingsStore.getGroqKeys().size
                    val updated = settingsStore.addGroqKey(newGroqKey)
                    val addedCount = updated.size - previousCount
                    newGroqKey = ""
                    refresh()
                    statusMessage = if (addedCount > 0) "Added $addedCount Groq key(s)" else "No new valid Groq keys added"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Groq Key")
            }
        }

        item {
            HorizontalDivider()
            Text("Picovoice AccessKey (wake word)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used once at startup to activate offline wake word models. Detection runs fully on-device after activation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = picovoiceKey,
                onValueChange = { picovoiceKey = it },
                label = { Text("Picovoice AccessKey") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        }

        item {
            Button(
                onClick = {
                    settingsStore.setPicovoiceAccessKey(picovoiceKey)
                    refresh()
                    statusMessage = "Settings saved — restarting agent service"
                    AgentServiceStarter.restart(context)
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Restart Agent")
            }
        }

        statusMessage?.let { msg ->
            item {
                Text(msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            HorizontalDivider()
            Text("Failover behaviour", style = MaterialTheme.typography.titleSmall)
            Text(
                "• Primary key (★) is tried first on every request\n" +
                    "• On auth error, rate limit (429), billing (402), or server error → next key\n" +
                    "• Successful key becomes new primary until it fails\n" +
                    "• Malformed LLM responses do not rotate keys",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun maskKey(key: String, show: Boolean): String {
    if (show || key.length <= 8) return key
    return key.take(4) + "••••" + key.takeLast(4)
}
