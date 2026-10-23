package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ThinkingCapability
import org.json.JSONObject

internal object PortableThinking {
    fun capability(config: JSONObject, tokenizer: JSONObject): ThinkingCapability {
        val declared = config.optString("solus_thinking")
        if (declared == "unsupported") return ThinkingCapability.Unsupported
        if (declared == "model_controlled") return ThinkingCapability.ModelControlled
        val template = tokenizer.opt("chat_template")?.toString().orEmpty()
        return when {
            template.contains("enable_thinking") -> ThinkingCapability.Toggleable
            template.contains("<think>") || declared == "toggleable" -> ThinkingCapability.ModelControlled
            else -> ThinkingCapability.Unsupported
        }
    }
    fun hasReasoningPrefill(prompt: String): Boolean = prompt.trimEnd().endsWith("<think>")
}
