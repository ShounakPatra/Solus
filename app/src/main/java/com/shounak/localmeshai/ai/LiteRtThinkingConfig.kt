package com.shounak.localmeshai.ai

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ThinkingConfig

/** Applies the passive `ThinkingConfig` preference when building a LiteRT-LM conversation. */
internal object LiteRtThinkingConfig {
    fun apply(base: ConversationConfig?, enabled: Boolean?): ConversationConfig {
        val config = base ?: ConversationConfig()
        return if (enabled == null) config
        else config.copy(thinkingConfig = ThinkingConfig(enableThinking = enabled))
    }
}
