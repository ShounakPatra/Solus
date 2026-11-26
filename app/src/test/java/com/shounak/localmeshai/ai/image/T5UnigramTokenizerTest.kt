package com.shounak.localmeshai.ai.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T5UnigramTokenizerTest {
    private val sampleVocabJson = """
        [
          ["<pad>", 0.0],
          ["</s>", 0.0],
          ["<unk>", 0.0],
          ["\u2581cat", -1.2],
          ["\u2581", -2.5],
          ["cat", -2.8],
          ["s", -1.5],
          ["\u2581in", -1.1],
          ["\u2581fi", -1.3],
          ["\u2581A", -1.4],
          ["\u2581hat", -1.2]
        ]
    """.trimIndent()

    @Test
    fun `encode matches SentencePiece Viterbi and pads to 256`() {
        val tokenizer = T5UnigramTokenizer.fromVocabJson(sampleVocabJson)
        val raw = tokenizer.encode("cats in hat")
        // "\u2581cat" (3) + "s" (6) + "\u2581in" (7) + "\u2581hat" (10) + "</s>" (1)
        assertArrayEquals(intArrayOf(3, 6, 7, 10, 1), raw)

        val padded = tokenizer.encodePadded("cats in hat", maxLength = 256)
        assertEquals(256, padded.inputIds.size)
        assertEquals(256, padded.attentionMask.size)
        assertEquals(3L, padded.inputIds[0])
        assertEquals(6L, padded.inputIds[1])
        assertEquals(7L, padded.inputIds[2])
        assertEquals(10L, padded.inputIds[3])
        assertEquals(1L, padded.inputIds[4])
        assertEquals(0L, padded.inputIds[5])
        assertEquals(1L, padded.attentionMask[4])
        assertEquals(0L, padded.attentionMask[5])
        assertTrue(padded.inputIds.drop(5).all { it == 0L })
        assertTrue(padded.attentionMask.drop(5).all { it == 0L })
    }

    @Test
    fun `truncates overlong prompts while preserving trailing EOS`() {
        val tokenizer = T5UnigramTokenizer.fromVocabJson(sampleVocabJson)
        val padded = tokenizer.encodePadded("cat ".repeat(40), maxLength = 16)
        assertEquals(16, padded.inputIds.size)
        assertEquals(16, padded.attentionMask.size)
        assertEquals(1L, padded.inputIds[15])
        assertTrue(padded.attentionMask.all { it == 1L })
    }

    @Test
    fun `normalizes NFKC ligatures and fullwidth ASCII and collapses consecutive UNK`() {
        val tokenizer = T5UnigramTokenizer.fromVocabJson(sampleVocabJson)
        // \uFB01 ("fi") -> "\u2581fi" (8); \uFF21 ('A') -> "\u2581A" (9); "qqq" -> "\u2581" (4) + single UNK (2)
        val encoded = tokenizer.encode("\uFB01 \uFF21 qqq")
        assertArrayEquals(intArrayOf(8, 9, 4, 2, 1), encoded)
    }

    @Test
    fun `loads Hugging Face tokenizer json unigram array`() {
        val tokenizerJson = """
            {
              "model": {
                "type": "Unigram",
                "unk_id": 2,
                "vocab": $sampleVocabJson
              }
            }
        """.trimIndent()
        val tokenizer = T5UnigramTokenizer.fromTokenizerJson(tokenizerJson)
        val encoded = tokenizer.encodePadded("cat", maxLength = 8)
        assertEquals(8, encoded.inputIds.size)
        assertEquals(3L, encoded.inputIds[0])
        assertEquals(1L, encoded.inputIds[1])
        assertEquals(0L, encoded.inputIds[2])
    }
}
