package com.shounak.localmeshai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.memory.MemoryCategory
import com.shounak.localmeshai.memory.MemoryEntry
import com.shounak.localmeshai.memory.PersistentMemoryManager
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersistentMemoryDialog(
    memoryManager: PersistentMemoryManager,
    onDismissRequest: () -> Unit
) {
    var memories by remember { mutableStateOf(memoryManager.getAllMemories()) }
    var selectedCategoryFilter by remember { mutableStateOf<MemoryCategory?>(null) }
    var newMemoryText by remember { mutableStateOf("") }
    var newMemoryCategory by remember { mutableStateOf(MemoryCategory.FACT) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    fun refresh() {
        memories = memoryManager.getAllMemories()
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val colors = MaterialTheme.colorScheme
        val dialogShape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f)
                .glassEffect(
                    hazeState = remember { HazeState() },
                    shape = dialogShape,
                    blurRadius = 20.dp,
                    tintColor = colors.surfaceContainer,
                    borderAlpha = 0.35f
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🧠 Persistent Memory",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Facts and instructions remembered across all chats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Filter Category Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategoryFilter == null,
                        onClick = { selectedCategoryFilter = null },
                        label = { Text("All (${memories.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = colors.surfaceContainerLow,
                            labelColor = colors.onSurfaceVariant,
                            selectedContainerColor = colors.primary.copy(alpha = 0.28f),
                            selectedLabelColor = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedCategoryFilter == null,
                            borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                            selectedBorderColor = colors.primary.copy(alpha = 0.75f),
                            borderWidth = 1.dp
                        )
                    )
                    MemoryCategory.entries.forEach { cat ->
                        val count = memories.count { it.category == cat }
                        val isSelected = selectedCategoryFilter == cat
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategoryFilter = if (isSelected) null else cat },
                            label = { Text("${cat.icon} ${cat.displayName} ($count)") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = colors.surfaceContainerLow,
                                labelColor = colors.onSurfaceVariant,
                                selectedContainerColor = colors.primary.copy(alpha = 0.28f),
                                selectedLabelColor = Color.White
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                                selectedBorderColor = colors.primary.copy(alpha = 0.75f),
                                borderWidth = 1.dp
                            )
                        )
                    }
                }

                // Add New Memory Input Row
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.40f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newMemoryText,
                                onValueChange = { newMemoryText = it },
                                placeholder = {
                                    Text(
                                        "Add fact or rule (e.g. 'I prefer Kotlin')",
                                        color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = false,
                                maxLines = 2,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = colors.surfaceContainerLowest,
                                    unfocusedContainerColor = colors.surfaceContainerLowest,
                                    focusedBorderColor = colors.primary.copy(alpha = 0.65f),
                                    unfocusedBorderColor = colors.outlineVariant.copy(alpha = 0.40f),
                                    cursorColor = colors.primary
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            FilledIconButton(
                                onClick = {
                                    if (newMemoryText.isNotBlank()) {
                                        memoryManager.addMemory(newMemoryText, newMemoryCategory)
                                        newMemoryText = ""
                                        refresh()
                                    }
                                },
                                enabled = newMemoryText.isNotBlank(),
                                shape = RoundedCornerShape(10.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = colors.primary,
                                    contentColor = Color.Black,
                                    disabledContainerColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                    disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.38f)
                                ),
                                modifier = Modifier.size(46.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add memory")
                            }
                        }

                        // Category picker for new memory
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MemoryCategory.entries.forEach { cat ->
                                val isSelected = newMemoryCategory == cat
                                InputChip(
                                    selected = isSelected,
                                    onClick = { newMemoryCategory = cat },
                                    label = { Text("${cat.icon} ${cat.displayName}", style = MaterialTheme.typography.labelSmall) },
                                    colors = InputChipDefaults.inputChipColors(
                                        containerColor = colors.surfaceContainerLowest,
                                        labelColor = colors.onSurfaceVariant,
                                        selectedContainerColor = colors.primary.copy(alpha = 0.28f),
                                        selectedLabelColor = Color.White
                                    ),
                                    border = InputChipDefaults.inputChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = colors.outlineVariant.copy(alpha = 0.35f),
                                        selectedBorderColor = colors.primary.copy(alpha = 0.75f),
                                        borderWidth = 1.dp
                                    )
                                )
                            }
                        }
                    }
                }

                // Filtered Memories List
                val displayedMemories = remember(memories, selectedCategoryFilter) {
                    if (selectedCategoryFilter == null) {
                        memories
                    } else {
                        memories.filter { it.category == selectedCategoryFilter }
                    }
                }

                if (displayedMemories.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "🧠",
                                style = MaterialTheme.typography.displaySmall
                            )
                            Text(
                                text = if (memories.isEmpty()) "No memories saved yet" else "No memories in this category",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tell Solus 'Remember that...' in chat or add entries above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayedMemories, key = { it.id }) { memory ->
                            MemoryItemCard(
                                memory = memory,
                                onToggle = { enabled ->
                                    memoryManager.toggleMemory(memory.id, enabled)
                                    refresh()
                                },
                                onDelete = {
                                    memoryManager.deleteMemory(memory.id)
                                    refresh()
                                }
                            )
                        }
                    }
                }

                // Footer with Clear All Button
                if (memories.isNotEmpty()) {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${memories.count { it.isEnabled }} of ${memories.size} active in prompts",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { showClearConfirmation = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = colors.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear All")
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmation) {
        val colors = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            containerColor = colors.surfaceContainerLow,
            titleContentColor = colors.onSurface,
            textContentColor = colors.onSurfaceVariant,
            title = { Text("Clear all memories?") },
            text = { Text("This will permanently delete all saved user memories, preferences, and custom instructions.") },
            confirmButton = {
                Button(
                    onClick = {
                        memoryManager.clearAllMemories()
                        refresh()
                        showClearConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmation = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = colors.onSurfaceVariant
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MemoryItemCard(
    memory: MemoryEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(14.dp)
    val isEnabled = memory.isEnabled

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (isEnabled) colors.primary.copy(alpha = 0.40f) else colors.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surfaceContainerHigh.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(memory.category.icon, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        text = memory.category.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEnabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.85f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.primary,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    uncheckedTrackColor = colors.surfaceContainerHighest.copy(alpha = 0.40f),
                    uncheckedBorderColor = colors.outlineVariant.copy(alpha = 0.50f)
                )
            )

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete memory",
                    tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
