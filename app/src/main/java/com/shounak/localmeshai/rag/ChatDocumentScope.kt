package com.shounak.localmeshai.rag

/** Upload identity, never a filename or a match from the shared knowledge base. */
internal object ChatDocumentScope {
    fun ids(uploads: List<Pair<Boolean, String?>>): Set<String> =
        uploads.filter { it.first }.mapNotNull { it.second?.takeIf(String::isNotBlank) }.toSet()

    fun isDocumentQuestion(query: String, names: List<String> = emptyList()): Boolean {
        if (RetrievalRelevance.isConversationOnly(query)) return false
        val lower = query.lowercase(java.util.Locale.ROOT)
        if (lower.trim().trimEnd('.', '?', '!') in setOf("describe this", "what does this say", "what does it say",
                "tell me about this", "explain this", "explain it", "summarize it", "summarise it", "summarize this", "summarise this")) return true
        return Regex("\\b(document|documents|file|files|pdf|uploaded|attached|passage|section)\\b").containsMatchIn(lower) ||
            RetrievalRelevance.isUnqualifiedOverview(query) || names.any { name ->
                val stem = name.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
                lower.contains(name.lowercase(java.util.Locale.ROOT)) || stem.length >= 4 && lower.contains(stem)
            }
    }

    fun shouldIncludeText(query: String, name: String, text: String): Boolean =
        !RetrievalRelevance.isConversationOnly(query) &&
            (isDocumentQuestion(query, listOf(name)) || RetrievalRelevance.hasLexicalEvidence(query, text))
}
