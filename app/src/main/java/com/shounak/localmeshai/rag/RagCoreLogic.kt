package com.shounak.localmeshai.rag

import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

// ==========================================
// 1. Text Chunking
// ==========================================

fun findBreakPoint(text: String, start: Int, end: Int, chunkSize: Int): Int {
    if (chunkSize <= 0) return end
    if (start >= end) return end
    val windowStart = (end - (chunkSize * 0.35f).toInt()).coerceAtLeast(start + 10)
    if (windowStart >= end) return end
    val slice = text.substring(windowStart, end)

    // 1. Paragraph break
    val paraIdx = slice.lastIndexOf("\n\n")
    if (paraIdx != -1) {
        return windowStart + paraIdx + 2
    }

    // 2. Line break
    val lineIdx = slice.lastIndexOf('\n')
    if (lineIdx != -1) {
        return windowStart + lineIdx + 1
    }

    // 3. Sentence break
    val sentenceDelims = arrayOf(". ", "? ", "! ")
    for (pattern in sentenceDelims) {
        val sentIdx = slice.lastIndexOf(pattern)
        if (sentIdx != -1) {
            return windowStart + sentIdx + pattern.length
        }
    }

    // 4. Clause break
    val clauseDelims = arrayOf("; ", ": ", ", ")
    for (delimiter in clauseDelims) {
        val clauseIdx = slice.lastIndexOf(delimiter)
        if (clauseIdx != -1) {
            return windowStart + clauseIdx + delimiter.length
        }
    }

    // 5. Word boundary
    val spaceIdx = slice.lastIndexOf(' ')
    if (spaceIdx != -1) {
        return windowStart + spaceIdx + 1
    }

    // Hard boundary fallback
    return end
}

fun chunkText(documentText: String, chunkSize: Int, chunkOverlap: Int): List<String> {
    require(chunkSize > 0) { "chunkSize must be positive" }
    require(chunkOverlap >= 0 && chunkOverlap < chunkSize) { "chunkOverlap must be non-negative and smaller than chunkSize" }
    val trimmed = documentText.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.length <= chunkSize) {
        return listOf(trimmed)
    }

    val chunks = mutableListOf<String>()
    var start = 0

    while (start < trimmed.length) {
        val potentialEnd = minOf(start + chunkSize, trimmed.length)

        if (potentialEnd == trimmed.length) {
            val chunk = trimmed.substring(start, potentialEnd).trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }
            break
        }

        val breakPoint = findBreakPoint(trimmed, start, potentialEnd, chunkSize)
        val chunk = trimmed.substring(start, breakPoint).trim()
        if (chunk.isNotEmpty()) {
            chunks.add(chunk)
        }

        if (breakPoint <= start) {
            start = potentialEnd
        } else {
            val nextStart = breakPoint - chunkOverlap
            start = if (nextStart > start) nextStart else breakPoint
        }
        if (start >= trimmed.length) break
    }

    return chunks
}

fun chunkBoundaries(documentText: String, chunkSize: Int, chunkOverlap: Int): IntArray {
    require(chunkSize > 0) { "chunkSize must be positive" }
    require(chunkOverlap >= 0 && chunkOverlap < chunkSize) { "chunkOverlap must be non-negative and smaller than chunkSize" }
    val trimmed = documentText.trim()
    if (trimmed.isEmpty()) return IntArray(0)
    if (trimmed.length <= chunkSize) {
        return intArrayOf(0, trimmed.length)
    }

    val list = mutableListOf<Int>()
    var start = 0

    while (start < trimmed.length) {
        val potentialEnd = minOf(start + chunkSize, trimmed.length)

        if (potentialEnd == trimmed.length) {
            val chunk = trimmed.substring(start, potentialEnd).trim()
            if (chunk.isNotEmpty()) {
                list.add(start)
                list.add(potentialEnd)
            }
            break
        }

        val breakPoint = findBreakPoint(trimmed, start, potentialEnd, chunkSize)
        val chunk = trimmed.substring(start, breakPoint).trim()
        if (chunk.isNotEmpty()) {
            list.add(start)
            list.add(breakPoint)
        }

        if (breakPoint <= start) {
            start = potentialEnd
        } else {
            val nextStart = breakPoint - chunkOverlap
            start = if (nextStart > start) nextStart else breakPoint
        }
        if (start >= trimmed.length) break
    }

    val result = IntArray(list.size)
    for (i in list.indices) {
        result[i] = list[i]
    }
    return result
}

