package com.shounak.localmeshai.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class NoThinkingPromptUtilsTest {
    @Test
    fun wrapPlacesNoThinkingSwitchAtStartAndEnd() {
        val wrapped = NoThinkingPromptUtils.wrap("write a Java program")

        assertTrue(wrapped.startsWith("/no_think\n"))
        assertTrue(wrapped.endsWith("/no_think"))
        assertTrue(wrapped.contains("write a Java program"))
    }

    @Test
    fun systemMessageDisablesThinkingMode() {
        val systemMessage = NoThinkingPromptUtils.systemMessage

        assertTrue(systemMessage.contains("/no_think"))
    }
}
