package com.shounak.localmeshai.ai

import org.junit.Assert.*
import org.junit.Test

class PortablePromptTest {
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
