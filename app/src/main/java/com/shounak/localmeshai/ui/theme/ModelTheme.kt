package com.shounak.localmeshai.ui.theme

import androidx.compose.ui.graphics.Color

object ModelTheme {
    fun getAccentColor(modelId: String?): Color {
        if (modelId == null) return Color(0xFF3B82F6) // Default Solus Blue
        val lower = modelId.lowercase()
        return when {
            lower.contains("deepseek") -> Color(0xFF00E5FF) // Cyber Cyan
            lower.contains("gemma") -> Color(0xFFFFB300)    // Amber Gold
            lower.contains("qwen") -> Color(0xFFAB47BC)     // Electric Violet
            lower.contains("llama") -> Color(0xFF00E676)    // Emerald Green
            lower.contains("phi") -> Color(0xFF29B6F6)      // Sky Blue
            lower.contains("mistral") -> Color(0xFFFF7043)  // Sunset Coral
            else -> Color(0xFF3B82F6)                      // Solus Primary Blue
        }
    }
}
