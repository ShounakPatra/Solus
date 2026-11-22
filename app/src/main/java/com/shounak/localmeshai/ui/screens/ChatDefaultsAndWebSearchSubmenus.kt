package com.shounak.localmeshai.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.ai.tools.DuckDuckGoSearchEngine
import com.shounak.localmeshai.ai.tools.SearchResult
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.AppSettingsData
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ChatDefaultsSubmenu(
    appSettings: AppSettings,
    settingsData: AppSettingsData,
    hazeState: HazeState
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "These defaults apply to every new answer generated on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )

        // 1. Temperature
        ChatDefaultSliderRow(
            title = "Temperature",
            valueText = String.format(Locale.US, "%.1f", settingsData.defaultTemperature),
            subtitle = "Lower is deterministic and focused; higher produces more diverse text.",
            value = settingsData.defaultTemperature,
            valueRange = 0.0f..2.0f,
            step = 0.1f,
            hazeState = hazeState,
            onValueChange = { newVal ->
                appSettings.updateSettings { it.copy(defaultTemperature = newVal) }
            }
        )

        // 2. Top P
        ChatDefaultSliderRow(
            title = "Top P",
            valueText = String.format(Locale.US, "%.2f", settingsData.defaultTopP),
            subtitle = "Nucleus sampling threshold.",
            value = settingsData.defaultTopP,
            valueRange = 0.05f..1.00f,
            step = 0.05f,
            hazeState = hazeState,
            onValueChange = { newVal ->
                appSettings.updateSettings { it.copy(defaultTopP = newVal) }
            }
        )

        // 3. Top K
        ChatDefaultSliderRow(
            title = "Top K",
            valueText = "${settingsData.defaultTopK}",
            subtitle = "Limits token candidates to the K most likely tokens.",
            value = settingsData.defaultTopK.toFloat(),
            valueRange = 1.0f..100.0f,
            step = 5.0f,
            hazeState = hazeState,
            onValueChange = { newVal ->
                appSettings.updateSettings { it.copy(defaultTopK = newVal.toInt()) }
            }
        )

        // 4. Max length
        ChatDefaultSliderRow(
            title = "Max length",
            valueText = if (settingsData.defaultMaxLength <= 0) "Auto" else "${settingsData.defaultMaxLength}",
            subtitle = "Longest answer in tokens. 0 lets the model decide naturally.",
            value = settingsData.defaultMaxLength.toFloat(),
            valueRange = 0.0f..4096.0f,
            step = 128.0f,
            hazeState = hazeState,
            onValueChange = { newVal ->
                appSettings.updateSettings { it.copy(defaultMaxLength = newVal.toInt()) }
            }
        )

        // 5. Compact automatically at
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .glassEffect(
                    hazeState = hazeState,
                    shape = RoundedCornerShape(18.dp),
                    blurRadius = 20.dp,
                    tintColor = colors.surfaceContainer.copy(alpha = 0.50f),
                    borderAlpha = 0.35f
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Compact automatically at",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiquidGlassButton(
                        onClick = {
                            val next = (settingsData.compactAutomaticallyAt - 10).coerceAtLeast(0)
                            appSettings.updateSettings { it.copy(compactAutomaticallyAt = next) }
                        },
                        hazeState = hazeState,
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("—", color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .widthIn(min = 60.dp)
                            .glassEffect(
                                hazeState = hazeState,
                                shape = RoundedCornerShape(12.dp),
                                blurRadius = 14.dp,
                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.40f),
                                borderAlpha = 0.25f
                            )
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${settingsData.compactAutomaticallyAt}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }

                    LiquidGlassButton(
                        onClick = {
                            val next = (settingsData.compactAutomaticallyAt + 10).coerceAtMost(90)
                            appSettings.updateSettings { it.copy(compactAutomaticallyAt = next) }
                        },
                        hazeState = hazeState,
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("+", color = colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = if (settingsData.compactAutomaticallyAt <= 0) "Off" else "${settingsData.compactAutomaticallyAt}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (settingsData.compactAutomaticallyAt <= 0) colors.onSurfaceVariant else colors.primary
                    )
                }

                Text(
                    text = "When chat context fills this share of the active model memory, Solus automatically summarizes older turns. 0 turns it off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }

        // Voice & Speech Settings (TinyVoca)
        VoiceSettingsSection(appSettings, settingsData, hazeState)
    }
}

