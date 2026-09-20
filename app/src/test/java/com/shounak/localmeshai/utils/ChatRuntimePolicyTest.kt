package com.shounak.localmeshai.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRuntimePolicyTest {
    @Test
    fun gemmaTaskUsesConversationNativeLiteRtRuntime() {
        assertTrue(
            ChatRuntimePolicy.shouldUseLiteRtLmConversation(
                fileName = "gemma3-270m-it-q8.task",
                modelIdentity = "gemma3_270m_q8 Gemma 3 270M"
            )
        )
    }

    @Test
    fun allLiteRtLmFilesUseConversationRuntime() {
        assertTrue(
            ChatRuntimePolicy.shouldUseLiteRtLmConversation(
                fileName = "Qwen3_1.7B.litertlm",
                modelIdentity = "Qwen 3 1.7B"
            )
        )
    }

    @Test
    fun nonGemmaTaskKeepsMediaPipeFallback() {
        assertFalse(
            ChatRuntimePolicy.shouldUseLiteRtLmConversation(
                fileName = "tinyllama.task",
                modelIdentity = "TinyLlama"
            )
        )
    }

    @Test
    fun ggufFilesAreIdentifiedAsGguf() {
        assertTrue(ChatRuntimePolicy.isGgufModel("qwen2.5-1.5b-instruct-q4_k_m.gguf"))
        assertTrue(ChatRuntimePolicy.isGgufModel("Llama-3.2-1B-Instruct-Q4_K_M.GGUF"))
        assertTrue(ChatRuntimePolicy.isGgufModel("Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"))
        assertFalse(ChatRuntimePolicy.isGgufModel("model.litertlm"))
        assertFalse(ChatRuntimePolicy.isGgufModel("model.task"))
        assertFalse(ChatRuntimePolicy.isGgufModel("model.safetensors"))
    }
}
