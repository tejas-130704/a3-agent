package com.agent.ai.presentation.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ai.core.AgentErrorEvent

@Composable
fun AgentErrorDialog(
    error: AgentErrorEvent?,
    onDismiss: () -> Unit
) {
    if (error == null) return

    val context = LocalContext.current
    var showDebug by remember(error) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Text("!", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
        },
        title = {
            Text(error.title, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(error.message)
                Text(
                    error.code.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = { showDebug = !showDebug }) {
                    Text(if (showDebug) "Hide debug details" else "Show debug details")
                }
                if (showDebug) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            error.debugText,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
        dismissButton = {
            TextButton(onClick = { copyDebugToClipboard(context, error) }) {
                Text("Copy debug")
            }
        }
    )
}

private fun copyDebugToClipboard(context: Context, error: AgentErrorEvent) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("A3 Agent error", error.debugText))
}
