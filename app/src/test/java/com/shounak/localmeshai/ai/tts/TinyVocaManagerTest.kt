package com.shounak.localmeshai.ai.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TinyVocaManagerTest {

    @Test
    fun testCleanTextForSpeech_stripsThinkingTags() {
        val input = "<think>\nThinking about life...\n</think>Hello world! How are you?"
        val cleaned = TinyVocaManager.cleanTextForSpeech(input)
        assertEquals("Hello world! How are you?", cleaned)
        assertFalse(cleaned.contains("Thinking"))
    }

    @Test
    fun testCleanTextForSpeech_stripsCodeBlocks() {
        val input = "Here is the code:\n```python\nprint('hello')\n```\nIt works!"
        val cleaned = TinyVocaManager.cleanTextForSpeech(input)
        assertTrue(cleaned.contains("Code block omitted"))
        assertFalse(cleaned.contains("print('hello')"))
        assertTrue(cleaned.contains("It works!"))
    }

    @Test
    fun testCleanTextForSpeech_stripsMarkdownAndUrls() {
        val input = "# Title\nCheck this **bold** and *italic* link: https://huggingface.co/n4ze3m/tiny-voca"
        val cleaned = TinyVocaManager.cleanTextForSpeech(input)
        assertEquals("Title Check this bold and italic link:", cleaned)
        assertFalse(cleaned.contains("#"))
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("https://"))
    }

    @Test
    fun testCleanTextForSpeech_emptyOrBlank() {
        val input = "   <think>foo</think>   "
        val cleaned = TinyVocaManager.cleanTextForSpeech(input)
        assertTrue(cleaned.isBlank())
    }
}