// ==========================================
// 2. Vector Embedding Math
// ==========================================

fun dotProduct(a: DoubleArray, b: DoubleArray): Double {
    val len = minOf(a.size, b.size)
    var sum = 0.0
    for (i in 0 until len) {
        sum += a[i] * b[i]
    }
    return sum
}

fun magnitude(v: DoubleArray): Double {
    var sum = 0.0
    for (i in v.indices) {
        sum += v[i] * v[i]
    }
    return sqrt(sum)
}

fun l2Normalize(v: DoubleArray): DoubleArray {
    val mag = magnitude(v)
    if (mag <= 1e-9) {
        return DoubleArray(v.size)
    }
    val invMag = 1.0 / mag
    val out = DoubleArray(v.size)
    for (i in v.indices) {
        out[i] = v[i] * invMag
    }
    return out
}

fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val dot = dotProduct(a, b)
    val magA = magnitude(a)
    val magB = magnitude(b)
    val denom = magA * magB
    if (denom <= 1e-9) return 0.0
    val sim = dot / denom
    return sim.coerceIn(-1.0, 1.0)
}

// ==========================================
// 3. Universal Semantic Embedding
// ==========================================

private const val SEED_1 = -3750763034362895579L
private const val SEED_2 = 0x100000001b3L
private const val SEED_3 = -7046029254386353131L
private const val FNV_PRIME = 0x100000001b3L

fun fnv1a64(text: String, seed: Long): Long {
    var hash = seed
    for (i in 0 until text.length) {
        hash = hash xor text[i].code.toLong()
        hash *= FNV_PRIME
    }
    return hash
}

private val EMBED_STOP_WORDS = setOf(
    "a", "about", "above", "after", "again", "against", "all", "am", "an", "and",
    "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being",
    "below", "between", "both", "but", "by", "can", "can't", "cannot", "could",
    "couldn't", "did", "didn't", "do", "does", "doesn't", "doing", "don't", "down",
    "during", "each", "few", "for", "from", "further", "had", "hadn't", "has",
    "hasn't", "have", "haven't", "having", "he", "he'd", "he'll", "he's", "her",
    "here", "here's", "hers", "herself", "him", "himself", "his", "how", "how's",
    "i", "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is", "isn't", "it",
    "it's", "its", "itself", "let's", "me", "more", "most", "mustn't", "my",
    "myself", "no", "nor", "not", "of", "off", "on", "once", "only", "or", "other",
    "ought", "our", "ours", "ourselves", "out", "over", "own", "same", "shan't",
    "she", "she'd", "she'll", "she's", "should", "shouldn't", "so", "some", "such",
    "than", "that", "that's", "the", "their", "theirs", "them", "themselves",
    "then", "there", "there's", "these", "they", "they'd", "they'll", "they're",
    "they've", "this", "those", "through", "to", "too", "under", "until", "up",
    "very", "was", "wasn't", "we", "we'd", "we'll", "we're", "we've", "were",
    "weren't", "what", "what's", "when", "when's", "where", "where's", "which",
    "while", "who", "who's", "whom", "why", "why's", "with", "won't", "would",
    "wouldn't", "you", "you'd", "you'll", "you're", "you've", "your", "yours",
    "yourself", "yourselves"
)

private fun computeTokenWeight(token: String): Double {
    if (token in EMBED_STOP_WORDS) return 0.18
    if (token.all { it.isDigit() } || token.any { it.isDigit() }) return 2.2
    if (token.length <= 2) return 0.4
    if (token.length >= 8) return 1.4
    return 1.0
}

