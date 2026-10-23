package com.shounak.localmeshai.rag.embedding

import java.io.File
import java.text.Normalizer

/**
 * Pure-Kotlin BERT uncased WordPiece tokenizer (BasicTokenizer + WordPiece), matching
 * Hugging Face `BertTokenizer(do_lower_case=True)` output for BERT-family embedders
 * such as bge-small-en-v1.5. No JNI, so it runs on the JVM unit-test classpath.
 */
class WordPieceTokenizer(private val vocab: Map<String, Int>, private val maxLength: Int = 512) {
    private val unkId = vocab.getValue(UNK)
    val clsId: Int = vocab.getValue(CLS)
    val sepId: Int = vocab.getValue(SEP)

    init { require(maxLength >= 2) { "maxLength must leave room for [CLS] and [SEP]" } }

    /** Returns `[CLS] tokens… [SEP]`, truncated to [maxLength]. */
    fun encode(text: String): IntArray {
        val pieces = ArrayList<Int>()
        val budget = maxLength - 2
        for (word in basicTokenize(text)) {
            for (id in wordPiece(word)) {
                if (pieces.size == budget) break
                pieces.add(id)
            }
            if (pieces.size == budget) break
        }
        return IntArray(pieces.size + 2).also { out ->
            out[0] = clsId
            pieces.forEachIndexed { i, id -> out[i + 1] = id }
            out[out.size - 1] = sepId
        }
    }

    internal fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
            when {
                isWhitespace(cp) -> cleaned.append(' ')
                isCjk(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val out = ArrayList<String>()
        for (raw in cleaned.split(' ')) {
            if (raw.isEmpty()) continue
            val lowered = stripAccents(raw.lowercase())
            val current = StringBuilder()
            var j = 0
            while (j < lowered.length) {
                val cp = lowered.codePointAt(j)
                j += Character.charCount(cp)
                if (isPunctuation(cp)) {
                    if (current.isNotEmpty()) { out.add(current.toString()); current.setLength(0) }
                    out.add(String(Character.toChars(cp)))
                } else current.appendCodePoint(cp)
            }
            if (current.isNotEmpty()) out.add(current.toString())
        }
        return out
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > MAX_CHARS_PER_WORD) return listOf(unkId)
        val ids = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { found = id; break }
                end--
            }
            if (found < 0) return listOf(unkId)
            ids.add(found)
            start = end
        }
        return ids
    }

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        private const val MAX_CHARS_PER_WORD = 100

        fun fromVocabFile(file: File, maxLength: Int = 512): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(32_768)
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line -> vocab.putIfAbsent(line.trimEnd('\r', '\n'), index) }
            }
            require(UNK in vocab && CLS in vocab && SEP in vocab) { "vocab.txt is missing BERT special tokens" }
            return WordPieceTokenizer(vocab, maxLength)
        }

        private fun stripAccents(text: String): String {
            val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
            val sb = StringBuilder(nfd.length)
            var i = 0
            while (i < nfd.length) {
                val cp = nfd.codePointAt(i)
                i += Character.charCount(cp)
                if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
            }
            return sb.toString()
        }

        private fun isWhitespace(cp: Int): Boolean =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
                Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            val type = Character.getType(cp)
            return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() ||
                type == Character.SURROGATE.toInt()
        }

        private fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        private fun isCjk(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
                cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }
}
