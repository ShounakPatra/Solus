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
}