private fun projectToken(token: String, weight: Double, target: DoubleArray, dimension: Int) {
    val h1 = fnv1a64(token, SEED_1)
    val h2 = fnv1a64(token, SEED_2)
    val h3 = fnv1a64(token, SEED_3)

    val idx1 = ((h1 ushr 1) % dimension.toLong()).toInt()
    val sign1 = if ((h1 and 1L) == 0L) 1.0 else -1.0
    target[idx1] += sign1 * weight

    val idx2 = ((h2 ushr 1) % dimension.toLong()).toInt()
    val sign2 = if ((h2 and 1L) == 0L) 1.0 else -1.0
    target[idx2] += sign2 * (weight * 0.75)

    val idx3 = ((h3 ushr 1) % dimension.toLong()).toInt()
    val sign3 = if ((h3 and 1L) == 0L) 1.0 else -1.0
    target[idx3] += sign3 * (weight * 0.5)
}

fun embedUniversal(text: String, dimension: Int): DoubleArray {
    if (dimension <= 0) return DoubleArray(0)
    if (text.isBlank()) return DoubleArray(dimension)

    val vector = DoubleArray(dimension)
    val normalized = text.lowercase(Locale.US).trim()
    val rawTokens = mutableListOf<String>()
    val sb = StringBuilder()
    for (c in normalized) {
        if (c.isLetterOrDigit()) {
            sb.append(c)
        } else if (sb.isNotEmpty()) {
            rawTokens.add(sb.toString())
            sb.setLength(0)
        }
    }
    if (sb.isNotEmpty()) rawTokens.add(sb.toString())
    if (rawTokens.isEmpty()) return DoubleArray(dimension)

    for (i in rawTokens.indices) {
        val token = rawTokens[i]
        val idfWeight = computeTokenWeight(token)
        val posWeight = 1.0 + 0.3 * exp(-i.toDouble() / 15.0)
        val totalWeight = idfWeight * posWeight

        projectToken(token, totalWeight, vector, dimension)

        val subwordWeight = totalWeight * 0.4
        val bounded = "<$token>"
        val maxLen = minOf(5, bounded.length)
        for (n in 3..maxLen) {
            val limit = bounded.length - n
            for (start in 0..limit) {
                val gram = bounded.substring(start, start + n)
                projectToken(gram, subwordWeight, vector, dimension)
            }
        }
    }

    for (i in 0 until (rawTokens.size - 1)) {
        val bigram = "${rawTokens[i]}_${rawTokens[i + 1]}"
        val bigramWeight = (computeTokenWeight(rawTokens[i]) + computeTokenWeight(rawTokens[i + 1])) * 0.65
        projectToken(bigram, bigramWeight, vector, dimension)
    }

    return l2Normalize(vector)
}

// ==========================================
// 4. Retrieval Relevance
// ==========================================

private val RELEVANCE_WORDS_REGEX = Regex("[\\p{L}\\p{N}]+")

private fun extractWords(text: String): List<String> =
    RELEVANCE_WORDS_REGEX.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

private val CONVERSATION_ONLY_SET = setOf(
    "hi", "hello", "hey", "hi there", "hello there", "hey there", "good morning", "good evening", "good afternoon",
    "thanks", "thank you", "thanks a lot", "ok", "okay", "who are you", "what are you", "what is your name",
    "what s your name", "how are you", "what can you do", "tell me about yourself",
    "what is my name", "what s my name", "tell me my name", "who am i"
)

fun isConversationOnly(query: String): Boolean = try {
    RagCoreLogicNative.isConversationOnly(query)
} catch (_: Throwable) {
    extractWords(query).joinToString(" ") in CONVERSATION_ONLY_SET
}

private val OVERVIEW_SET = setOf(
    "summarize", "summarise", "summary", "overview", "tldr", "tl dr", "outline",
    "give me a summary", "give me an overview", "show key points", "key points", "main points"
)

fun isUnqualifiedOverview(query: String): Boolean = try {
    RagCoreLogicNative.isUnqualifiedOverview(query)
} catch (_: Throwable) {
    extractWords(query).joinToString(" ") in OVERVIEW_SET
}

