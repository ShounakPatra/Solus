package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.WordPieceTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocab = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", ",", "world", "!", "un", "##aff", "##able",
        "cafe", "resume", "(", ")", "1919", "中", "文", "."
    ).withIndex().associate { it.value to it.index }
    private val tokenizer = WordPieceTokenizer(vocab)

    @Test
    fun splitsPunctuationAndWrapsWithClsSep() {
        assertArrayEquals(intArrayOf(2, 4, 5, 6, 7, 3), tokenizer.encode("Hello, WORLD!"))
    }

    @Test
    fun greedyLongestMatchUsesContinuationPieces() {
        assertArrayEquals(intArrayOf(2, 8, 9, 10, 3), tokenizer.encode("unaffable"))
    }

    @Test
    fun lowercasesAndStripsAccents() {
        assertArrayEquals(intArrayOf(2, 11, 12, 3), tokenizer.encode("Café Résumé"))
    }

    @Test
    fun unknownWordBecomesSingleUnk() {
        assertArrayEquals(intArrayOf(2, 1, 4, 3), tokenizer.encode("zzzq hello"))
    }

    @Test
    fun emojiIsKeptAsUnknownWordNotDropped() {
        assertArrayEquals(intArrayOf(2, 4, 1, 3), tokenizer.encode("hello 😀"))
    }

    @Test
    fun cjkCharactersAreSplitIndividually() {
        assertArrayEquals(intArrayOf(2, 16, 17, 3), tokenizer.encode("中文"))
    }

    @Test
    fun controlCharactersAreRemovedAndWhitespaceNormalised() {
        assertArrayEquals(intArrayOf(2, 4, 6, 3), tokenizer.encode("hel\u0000lo\t\nworld\u200b"))
    }

    @Test
    fun truncatesToMaxLengthKeepingSep() {
        val short = WordPieceTokenizer(vocab, maxLength = 4)
        val ids = short.encode("hello world hello world")
        assertEquals(4, ids.size)
        assertArrayEquals(intArrayOf(2, 4, 6, 3), ids)
    }

    @Test
    fun blankTextIsJustClsSep() {
        assertArrayEquals(intArrayOf(2, 3), tokenizer.encode("   "))
    }
}
