package com.shounak.localmeshai.ui.models

import org.junit.Assert.*
import org.junit.Test

class AutomaticModelSelectionTest {
    @Test fun returningToTabDoesNotReloadButExplicitRetryOrSelectionDoes() {
        val selection = AutomaticModelSelection()
        assertTrue(selection.shouldLoad("model.gguf", "model", false, 0))
        assertFalse(selection.shouldLoad("model.gguf", "model", false, 0))
        assertTrue(selection.shouldLoad("model.gguf", "model", false, 1))
        assertFalse(selection.shouldLoad(null, null, false, 0))
        assertTrue(selection.shouldLoad("model.gguf", "model", false, 1))
    }
}
