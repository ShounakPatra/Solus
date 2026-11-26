package com.shounak.localmeshai.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSpeechChunkerTest {

    @Test
    fun `splits streaming sentences at boundaries and flushes remainder`() {
        val chunker = SentenceSpeechChunker()

        assertTrue(chunker.updateSnapshot("Hello Dr. Smith").isEmpty())
        assertTrue(chunker.updateSnapshot("Hello Dr. Smith, pi is 3.14").isEmpty())

        val first = chunker.updateSnapshot("Hello Dr. Smith, pi is 3.14 right? Yes")
        assertEquals(listOf("Hello Dr. Smith, pi is 3.14 right?"), first)

        val second = chunker.updateSnapshot("Hello Dr. Smith, pi is 3.14 right? Yes it is! Wait...")
        assertEquals(listOf("Yes it is!"), second)

        val tail = chunker.flushRemainder()
        assertEquals("Wait...", tail)
        assertNull(chunker.flushRemainder())
    }

    @Test
    fun `strips markdown URLs code blocks and holds unclosed think chains`() {
        val chunker = SentenceSpeechChunker()

        // Unclosed think block should not emit TTS
        assertTrue(chunker.updateSnapshot("<think>Internal thought. More thought.").isEmpty())

        // Once closed, multi-sentence internal reasoning stays silent and only visible answer is spoken
        val emitted = chunker.updateSnapshot(
            "<think>Internal thought. More thought.</think>Check **bold** [docs](https://example.com) now. Done!"
        )
        assertEquals(listOf("Check bold docs now."), emitted)
        assertEquals("Done!", chunker.flushRemainder())
    }

    @Test
    fun `replaces fenced code blocks with clean speech summary`() {
        val chunker = SentenceSpeechChunker()
        val emitted = chunker.updateSnapshot("Here is code.\n```kotlin\nval x = 1\n```\nAll set.")
        val all = emitted + listOfNotNull(chunker.flushRemainder())
        assertEquals(listOf("Here is code.", "Code block omitted.", "All set."), all)
    }
}