@Composable
fun VoiceSettingsSubmenu(
    appSettings: AppSettings,
    settingsData: AppSettingsData,
    hazeState: HazeState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        VoiceSettingsSection(appSettings, settingsData, hazeState)
    }
}

@Composable
fun VoiceSettingsSection(
    appSettings: AppSettings,
    settingsData: AppSettingsData,
    hazeState: HazeState
) {
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .glassEffect(
                hazeState = hazeState,
                shape = RoundedCornerShape(18.dp),
                blurRadius = 20.dp,
                tintColor = colors.surfaceContainer.copy(alpha = 0.50f),
                borderAlpha = 0.35f
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Voice & Speech (TinyVoca)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )

            // Voice Switcher (F2 Female vs M2 Male)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Voice",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isF2 = settingsData.ttsVoice.equals("F2", ignoreCase = true)
                    LiquidGlassButton(
                        onClick = { appSettings.updateSettings { it.copy(ttsVoice = "F2") } },
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = if (isF2) colors.primary.copy(alpha = 0.30f) else colors.surfaceContainerHighest.copy(alpha = 0.30f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "F2 (Female)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isF2) FontWeight.Bold else FontWeight.Normal,
                            color = if (isF2) colors.primary else colors.onSurface
                        )
                    }

                    val isM2 = settingsData.ttsVoice.equals("M2", ignoreCase = true)
                    LiquidGlassButton(
                        onClick = { appSettings.updateSettings { it.copy(ttsVoice = "M2") } },
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = if (isM2) colors.primary.copy(alpha = 0.30f) else colors.surfaceContainerHighest.copy(alpha = 0.30f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "M2 (Male)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isM2) FontWeight.Bold else FontWeight.Normal,
                            color = if (isM2) colors.primary else colors.onSurface
                        )
                    }
                }
            }

            // Speed Adjustment (1.0x / 1.25x / 1.5x)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Speech Speed",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val speeds = listOf(1.0f to "1.0x", 1.25f to "1.25x", 1.5f to "1.5x")
                    speeds.forEach { (speed, label) ->
                        val isSelected = kotlin.math.abs(settingsData.ttsSpeed - speed) < 0.05f
                        LiquidGlassButton(
                            onClick = { appSettings.updateSettings { it.copy(ttsSpeed = speed) } },
                            hazeState = hazeState,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            tintColor = if (isSelected) colors.primary.copy(alpha = 0.30f) else colors.surfaceContainerHighest.copy(alpha = 0.30f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) colors.primary else colors.onSurface
                            )
                        }
                    }
                }
            }

            // Live Text Highlighting Switch with Glass UI
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassEffect(
                        hazeState = hazeState,
                        shape = RoundedCornerShape(14.dp),
                        blurRadius = 16.dp,
                        tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                        borderAlpha = 0.30f
                    ),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Live Text Highlight",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "BETA",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Text(
                            text = "Highlight words in real time as the voice model reads aloud",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                    SettingsSwitch(
                        checked = settingsData.enableVoiceLiveHighlight,
                        onCheckedChange = { isChecked ->
                            appSettings.updateSettings { it.copy(enableVoiceLiveHighlight = isChecked) }
                        }
                    )
                }
            }
        }
    }

    // TinyVoca Model Manager Card
    com.shounak.localmeshai.ui.components.TinyVocaModelCard(hazeState = hazeState)
}

