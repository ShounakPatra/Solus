package com.shounak.localmeshai.rag

/** Upload identity, never a filename or a match from the shared knowledge base. */
internal object ChatDocumentScope {
    private val documentReference = Regex("\\b(?:(?:this|that|the|my|our|uploaded|attached)\\s+(?:uploaded\\s+|attached\\s+)?(?:documents?|files?|pdfs?|passages?)|(?:in|from|according to)\\s+(?:the\\s+|this\\s+|my\\s+)?(?:document|file|pdf|passage)|(?:summari[sz]e|describe|explain|analy[sz]e|review|outline)\\s+(?:the\\s+|this\\s+|my\\s+)?(?:documents?|files?|pdfs?)|uploaded|attached)\\b")

    fun referencedNames(query: String, names: List<String>): Set<String> = names.filter { name ->
        if (name.isBlank()) false else {
            val stem = name.substringBeforeLast('.')
            listOf(name).plus(if (stem.length >= 4) listOf(stem) else emptyList()).any {
                Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).containsMatchIn(query)
            }
        }
    }.toSet()
    fun ids(uploads: List<Pair<Boolean, String?>>): Set<String> =
        uploads.filter { it.first }.mapNotNull { it.second?.takeIf(String::isNotBlank) }.toSet()

    fun isDocumentQuestion(query: String, names: List<String> = emptyList()): Boolean {
        if (RetrievalRelevance.isConversationOnly(query)) return false
        val lower = query.lowercase(java.util.Locale.ROOT)
        if (lower.trim().trimEnd('.', '?', '!') in setOf("describe this", "what does this say", "what does it say",
                "tell me about this", "explain this", "explain it", "summarize it", "summarise it", "summarize this", "summarise this")) return true
        return documentReference.containsMatchIn(lower) || RetrievalRelevance.isUnqualifiedOverview(query) ||
            referencedNames(query, names).isNotEmpty()
    }

    fun shouldIncludeText(query: String, name: String, text: String): Boolean =
        !RetrievalRelevance.isConversationOnly(query) &&
            (isDocumentQuestion(query, listOf(name)) || RetrievalRelevance.hasLexicalEvidence(query, text))
}
