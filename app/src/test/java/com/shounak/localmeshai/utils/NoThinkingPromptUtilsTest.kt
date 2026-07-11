package com.shounak.localmeshai.utils

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NoThinkingPromptUtilsTest {
    @Test
    fun wrapPlacesNoThinkingSwitchOnceAtEnd() {
        val wrapped = NoThinkingPromptUtils.wrap("write a Java program")

        assertTrue(wrapped.startsWith("write a Java program"))
        assertTrue(wrapped.endsWith("/no_think"))
        assertTrue(wrapped.contains("write a Java program"))
        assertEquals(1, Regex(Regex.escape(NoThinkingPromptUtils.SWITCH)).findAll(wrapped).count())
    }

    @Test
    fun blankPromptReturnsOnlySwitch() {
        assertEquals(NoThinkingPromptUtils.SWITCH, NoThinkingPromptUtils.wrap("  "))
    }

    @Test
    fun wrapPlacesSwitchBeforeAssistantMarker() {
        val wrapped = NoThinkingPromptUtils.wrap(
            "User: First question\nAssistant: First answer\nUser: Latest question\nAssistant:"
        )

        assertTrue(wrapped.endsWith("User: Latest question\n/no_think\nAssistant:"))
        assertEquals(1, Regex(Regex.escape(NoThinkingPromptUtils.SWITCH)).findAll(wrapped).count())
    }

    @Test
    fun existingSwitchIsNotDuplicated() {
        val wrapped = NoThinkingPromptUtils.wrap("Explain gravity\n\n/no_think")

        assertEquals(1, wrapped.lineSequence().count { it.trim() == NoThinkingPromptUtils.SWITCH })
    }
}
