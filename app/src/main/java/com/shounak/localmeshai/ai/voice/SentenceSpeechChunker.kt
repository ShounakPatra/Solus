package com.shounak.localmeshai.ai.voice

/**
 * Incrementally splits streaming assistant LLM output into clean spoken sentences for
 * low-latency TTS queuing in Hands-Free Voice Mode.
 *
 * Strips `<think>...</think>` blocks, fenced code blocks, Markdown formatting, and URLs,
 * and avoids splitting mid-decimal (`3.14`), ellipsis (`...`), or common abbreviations (`Dr.`).
 */
class SentenceSpeechChunker {
    private val rawAccumulated = StringBuilder()
    private var emittedCursor = 0

    fun reset() {
        rawAccumulated.setLength(0)
        emittedCursor = 0
    }

    /**
     * Replaces or appends the latest full streaming answer snapshot (matching [ChatViewModel]'s
     * cumulative `answerText` updates) and returns zero or more complete spoken sentences ready to queue.
     */
    fun updateSnapshot(fullAnswerText: String): List<String> {
        if (fullAnswerText.length >= rawAccumulated.length && fullAnswerText.startsWith(rawAccumulated)) {
            rawAccumulated.append(fullAnswerText, rawAccumulated.length, fullAnswerText.length)
        } else {
            rawAccumulated.setLength(0)
            rawAccumulated.append(fullAnswerText)
            emittedCursor = emittedCursor.coerceAtMost(rawAccumulated.length)
        }
        return extractReadySentences()
    }

    /**
     * Appends an incremental delta token and returns any completed spoken sentences.
     */
    fun appendDelta(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        rawAccumulated.append(delta)
        return extractReadySentences()
    }

    /**
     * Flushes any remaining partial tail once the LLM response finishes streaming.
     */
    fun flushRemainder(): String? {
        if (emittedCursor >= rawAccumulated.length) return null
        val tail = rawAccumulated.substring(emittedCursor)
        emittedCursor = rawAccumulated.length
        return sanitizeForSpeech(tail).takeIf { it.isNotBlank() }
    }

    private fun extractReadySentences(): List<String> {
        val text = rawAccumulated.toString()
        if (emittedCursor >= text.length) return emptyList()

        // Hold back while inside an unclosed <think> or fenced ``` code block
        val unclosedThinkStart = text.lastIndexOf("<think>", ignoreCase = true)
        val closedThinkEnd = text.lastIndexOf("</think>", ignoreCase = true)
        val effectiveEnd = if (unclosedThinkStart > closedThinkEnd) unclosedThinkStart else text.length
        if (effectiveEnd <= emittedCursor) return emptyList()

        val ready = ArrayList<String>(2)
        var scan = emittedCursor
        var insideFence = countFencesBefore(text, scan) % 2 == 1

        var i = scan
        while (i < effectiveEnd) {
            if (text.regionMatches(i, "<think>", 0, 7, ignoreCase = true)) {
                val closeIdx = text.indexOf("</think>", startIndex = i + 7, ignoreCase = true)
                if (closeIdx < 0) break
                val afterClose = closeIdx + 8
                if (scan == i) {
                    scan = afterClose
                    emittedCursor = scan
                }
                i = afterClose
                continue
            }
            if (text.startsWith("```", startIndex = i)) {
                insideFence = !insideFence
                i += 3
                continue
            }
            if (!insideFence) {
                val ch = text[i]
                if (ch == '\n' || ch == '.' || ch == '!' || ch == '?') {
                    if (isSentenceBoundary(text, i)) {
                        val candidateRaw = text.substring(scan, i + 1)
                        val cleaned = sanitizeForSpeech(candidateRaw)
                        scan = i + 1
                        emittedCursor = scan
                        if (cleaned.isNotBlank()) {
                            ready.add(cleaned)
                        }
                    }
                }
            }
            i++
        }
        return ready
    }

    private fun countFencesBefore(text: String, limit: Int): Int {
        var count = 0
        var idx = 0
        while (idx + 3 <= limit) {
            val found = text.indexOf("```", startIndex = idx)
            if (found < 0 || found >= limit) break
            count++
            idx = found + 3
        }
        return count
    }

    private fun isSentenceBoundary(text: String, idx: Int): Boolean {
        val ch = text[idx]
        if (ch == '\n') return true
        val next = text.getOrNull(idx + 1)
        val prev = text.getOrNull(idx - 1)
        if (ch == '.') {
            // Do not split inside ellipses ("...")
            if (prev == '.' || next == '.') return false
            // Do not split inside decimal numbers or version numbers ("3.14", "1.0")
            if (prev != null && prev.isDigit() && next != null && next.isDigit()) return false
            // Wait for trailing whitespace or closing punctuation before committing '.' as a boundary
            if (next == null) return false
            if (!next.isWhitespace() && next != '"' && next != '\'' && next != ')') return false
            val wordBefore = wordBeforeDot(text, idx).lowercase()
            if (wordBefore in ABBREVIATIONS) return false
            // Also don't split inside URLs like "example.com"
            if (prev != null && !next.isWhitespace() && next != '"' && next != '\'' && next != ')') return false
            return true
        }
        return next != null && (next.isWhitespace() || next == '"' || next == '\'' || next == ')')
    }

    private fun wordBeforeDot(text: String, dotIdx: Int): String {
        var start = dotIdx - 1
        while (start >= 0 && text[start].isLetter()) start--
        return text.substring(start + 1, dotIdx)
    }

    companion object {
        private val ABBREVIATIONS = setOf("dr", "mr", "mrs", "ms", "prof", "sr", "jr", "vs", "etc", "eg", "ie", "e.g", "i.e")

        fun sanitizeForSpeech(raw: String): String {
            var t = raw
            t = t.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), " ")
            t = t.replace(Regex("""```[\s\S]*?```"""), " Code block omitted. ")
            t = t.replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
            t = t.replace(Regex("""https?://\S+"""), "")
            t = t.replace(Regex("""`([^`]+)`"""), "$1")
            t = t.replace(Regex("""\$\$[\s\S]*?\$\$"""), " ")
            t = t.replace(Regex("""\$([^$]+)\$"""), "$1")
            t = t.replace(Regex("""[#*_~>]+"""), " ")
            t = t.replace(Regex("""\s+"""), " ").trim()
            return t
        }
    }
}
