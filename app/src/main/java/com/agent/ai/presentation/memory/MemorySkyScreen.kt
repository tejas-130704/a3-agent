package com.agent.ai.presentation.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ai.data.memory.AgentMemoryHub
import com.agent.ai.data.memory.ConversationSummary
import com.agent.ai.data.memory.MemoryBubble
import com.agent.ai.data.memory.MemoryTopic
import com.agent.ai.presentation.theme.AccentCyan
import com.agent.ai.presentation.theme.AccentPink
import com.agent.ai.presentation.theme.AccentViolet
import com.agent.ai.presentation.theme.SkyCard

@Composable
fun MemorySkyScreen() {
    if (!AgentMemoryHub.isReady()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Memory loading…")
        }
        return
    }

    var selectedTopic by remember { mutableStateOf<MemoryTopic?>(null) }
    val repo = AgentMemoryHub.repository
    var bubbles by remember { mutableStateOf(repo.allBubbles()) }
    var summaries by remember { mutableStateOf(repo.recentSummaries(5)) }
    val stats = remember(bubbles) { repo.topicStats() }

    var pendingDeleteBubble by remember { mutableStateOf<MemoryBubble?>(null) }
    var pendingDeleteSummary by remember { mutableStateOf<ConversationSummary?>(null) }

    fun refreshLists() {
        bubbles = if (selectedTopic == null) repo.allBubbles()
        else repo.bubblesForTopic(selectedTopic!!)
        summaries = repo.recentSummaries(5)
    }

    pendingDeleteBubble?.let { bubble ->
        AlertDialog(
            onDismissRequest = { pendingDeleteBubble = null },
            title = { Text("Delete memory?") },
            text = { Text("Remove \"${bubble.label}\" from ${bubble.topic.displayName}?") },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteBubbleById(bubble.id)
                    pendingDeleteBubble = null
                    refreshLists()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBubble = null }) { Text("Cancel") }
            }
        )
    }

    pendingDeleteSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSummary = null },
            title = { Text("Delete summary?") },
            text = { Text("Remove this conversation summary from memory?") },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteSummaryById(summary.id)
                    pendingDeleteSummary = null
                    refreshLists()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSummary = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Memory Sky", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Tap delete on any node to remove it from memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Text("Topic skies", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FlowRowTopicChips(stats, selectedTopic) { topic ->
                selectedTopic = if (selectedTopic == topic) null else topic
                refreshLists()
            }
        }

        item {
            Text(
                if (selectedTopic != null) "${selectedTopic!!.emoji} ${selectedTopic!!.displayName}"
                else "All memory nodes (${bubbles.size})",
                style = MaterialTheme.typography.titleSmall
            )
        }

        if (bubbles.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SkyCard)) {
                    Text(
                        "No memories yet. Use voice commands — WhatsApp contacts, Spotify searches, and calls will appear here.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            items(bubbles, key = { it.id }) { bubble ->
                MemoryBubbleCard(
                    bubble = bubble,
                    onDelete = { pendingDeleteBubble = bubble }
                )
            }
        }

        item {
            if (summaries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Recent conversation summaries", fontWeight = FontWeight.SemiBold)
            }
        }

        items(summaries, key = { it.id }) { summary ->
            SummaryCard(
                summary = summary,
                onDelete = { pendingDeleteSummary = summary }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowTopicChips(
    stats: Map<MemoryTopic, Int>,
    selected: MemoryTopic?,
    onSelect: (MemoryTopic) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MemoryTopic.entries.filter { stats[it] ?: 0 > 0 || it == MemoryTopic.GENERAL }.forEach { topic ->
            val count = stats[topic] ?: 0
            FilterChip(
                selected = selected == topic,
                onClick = { onSelect(topic) },
                label = { Text("${topic.emoji} ${topic.displayName} ($count)") }
            )
        }
    }
}

@Composable
private fun MemoryBubbleCard(bubble: MemoryBubble, onDelete: () -> Unit) {
    val size = (40 + bubble.frequency * 8).coerceAtMost(80).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SkyCard)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    when (bubble.topic) {
                        MemoryTopic.SPOTIFY -> AccentPink.copy(alpha = 0.4f)
                        MemoryTopic.WHATSAPP -> AccentCyan.copy(alpha = 0.4f)
                        else -> AccentViolet.copy(alpha = 0.35f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("${bubble.frequency}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(bubble.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${bubble.subTopic} · ${bubble.topic.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(bubble.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete memory",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: ConversationSummary, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = SkyCard)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(summary.summary, style = MaterialTheme.typography.bodySmall)
                if (summary.toolUsed != null) {
                    Text("Tool: ${summary.toolUsed}", style = MaterialTheme.typography.labelSmall, color = AccentCyan)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete summary",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