private val RELEVANCE_STOP_WORDS = setOf(
    "the", "and", "for", "with", "this", "that", "what", "which", "when", "where",
    "who", "why", "how", "are", "was", "were", "does", "did", "can", "could", "would", "should", "will",
    "you", "your", "please", "tell", "about", "have", "has", "had", "from", "into", "under", "over",
    "is", "it", "its", "of", "to", "in", "on", "at", "an", "as", "be", "by", "do", "me", "my", "we", "us"
)

fun hasLexicalEvidence(query: String, content: String): Boolean = try {
    RagCoreLogicNative.hasLexicalEvidence(query, content)
} catch (_: Throwable) {
    val terms = extractWords(query).filter { it.length > 1 && it !in RELEVANCE_STOP_WORDS }.toSet()
    if (terms.isEmpty()) false else extractWords(content).any { it in terms }
}

fun scoreRelevance(query: String, content: String): Double {
    if (isConversationOnly(query)) return 0.0
    val queryWords = extractWords(query).filter { it.length > 1 && it !in RELEVANCE_STOP_WORDS }
    if (queryWords.isEmpty()) return 0.0
    val contentWords = extractWords(content).toSet()
    var matches = 0
    for (w in queryWords) {
        if (w in contentWords) matches++
    }
    return matches.toDouble() / queryWords.size.toDouble()
}

// ==========================================
// 5. Turn Document Planning Helpers
// ==========================================

fun planSavedUploadId(entryIds: List<String>, isUserList: List<Boolean>, paths: List<String>, turnMessageId: String?): String? {
    val n = minOf(entryIds.size, minOf(isUserList.size, paths.size))
    val turnIndex = if (turnMessageId != null) {
        var found = -1
        for (i in 0 until n) {
            if (entryIds[i] == turnMessageId) {
                found = i
                break
            }
        }
        found
    } else -1

    val limit = if (turnIndex >= 0) turnIndex else n

    var latestPath: String? = null
    var latestId: String? = null
    for (i in (limit - 1) downTo 0) {
        if (isUserList[i] && paths[i].isNotBlank()) {
            latestPath = paths[i]
            latestId = entryIds[i]
            break
        }
    }
    if (latestPath == null) return null

    // First user message carrying that path owns it
    for (i in 0 until n) {
        if (isUserList[i] && paths[i] == latestPath) {
            return entryIds[i]
        }
    }
    return latestId
}

fun planIsOriginalUpload(entryIds: List<String>, isUserList: List<Boolean>, paths: List<String>, messageId: String): Boolean {
    val n = minOf(entryIds.size, minOf(isUserList.size, paths.size))
    val seenPaths = mutableSetOf<String>()
    for (i in 0 until n) {
        if (isUserList[i] && paths[i].isNotBlank()) {
            if (seenPaths.add(paths[i])) {
                if (entryIds[i] == messageId) return true
            }
        }
    }
    return false
}

fun planLibraryDocumentId(entryIds: List<String>, isUserList: List<Boolean>, paths: List<String>, ragDocIds: List<String>, turnMessageId: String): String? {
    val n = minOf(entryIds.size, minOf(isUserList.size, minOf(paths.size, ragDocIds.size)))
    var turnIndex = -1
    for (i in 0 until n) {
        if (entryIds[i] == turnMessageId && isUserList[i]) {
            turnIndex = i
            break
        }
    }
    if (turnIndex < 0) return null
    val candidate = ragDocIds[turnIndex].trim()
    if (candidate.isEmpty()) return null

    // If turn is an upload itself (first user with this path), return null
    val seenPaths = mutableSetOf<String>()
    for (i in 0 until n) {
        if (isUserList[i] && paths[i].isNotBlank()) {
            if (seenPaths.add(paths[i])) {
                if (entryIds[i] == turnMessageId) return null
            }
        }
    }

    // If candidate belongs to any upload in this chat, return null
    seenPaths.clear()
    for (i in 0 until n) {
        if (isUserList[i] && paths[i].isNotBlank()) {
            if (seenPaths.add(paths[i])) {
                if (ragDocIds[i] == candidate) return null
            }
        }
    }

    return candidate
}
