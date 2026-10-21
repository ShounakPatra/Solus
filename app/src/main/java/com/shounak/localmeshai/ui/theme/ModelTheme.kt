package com.shounak.localmeshai.ui.theme

import androidx.compose.ui.graphics.Color
import java.util.Locale

object ModelTheme {
    fun getAccentColor(modelId: String?, modelName: String? = null): Color {
        val source = "${modelId.orEmpty()} ${modelName.orEmpty()}".lowercase(Locale.US)
        if (source.isBlank()) return Color(0xFF3B82F6) // Default Solus Blue
        return when {
            source.contains("deepseek") -> Color(0xFF00E5FF) // Cyber Cyan
            source.contains("gemma") -> Color(0xFFFFB300)    // Amber Gold
            source.contains("qwen") -> Color(0xFFAB47BC)     // Electric Violet
            source.contains("llama") -> Color(0xFF00E676)    // Emerald Green
            source.contains("phi") -> Color(0xFF29B6F6)      // Sky Blue
            source.contains("mistral") || source.contains("ministral") -> Color(0xFFFF7043)  // Sunset Coral
            source.contains("fastvlm") -> Color(0xFFFF4081)  // Neon Pink
            source.contains("smollm") -> Color(0xFFFFAB00)   // Warm Amber
            else -> Color(0xFF3B82F6)                        // Solus Primary Blue
        }
    }
}
