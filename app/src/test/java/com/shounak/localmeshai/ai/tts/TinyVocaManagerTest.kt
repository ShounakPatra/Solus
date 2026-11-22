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

    @Test
    fun testEstimateSyllables() {
        assertEquals(1, TinyVocaManager.estimateSyllables("a"))
        assertEquals(1, TinyVocaManager.estimateSyllables("is"))
        assertEquals(2, TinyVocaManager.estimateSyllables("Gemma"))
        assertEquals(2, TinyVocaManager.estimateSyllables("Model"))
        assertEquals(3, TinyVocaManager.estimateSyllables("developed"))
        assertEquals(4, TinyVocaManager.estimateSyllables("concentration"))
        assertEquals(8, TinyVocaManager.estimateSyllables("internationalization"))
    }

    @Test
    fun testCalculateTokenDelay_dynamicScaling_fromOneToInfiniteLetters() {
        val words = listOf(
            "a",                      // 1 letter
            "is",                     // 2 letters
            "model",                  // 5 letters
            "developed",              // 9 letters
            "concentration",          // 13 letters
            "internationalization",   // 20 letters
            "pneumonoultramicroscopicsilicovolcanoconiosis" // 45 letters
        )

        val delays = words.mapIndexed { idx, word ->
            TinyVocaManager.calculateTokenDelay(SpokenWordToken(idx, word, 0, word.length), 1.0f)
        }

        // Each longer word must take strictly more time than shorter words
        for (i in 0 until delays.size - 1) {
            assertTrue("Word '${words[i+1]}' (${delays[i+1]}ms) must be longer than '${words[i]}' (${delays[i]}ms)",
                delays[i + 1] > delays[i])
        }

        // "concentration" should take more than 3x the duration of "is"
        assertTrue(delays[4] > delays[1] * 3)
    }

    @Test
    fun testCalculateTokenDelay_proportionalSpeedScaling() {
        val token = SpokenWordToken(0, "concentration", 0, 13)

        val delay100 = TinyVocaManager.calculateTokenDelay(token, 1.0f)
        val delay125 = TinyVocaManager.calculateTokenDelay(token, 1.25f)
        val delay150 = TinyVocaManager.calculateTokenDelay(token, 1.5f)

        assertTrue(delay100 > delay125)
        assertTrue(delay125 > delay150)

        // Check proportionality within 5% tolerance
        val ratio125 = delay100.toDouble() / delay125.toDouble()
        val ratio150 = delay100.toDouble() / delay150.toDouble()
        assertTrue(kotlin.math.abs(ratio125 - 1.25) < 0.1)
        assertTrue(kotlin.math.abs(ratio150 - 1.5) < 0.1)
    }
}
