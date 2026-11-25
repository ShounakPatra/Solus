package com.shounak.localmeshai.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRuntimePolicyTest {
    @Test
    fun gemmaTaskBundlesRouteThroughLiteRtLmConversation() {
        assertTrue(ChatRuntimePolicy.shouldUseLiteRtLmConversation("gemma3-270m-it-q8.task", "gemma3-270m-it-q8"))
        assertTrue(ChatRuntimePolicy.shouldUseLiteRtLmConversation("/data/user/0/app/files/gemma-3n-E2B-it-int4.task", "gemma3n"))
        assertTrue(ChatRuntimePolicy.shouldUseLiteRtLmConversation("model.litertlm", "qwen3"))
        assertFalse(ChatRuntimePolicy.shouldUseLiteRtLmConversation("phi-mini.task", "phi-mini"))
        assertFalse(ChatRuntimePolicy.shouldUseLiteRtLmConversation("qwen3-1.7b.gguf", "gemma"))
    }
}
