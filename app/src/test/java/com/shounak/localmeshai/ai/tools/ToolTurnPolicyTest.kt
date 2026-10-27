package com.shounak.localmeshai.ai.tools

import org.junit.Assert.*
import org.junit.Test

class ToolTurnPolicyTest {
    @Test fun standaloneGreetingsAvoidToolSchemas() {
        for (greeting in listOf("hi", " Hello! ", "HEY.", "good morning")) {
            assertFalse(greeting, ToolTurnPolicy.needsTools(listOf("system" to "Be helpful", "user" to greeting)))
        }
    }

    @Test fun toolRequestsAndFollowupsKeepToolsAvailable() {
        for (request in listOf("hi, what time is it?", "calculate 17*23", "search my documents", "what is my name")) {
            assertTrue(request, ToolTurnPolicy.needsTools(listOf("user" to request)))
        }
        assertTrue(ToolTurnPolicy.needsTools(listOf("user" to "set a reminder", "assistant" to "When?", "user" to "tomorrow")))
        assertFalse(ToolTurnPolicy.needsTools(listOf("user" to "hi", "assistant" to "Hello", "user" to "hi")))
    }
}
