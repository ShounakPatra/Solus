package com.shounak.localmeshai.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class PersonaPreset(
    val id: String,
    val title: String,
    val systemPrompt: String
)

val defaultPersonas = listOf(
    PersonaPreset("default", "🤖 General", ""),
    PersonaPreset("coder", "💻 Code Auditor", "You are an expert software engineer and Kotlin/Python code auditor. Provide high-quality, concise, production-ready code with explanations."),
    PersonaPreset("eli5", "💡 Simple ELI5", "Explain everything in plain, simple, beginner-friendly terms with easy everyday analogies."),
    PersonaPreset("proofreader", "📝 Proofreader", "Fix grammar, refine tone, improve clarity, and output clean markdown text without unnecessary chatter."),
    PersonaPreset("translator", "🌐 Translator", "Translate accurate context and preserve natural nuance across languages.")
)

@Composable
fun SystemPromptPresetsBar(
    selectedPersonaId: String,
    hazeState: dev.chrisbanes.haze.HazeState,
    onSelectPersona: (PersonaPreset) -> Unit,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        defaultPersonas.forEach { persona ->
            val isSelected = persona.id == selectedPersonaId
            val personaAccent = when (persona.id) {
                "coder" -> Color(0xFF38BDF8)
                "eli5" -> Color(0xFFA78BFA)
                "proofreader" -> Color(0xFF34D399)
                "translator" -> Color(0xFFF472B6)
                else -> accentColor
            }
            FloatingBlurChip(hazeState = hazeState, selected = isSelected,
                onClick = { onSelectPersona(persona) }, accent = personaAccent) {
                Text(persona.title, style = MaterialTheme.typography.labelSmall,
                    color = personaAccent)
            }
        }
    }
}
