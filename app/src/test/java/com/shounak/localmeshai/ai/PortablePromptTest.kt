package com.shounak.localmeshai.ai

import org.junit.Assert.*
import org.junit.Test

class PortablePromptTest {
    @Test fun chatMlLlamaUsesTokenizerMarkersAcrossPortableBackends() {
        val config = org.json.JSONObject().put("model_type", "llama")
        val metadata = org.json.JSONObject("""{"added_tokens":[{"content":"<|im_start|>"},{"content":"<|im_end|>"}]}""")
        val format = PortablePrompt.fallbackFormat(config, org.json.JSONObject(), PortablePrompt.registeredTokens(metadata))
        assertEquals("chatml", format)
        val first = PortablePrompt.render(format, listOf("user" to "hi"))
        val second = PortablePrompt.render(format, listOf("user" to "who are you"))
        assertNotEquals(first, second)
        assertTrue(second.contains("<|im_start|>user\nwho are you<|im_end|>"))
        assertFalse(second.contains("[INST]"))
        assertEquals(listOf("<|im_end|>"), PortablePrompt.stopSequences(format))
    }

    @Test fun explicitOverrideWinsAndBaseModelsKeepTheirFormat() {
        val markers = setOf("<|im_start|>", "<|im_end|>")
        assertEquals("plain", PortablePrompt.fallbackFormat(org.json.JSONObject().put("solus_prompt_format", "plain"), org.json.JSONObject(), markers))
        for ((model, expected) in listOf("gpt2" to "completion", "llama" to "llama2", "qwen2" to "chatml", "gemma" to "gemma", "phi3" to "phi3")) {
            assertEquals(expected, PortablePrompt.fallbackFormat(org.json.JSONObject().put("model_type", model), org.json.JSONObject(), emptySet()))
        }
        assertEquals("llama2", PortablePrompt.fallbackFormat(org.json.JSONObject().put("model_type", "llama"), org.json.JSONObject(), setOf("<|im_start|>")))
    }

    @Test fun allSupportedTemplateMetadataFormsSelectDefault() {
        val source = "{{ messages }}"
        assertEquals(source, PortablePrompt.template(org.json.JSONObject().put("chat_template", source)))
        assertEquals(source, PortablePrompt.template(org.json.JSONObject().put("chat_template", org.json.JSONObject().put("default", source))))
        assertEquals(source, PortablePrompt.template(org.json.JSONObject().put("chat_template", org.json.JSONArray()
            .put(org.json.JSONObject().put("name", "tool_use").put("template", "tools"))
            .put(org.json.JSONObject().put("name", "default").put("template", source)))))
        assertNull(PortablePrompt.template(org.json.JSONObject().put("chat_template", org.json.JSONObject())))
    }

    private val history = listOf("system" to "Be concise.","user" to "Hello","assistant" to "Hi","user" to "Next")
    @Test fun olderChatMlLlamaTemplateUsesChatMlBoundary() {
        val template = "{% for message in messages %}{{ '<|im_start|>' + message['role'] + message['content'] + '<|im_end|>' }}{% endfor %}"
        assertEquals(listOf("<|im_end|>"), PortablePrompt.stopSequences(template))
        assertTrue(PortablePrompt.stopSequences("completion").isEmpty())
    }
    @Test fun chatmlAndLlama3HaveOneGenerationPrefix() {
        val chatml = PortablePrompt.render("chatml",history)
        assertTrue(chatml.startsWith("<|im_start|>system\nBe concise.<|im_end|>"))
        assertTrue(chatml.endsWith("<|im_start|>assistant\n"))
        val llama = PortablePrompt.render("llama3",history)
        assertTrue(llama.startsWith("<|begin_of_text|>"))
        assertTrue(llama.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }
    @Test fun gemmaMergesSystemAndUsesModelRole() {
        val rendered = PortablePrompt.render("gemma",history)
        assertTrue(rendered.startsWith("<bos><start_of_turn>user\nBe concise.\n\nHello"))
        assertFalse(rendered.contains("<start_of_turn>system"))
        assertTrue(rendered.contains("<start_of_turn>model\nHi"))
        assertTrue(rendered.endsWith("<start_of_turn>model\n"))
    }
    @Test fun llama2UsesItsSystemBlockAndMistralDoesNot() {
        assertTrue(PortablePrompt.render("llama2",history).contains("<<SYS>>\nBe concise.\n<</SYS>>"))
        val mistral = PortablePrompt.render("mistral",history)
        assertFalse(mistral.contains("<<SYS>>"))
        assertTrue(mistral.contains("[INST] Be concise.\n\nHello [/INST] Hi</s>"))
    }
    @Test fun baseCompletionIsNotTurnedIntoAnInstructionModel() {
        assertEquals("The quick brown",PortablePrompt.render("completion",listOf("user" to "The quick brown")))
        assertEquals("<|user|>\nHello<|end|>\n<|assistant|>\n",PortablePrompt.render("phi3",listOf("user" to "Hello")))
    }
}
