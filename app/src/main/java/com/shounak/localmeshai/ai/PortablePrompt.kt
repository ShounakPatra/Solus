package com.shounak.localmeshai.ai

/** Explicit fallbacks for older checkpoints without tokenizer_config.chat_template. */
internal object PortablePrompt {
    /** Chat framing follows tokenizer metadata, not the weight architecture (ChatML Llamas exist). */
    fun template(config: org.json.JSONObject): String? = when (val value = config.opt("chat_template")) {
        is String -> value.takeIf { it.isNotBlank() }
        is org.json.JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            .firstOrNull { it.optString("name") == "default" }?.optString("template")?.takeIf { it.isNotBlank() }
        is org.json.JSONObject -> value.optString("default").takeIf { it.isNotBlank() }
        null -> null
        else -> null
    }

    fun fallbackFormat(config: org.json.JSONObject, tokenizer: org.json.JSONObject, registeredTokens: Set<String>): String {
        config.optString("solus_prompt_format").takeIf { it.isNotBlank() }?.let { return it }
        // Require complete marker sets; ordinary vocabulary text is not evidence of a chat format.
        if (registeredTokens.containsAll(setOf("<|im_start|>", "<|im_end|>"))) return "chatml"
        if (registeredTokens.containsAll(setOf("<|start_header_id|>", "<|end_header_id|>", "<|eot_id|>"))) return "llama3"
        return when (config.optString("model_type")) {
            "qwen2" -> "chatml"
            "gpt2" -> "completion"
            "llama" -> if (tokenizer.toString().contains("<|start_header_id|>")) "llama3" else "llama2"
            "mistral" -> "mistral"
            "gemma" -> "gemma"
            "phi3" -> "phi3"
            "phi" -> "phi"
            else -> "plain"
        }
    }

    fun registeredTokens(tokenizer: org.json.JSONObject): Set<String> {
        val added = tokenizer.optJSONArray("added_tokens") ?: return emptySet()
        return (0 until added.length()).mapNotNull { added.optJSONObject(it)?.optString("content") }
            .filter { it.isNotBlank() }.toSet()
    }

    fun stopSequences(formatOrTemplate: String): List<String> = buildList {
        if (formatOrTemplate == "chatml" || "<|im_end|>" in formatOrTemplate) add("<|im_end|>")
        if (formatOrTemplate == "llama3" || "<|eot_id|>" in formatOrTemplate) add("<|eot_id|>")
        if (formatOrTemplate == "gemma" || "<end_of_turn>" in formatOrTemplate) add("<end_of_turn>")
        if (formatOrTemplate == "phi3" || "<|end|>" in formatOrTemplate) add("<|end|>")
    }

    fun render(format: String, messages: List<Pair<String, String>>): String {
        require(messages.all { it.first in setOf("system", "user", "assistant") })
        return when (format) {
            "completion" -> messages.joinToString("\n\n") { it.second }
            "plain" -> messages.joinToString("\n\n") { "${it.first}: ${it.second}" } + "\n\nassistant:"
            "chatml" -> messages.joinToString("") { "<|im_start|>${it.first}\n${it.second}<|im_end|>\n" } + "<|im_start|>assistant\n"
            "llama3" -> "<|begin_of_text|>" + messages.joinToString("") { "<|start_header_id|>${it.first}<|end_header_id|>\n\n${it.second}<|eot_id|>" } + "<|start_header_id|>assistant<|end_header_id|>\n\n"
            "phi3" -> messages.joinToString("") { "<|${it.first}|>\n${it.second}<|end|>\n" } + "<|assistant|>\n"
            "gemma" -> {
                val system = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
                var first = true
                "<bos>" + messages.filter { it.first != "system" }.joinToString("") { (role, text) ->
                    val content = if (first && role == "user" && system.isNotBlank()) "$system\n\n$text" else text
                    first = false
                    "<start_of_turn>${if (role == "assistant") "model" else role}\n$content<end_of_turn>\n"
                } + "<start_of_turn>model\n"
            }
            "llama2", "mistral" -> {
                val system = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
                var first = true
                buildString {
                    for ((role, text) in messages.filter { it.first != "system" }) {
                        if (role == "user") {
                            if (first || format == "llama2") append("<s>")
                            append("[INST] ")
                            if (first && system.isNotBlank()) {
                                if (format == "llama2") append("<<SYS>>\n$system\n<</SYS>>\n\n") else append("$system\n\n")
                            }
                            append(text).append(" [/INST]")
                            first = false
                        } else append(' ').append(text).append("</s>")
                    }
                }
            }
            "phi" -> messages.joinToString("\n") { (role, text) -> "${if (role == "assistant") "Output" else "Instruct"}: $text" } + "\nOutput:"
            else -> error("Unsupported prompt format $format")
        }
    }
}
