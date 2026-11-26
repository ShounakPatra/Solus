package com.shounak.localmeshai.ai.image

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

data class EncodedPrompt(
    val inputIds: LongArray,
    val attentionMask: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedPrompt) return false
        return inputIds.contentEquals(other.inputIds) && attentionMask.contentEquals(other.attentionMask)
    }

    override fun hashCode(): Int = 31 * inputIds.contentHashCode() + attentionMask.contentHashCode()
}

/**
 * Pure Kotlin T5 SentencePiece/Unigram tokenizer matching Hugging Face / transformers.js / MiniT2I
 * `tokenizer.ts` behavior (`\u2581` whitespace prefix, NFKC normalization, Viterbi DP decoding).
 */
class T5UnigramTokenizer private constructor(
    private val pieceToId: Map<String, Int>,
    private val pieceScores: DoubleArray,
    private val maxPieceLen: Int,
    private val unkScore: Double,
    val padId: Int = DEFAULT_PAD_ID,
    val eosId: Int = DEFAULT_EOS_ID,
    val unkId: Int = DEFAULT_UNK_ID
) {
    fun encode(text: String): IntArray {
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        if (normalized.isEmpty()) return intArrayOf(eosId)

        val words = normalized.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val result = ArrayList<Int>(words.size * 2 + 1)
        for (word in words) {
            encodeWord("$SPIECE_PREFIX$word", result)
        }
        result.add(eosId)
        return result.toIntArray()
    }

    fun encodePadded(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): EncodedPrompt {
        require(maxLength >= 2) { "maxLength must be >= 2" }
        val rawTokens = encode(text)
        val effective = if (rawTokens.size > maxLength) {
            IntArray(maxLength).also { out ->
                System.arraycopy(rawTokens, 0, out, 0, maxLength - 1)
                out[maxLength - 1] = eosId
            }
        } else {
            rawTokens
        }

        val inputIds = LongArray(maxLength) { padId.toLong() }
        val attentionMask = LongArray(maxLength) { 0L }
        for (i in effective.indices) {
            inputIds[i] = effective[i].toLong()
            attentionMask[i] = 1L
        }
        return EncodedPrompt(inputIds = inputIds, attentionMask = attentionMask)
    }

    private fun encodeWord(word: String, out: MutableList<Int>) {
        val n = word.length
        if (n == 0) return

        val bestScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val bestLen = IntArray(n + 1)
        val bestId = IntArray(n + 1)
        bestScore[0] = 0.0

        for (i in 0 until n) {
            if (bestScore[i].isInfinite() && bestScore[i] < 0) continue

            val upper = minOf(maxPieceLen, n - i)
            var foundMatch = false
            for (len in 1..upper) {
                val sub = word.substring(i, i + len)
                val id = pieceToId[sub]
                if (id != null && id < pieceScores.size) {
                    foundMatch = true
                    val candidate = bestScore[i] + pieceScores[id]
                    if (candidate > bestScore[i + len]) {
                        bestScore[i + len] = candidate
                        bestLen[i + len] = len
                        bestId[i + len] = id
                    }
                }
            }

            if (!foundMatch || bestLen[i + 1] == 0) {
                val charCount = Character.charCount(word.codePointAt(i))
                val nextIdx = minOf(n, i + charCount)
                val candidate = bestScore[i] + unkScore
                if (candidate > bestScore[nextIdx]) {
                    bestScore[nextIdx] = candidate
                    bestLen[nextIdx] = nextIdx - i
                    bestId[nextIdx] = unkId
                }
            }
        }

        val tokens = ArrayList<Int>(n)
        var pos = n
        while (pos > 0) {
            val len = bestLen[pos]
            if (len <= 0) {
                pos--
                continue
            }
            tokens.add(bestId[pos])
            pos -= len
        }
        tokens.reverse()

        var prevWasUnk = false
        for (id in tokens) {
            if (id == unkId) {
                if (!prevWasUnk) {
                    out.add(id)
                    prevWasUnk = true
                }
            } else {
                prevWasUnk = false
                out.add(id)
            }
        }
    }

    companion object {
        const val DEFAULT_PAD_ID = 0
        const val DEFAULT_EOS_ID = 1
        const val DEFAULT_UNK_ID = 2
        const val FIRST_PIECE_ID = 3
        const val DEFAULT_MAX_LENGTH = 256
        private const val SPIECE_PREFIX = "\u2581"
        private val WHITESPACE_REGEX = Regex("\\s+")

        fun fromFile(vocabOrTokenizerFile: File): T5UnigramTokenizer {
            val content = vocabOrTokenizerFile.readText(Charsets.UTF_8)
            val trimmed = content.trimStart()
            return if (trimmed.startsWith("[")) {
                fromVocabJson(content)
            } else {
                fromTokenizerJson(content)
            }
        }

        fun fromVocabJson(rawJson: String): T5UnigramTokenizer {
            val array = JSONArray(rawJson)
            return buildFromPairs(array)
        }

        fun fromTokenizerJson(rawJson: String): T5UnigramTokenizer {
            val root = JSONObject(rawJson)
            val model = root.getJSONObject("model")
            val vocabAny = model.get("vocab")
            if (vocabAny is JSONArray) {
                return buildFromPairs(vocabAny, unkIdOverride = model.optInt("unk_id", DEFAULT_UNK_ID))
            }
            if (vocabAny is JSONObject) {
                val keys = vocabAny.keys()
                val entries = ArrayList<Pair<String, Int>>(vocabAny.length())
                while (keys.hasNext()) {
                    val key = keys.next()
                    entries.add(key to vocabAny.getInt(key))
                }
                val maxId = (entries.maxOfOrNull { it.second } ?: 2) + 1
                val scores = DoubleArray(maxId) { -1.0 }
                val map = HashMap<String, Int>(entries.size * 2)
                var maxLen = 1
                for ((piece, id) in entries) {
                    if (id >= FIRST_PIECE_ID && piece.isNotEmpty()) {
                        map[piece] = id
                        if (piece.length > maxLen) maxLen = piece.length
                    }
                }
                val pad = map["<pad>"] ?: DEFAULT_PAD_ID
                val eos = map["</s>"] ?: DEFAULT_EOS_ID
                val unk = map["<unk>"] ?: DEFAULT_UNK_ID
                return T5UnigramTokenizer(
                    pieceToId = map,
                    pieceScores = scores,
                    maxPieceLen = maxLen,
                    unkScore = -11.0,
                    padId = pad,
                    eosId = eos,
                    unkId = unk
                )
            }
            error("Unsupported tokenizer.json vocab structure")
        }

        private fun buildFromPairs(array: JSONArray, unkIdOverride: Int = DEFAULT_UNK_ID): T5UnigramTokenizer {
            val size = array.length()
            require(size > FIRST_PIECE_ID) { "Vocab must contain > $FIRST_PIECE_ID entries" }
            val pieceToId = HashMap<String, Int>(size * 2)
            val scores = DoubleArray(size)
            var minScore = 0.0
            var maxLen = 1
            var padId = DEFAULT_PAD_ID
            var eosId = DEFAULT_EOS_ID
            var unkId = unkIdOverride

            for (id in 0 until size) {
                val pair = array.getJSONArray(id)
                val piece = pair.getString(0)
                val score = pair.optDouble(1, 0.0)
                scores[id] = score
                when (piece) {
                    "<pad>" -> padId = id
                    "</s>" -> eosId = id
                    "<unk>" -> unkId = id
                }
                if (id >= FIRST_PIECE_ID && piece.isNotEmpty()) {
                    pieceToId[piece] = id
                    if (piece.length > maxLen) maxLen = piece.length
                    if (score < minScore) minScore = score
                }
            }

            return T5UnigramTokenizer(
                pieceToId = pieceToId,
                pieceScores = scores,
                maxPieceLen = maxLen,
                unkScore = minScore - 10.0,
                padId = padId,
                eosId = eosId,
                unkId = unkId
            )
        }
    }
}