@Composable
private fun ChatDefaultSliderRow(
    title: String,
    valueText: String,
    subtitle: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    hazeState: HazeState,
    onValueChange: (Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .glassEffect(
                hazeState = hazeState,
                shape = RoundedCornerShape(18.dp),
                blurRadius = 20.dp,
                tintColor = colors.surfaceContainer.copy(alpha = 0.50f),
                borderAlpha = 0.35f
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiquidGlassButton(
                    onClick = {
                        val updated = (value - step).coerceIn(valueRange.start, valueRange.endInclusive)
                        onValueChange(updated)
                    },
                    hazeState = hazeState,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("—", color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.surfaceContainerHighest.copy(alpha = 0.60f)
                    )
                )

                LiquidGlassButton(
                    onClick = {
                        val updated = (value + step).coerceIn(valueRange.start, valueRange.endInclusive)
                        onValueChange(updated)
                    },
                    hazeState = hazeState,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+", color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun WebSearchSubmenu(
    appSettings: AppSettings,
    settingsData: AppSettingsData,
    hazeState: HazeState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var braveInput by remember { mutableStateOf(settingsData.braveApiKey) }
    var tavilyInput by remember { mutableStateOf(settingsData.tavilyApiKey) }
    var isTestingSearch by remember { mutableStateOf(false) }
    var testResultDialog by remember { mutableStateOf<SearchResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Master on/off toggle card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .glassEffect(
                    hazeState = hazeState,
                    shape = RoundedCornerShape(18.dp),
                    blurRadius = 20.dp,
                    tintColor = colors.surfaceContainer.copy(alpha = 0.50f),
                    borderAlpha = 0.35f
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Enable Web Search",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Allow supported models to look up current live web data",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }

                Switch(
                    checked = settingsData.enableWebSearch,
                    onCheckedChange = { enabled ->
                        appSettings.updateSettings { it.copy(enableWebSearch = enabled) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primary,
                        checkedTrackColor = colors.primary.copy(alpha = 0.35f)
                    )
                )
            }
        }

        // Section: SEARCH PROVIDER
        Text(
            text = "SEARCH PROVIDER",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .glassEffect(
                    hazeState = hazeState,
                    shape = RoundedCornerShape(18.dp),
                    blurRadius = 20.dp,
                    tintColor = colors.surfaceContainer.copy(alpha = 0.50f),
                    borderAlpha = 0.35f
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val providers = listOf(
                    "Auto" to "Uses your key when one is saved, else free search.",
                    "DuckDuckGo" to "Free. No key needed. Runs fully client-side.",
                    "Brave" to "Add a Brave API key below first.",
                    "Tavily" to "Add a Tavily API key below first."
                )

                providers.forEachIndexed { index, (providerName, desc) ->
                    val isSelected = settingsData.webSearchProvider == providerName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                appSettings.updateSettings { it.copy(webSearchProvider = providerName) }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = providerName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                appSettings.updateSettings { it.copy(webSearchProvider = providerName) }
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.primary,
                                unselectedColor = colors.onSurfaceVariant
                            )
                        )
                    }

                    if (index < providers.size - 1) {
                        HorizontalDivider(
                            color = colors.outlineVariant.copy(alpha = 0.25f),
                            thickness = 0.6.dp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }

        // Section: API KEYS
        Text(
            text = "API KEYS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        ApiKeyEntryBlock(
            label = "Brave API key",
            value = braveInput,
            hazeState = hazeState,
            onValueChange = { braveInput = it },
            onSave = {
                appSettings.updateSettings { it.copy(braveApiKey = braveInput.trim()) }
                Toast.makeText(context, "Brave API key saved", Toast.LENGTH_SHORT).show()
            }
        )

        ApiKeyEntryBlock(
            label = "Tavily API key",
            value = tavilyInput,
            hazeState = hazeState,
            onValueChange = { tavilyInput = it },
            onSave = {
                appSettings.updateSettings { it.copy(tavilyApiKey = tavilyInput.trim()) }
                Toast.makeText(context, "Tavily API key saved", Toast.LENGTH_SHORT).show()
            }
        )

        Text(
            text = "Keys are kept securely on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom Test Search Button with Liquid Glass UI
        LiquidGlassButton(
            onClick = {
                if (isTestingSearch) return@LiquidGlassButton
                isTestingSearch = true
                scope.launch {
                    try {
                        val result = DuckDuckGoSearchEngine.searchWithProvider(
                            query = "Solus Android AI on-device",
                            provider = settingsData.webSearchProvider,
                            braveApiKey = settingsData.braveApiKey,
                            tavilyApiKey = settingsData.tavilyApiKey,
                            maxResults = 3
                        )
                        testResultDialog = result
                    } catch (e: Exception) {
                        Toast.makeText(context, "Search failed: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isTestingSearch = false
                    }
                }
            },
            hazeState = hazeState,
            shape = CircleShape,
            tintColor = colors.primary.copy(alpha = 0.35f),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (isTestingSearch) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = colors.primary,
                    strokeWidth = 2.dp
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = colors.primary
                    )
                    Text(
                        text = "Test search",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary
                    )
                }
            }
        }
    }

    // Scrollable Search Test Results Dialog with Glass UI
    testResultDialog?.let { res ->
        Dialog(
            onDismissRequest = { testResultDialog = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 540.dp)
                    .glassEffect(
                        hazeState = hazeState,
                        shape = RoundedCornerShape(24.dp),
                        blurRadius = 24.dp,
                        tintColor = colors.surfaceContainer.copy(alpha = 0.65f),
                        borderAlpha = 0.40f
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Search Test Results (${res.provider})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.onSurface
                        )
                        LiquidGlassButton(
                            onClick = { testResultDialog = null },
                            hazeState = hazeState,
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = colors.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Text("Query: \"${res.query}\"", fontWeight = FontWeight.SemiBold, color = colors.primary)

                    if (res.items.isEmpty()) {
                        Text(res.note.ifBlank { "No items returned." }, color = colors.onSurfaceVariant)
                    } else {
                        res.items.forEachIndexed { i, it ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassEffect(
                                        hazeState = hazeState,
                                        shape = RoundedCornerShape(14.dp),
                                        blurRadius = 14.dp,
                                        tintColor = colors.surfaceContainerHighest.copy(alpha = 0.40f),
                                        borderAlpha = 0.25f
                                    ),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("${i + 1}. ${it.title}", fontWeight = FontWeight.Bold, color = colors.primary)
                                    Text(it.snippet, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                                    Text(it.url, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LiquidGlassButton(
                        onClick = { testResultDialog = null },
                        hazeState = hazeState,
                        shape = RoundedCornerShape(14.dp),
                        tintColor = colors.primary.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold, color = colors.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyEntryBlock(
    label: String,
    value: String,
    hazeState: HazeState,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .glassEffect(
                hazeState = hazeState,
                shape = RoundedCornerShape(18.dp),
                blurRadius = 20.dp,
                tintColor = colors.surfaceContainer.copy(alpha = 0.50f),
                borderAlpha = 0.35f
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassEffect(
                        hazeState = hazeState,
                        shape = RoundedCornerShape(12.dp),
                        blurRadius = 14.dp,
                        tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                        borderAlpha = 0.25f
                    )
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Paste key here", color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface
                    ),
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                        if (!clip.isNullOrBlank()) {
                            onValueChange(clip.trim())
                            Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Paste key",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            val hasKey = value.isNotBlank()
            LiquidGlassButton(
                onClick = onSave,
                enabled = hasKey,
                hazeState = hazeState,
                shape = RoundedCornerShape(12.dp),
                tintColor = if (hasKey) colors.primary.copy(alpha = 0.30f) else colors.surfaceContainerHighest.copy(alpha = 0.20f),
                modifier = Modifier.fillMaxWidth(0.42f).height(40.dp)
            ) {
                Text(
                    text = "Save key",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasKey) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.50f)
                )
            }
        }
    }
}
