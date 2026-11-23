package com.shounak.localmeshai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.utils.CrashReport
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

@Composable
fun CrashPromptDialog(
    report: CrashReport,
    hazeState: HazeState? = null,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val dialogShape = RoundedCornerShape(24.dp)
    var showDetails by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val maxDialogHeight = (screenHeight - 56.dp).coerceAtLeast(320.dp)

        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = maxDialogHeight)
                .padding(vertical = 12.dp)
                .let { mod ->
                    if (hazeState != null) {
                        mod.glassEffect(
                            hazeState = hazeState,
                            shape = dialogShape,
                            blurRadius = 24.dp,
                            tintColor = colors.surfaceContainerHigh.copy(alpha = 0.85f),
                            borderAlpha = 0.45f
                        )
                    } else {
                        mod.background(colors.surfaceContainerHigh, dialogShape)
                    }
                },
            shape = dialogShape,
            colors = CardDefaults.cardColors(containerColor = if (hazeState != null) Color.Transparent else colors.surfaceContainerHigh)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with Bug Icon and Title (Pinned)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.error.copy(alpha = 0.15f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Crash Detected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Solus crashed on its previous run (${report.formattedDate})",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                // Scrollable Body (Adapts gracefully so action buttons never get pushed off)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Prompt description
                    Text(
                        text = "Solus captured the full crash debug log (logcat) and complete device hardware specs. Would you like to send this full report to our GitHub repo to help fix the issue?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )

                    // Error summary card
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surfaceContainerLow.copy(alpha = 0.70f),
                        border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = report.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.error,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (report.isNativeCrash) colors.error.copy(alpha = 0.15f) else colors.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (report.isNativeCrash) "NATIVE" else "JVM",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (report.isNativeCrash) colors.error else colors.primary
                                    )
                                }
                            }

                            // Toggle debug details preview
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showDetails = !showDetails }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showDetails) "Hide Debug Log & Device Info" else "View Full Debug Log & Device Info",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary
                                )
                                Icon(
                                    imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            AnimatedVisibility(
                                visible = showDetails,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = colors.surfaceContainerHighest.copy(alpha = 0.50f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = report.details,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Action buttons: Cancel and Send to GitHub Repo (Pinned at bottom, always fully visible)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (hazeState != null) {
                        LiquidGlassButton(
                            onClick = onCancel,
                            hazeState = hazeState,
                            shape = RoundedCornerShape(12.dp),
                            tintColor = colors.surfaceContainerHighest.copy(alpha = 0.50f),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                        }

                        LiquidGlassButton(
                            onClick = onSend,
                            hazeState = hazeState,
                            shape = RoundedCornerShape(12.dp),
                            tintColor = colors.error.copy(alpha = 0.22f),
                            modifier = Modifier
                                .weight(1.4f)
                                .height(44.dp)
                                .border(1.dp, colors.error.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send to Repo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.error)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelLarge)
                        }

                        Button(
                            onClick = onSend,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                            modifier = Modifier
                                .weight(1.4f)
                                .height(44.dp)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = colors.onError, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send to Repo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onError)
                        }
                    }
                }
            }
        }
    }
}
