package com.shounak.localmeshai.rag

import java.util.Locale

internal object RetrievalRelevance {
    private fun words(text: String) = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    fun isConversationOnly(query: String): Boolean = words(query).joinToString(" ") in setOf(
        "hi", "hello", "hey", "hi there", "hello there", "hey there", "good morning", "good evening", "good afternoon",
        "thanks", "thank you", "thanks a lot", "ok", "okay", "who are you", "what are you", "what is your name",
        "what s your name", "how are you", "what can you do", "tell me about yourself",
        "what is my name", "what s my name", "tell me my name", "who am i"
    )

    // Hash-based embeddings can collide on unrelated words. Require actual content evidence as well.
    fun hasLexicalEvidence(query: String, content: String): Boolean {
        val terms = words(query).filter { it.length > 1 && it !in stopWords }.toSet()
        return words(content).any { it in terms }
    }

    fun isUnqualifiedOverview(query: String): Boolean = words(query).joinToString(" ") in setOf(
        "summarize", "summarise", "summary", "overview", "tldr", "tl dr", "outline",
        "give me a summary", "give me an overview", "show key points", "key points", "main points"
    )

    private val stopWords = setOf("the", "and", "for", "with", "this", "that", "what", "which", "when", "where",
        "who", "why", "how", "are", "was", "were", "does", "did", "can", "could", "would", "should", "will",
        "you", "your", "please", "tell", "about", "have", "has", "had", "from", "into", "under", "over",
        "is", "it", "its", "of", "to", "in", "on", "at", "an", "as", "be", "by", "do", "me", "my", "we", "us")
}
