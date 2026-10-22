package com.shounak.localmeshai.ai

/** Explicit fallbacks for older checkpoints without tokenizer_config.chat_template. */
internal object PortablePrompt {
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
