package com.shounak.localmeshai.ai

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ThinkingConfig

/** Apply native thinking before creation/history prefill, rather than only on the next send. */
internal object LiteRtThinkingConfig {
    fun apply(base: ConversationConfig?, enabled: Boolean?, softSwitch: Boolean): ConversationConfig {
        val config = base ?: ConversationConfig()
        return if (enabled == null || softSwitch) config
        else config.copy(thinkingConfig = ThinkingConfig(enableThinking = enabled))
    }
}
