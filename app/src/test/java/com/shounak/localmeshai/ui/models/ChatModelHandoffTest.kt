package com.shounak.localmeshai.ui.models

import org.junit.Assert.*
import org.junit.Test

class ChatModelHandoffTest {
    @Test fun hiddenChatKeepsItsRuntimeOnlyForTheStillSelectedModel() {
        assertTrue(ChatModelHandoff.keepsHiddenRuntime("/models/gemma.litertlm", "/models/gemma.litertlm"))
        assertFalse(ChatModelHandoff.keepsHiddenRuntime("/models/gemma.litertlm", "/models/qwen.gguf"))
        assertFalse(ChatModelHandoff.keepsHiddenRuntime("/models/gemma.litertlm", null))
        assertFalse(ChatModelHandoff.keepsHiddenRuntime(null, null))
    }

    @Test fun visibleChatDefersOnlyWhileTheOtherChatOwnsTheSameModel() {
        assertTrue(ChatModelHandoff.defersLoad("/models/gemma.litertlm", "/models/gemma.litertlm"))
        assertFalse(ChatModelHandoff.defersLoad("/models/gemma.litertlm", "/models/qwen.gguf"))
        assertFalse(ChatModelHandoff.defersLoad("/models/gemma.litertlm", null))
    }
}
