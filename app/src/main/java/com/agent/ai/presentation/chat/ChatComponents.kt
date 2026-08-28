package com.agent.ai.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.ai.data.tools.ContactCallSession
import com.agent.ai.presentation.theme.AccentCyan
import com.agent.ai.presentation.theme.AccentViolet
import com.agent.ai.presentation.theme.SkyCard

@Composable
fun ChatScaffold(
    title: String,
    subtitle: String,
    messages: List<UiChatMessage>,
    loading: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    inputPlaceholder: String,
    inputEnabled: Boolean = true,
    banner: @Composable (() -> Unit)? = null,
    onSend: () -> Unit,
    suggestions: @Composable (() -> Unit)? = null,
    onContactChoiceClick: ((ContactChoiceUi, String) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (loading) 1 else 0

    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            listState.animateScrollToItem((itemCount + 1).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        banner?.invoke()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth()) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (messages.isEmpty() && suggestions != null) {
                item(key = "suggestions") { suggestions() }
            }

            items(messages, key = { it.id }) { msg ->
                val choicesActive = msg.pendingSessionId?.let { ContactCallSession.isActive(it) } == true
                ChatMessageRow(
                    msg = msg,
                    choicesActive = choicesActive,
                    loading = loading,
                    onContactChoiceClick = onContactChoiceClick
                )
            }

            if (loading) {
                item(key = "loading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Working…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(inputPlaceholder) },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                enabled = inputEnabled && !loading
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend, enabled = inputEnabled && !loading && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun ChatMessageRow(
    msg: UiChatMessage,
    choicesActive: Boolean,
    loading: Boolean,
    onContactChoiceClick: ((ContactChoiceUi, String) -> Unit)?
) {
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        msg.isError -> MaterialTheme.colorScheme.errorContainer
                        isUser -> AccentViolet.copy(alpha = 0.45f)
                        else -> SkyCard
                    }
                )
                .padding(12.dp)
        ) {
            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
        }

        if (!msg.contactChoices.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            ContactChoicesPanel(
                query = msg.pendingQuery.orEmpty(),
                choices = msg.contactChoices,
                enabled = choicesActive && !loading && onContactChoiceClick != null,
                expired = !choicesActive,
                onChoiceClick = { choice ->
                    msg.pendingSessionId?.let { id -> onContactChoiceClick?.invoke(choice, id) }
                }
            )
        }
    }
}

@Composable
fun ContactChoicesPanel(
    query: String,
    choices: List<ContactChoiceUi>,
    enabled: Boolean,
    expired: Boolean = false,
    onChoiceClick: ((ContactChoiceUi) -> Unit)?
) {
    Surface(
        modifier = Modifier.widthIn(max = 340.dp).alpha(if (expired) 0.55f else 1f),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Phone, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        expired -> "Selection expired — search again"
                        query.isBlank() -> "Pick a contact"
                        else -> "Matches for \"$query\""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            choices.forEach { choice ->
                ContactChoiceCard(choice, enabled = enabled) {
                    onChoiceClick?.invoke(choice)
                }
            }
        }
    }
}

@Composable
private fun ContactChoiceCard(choice: ContactChoiceUi, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = AccentCyan.copy(alpha = if (enabled) 0.12f else 0.06f)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(8.dp), color = AccentViolet.copy(alpha = 0.35f)) {
                Text("${choice.index}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Outlined.Person, null, modifier = Modifier.size(20.dp), tint = AccentCyan)
            Spacer(Modifier.width(8.dp))
            Text(choice.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun ChatSuggestionChip(text: String, onClick: (String) -> Unit) {
    AssistChip(
        onClick = { onClick(text) },
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.padding(vertical = 2.dp),
        colors = AssistChipDefaults.assistChipColors(containerColor = AccentViolet.copy(alpha = 0.18f))
    )
}

@Composable
fun ChatInfoBanner(text: String, isError: Boolean = false) {
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
