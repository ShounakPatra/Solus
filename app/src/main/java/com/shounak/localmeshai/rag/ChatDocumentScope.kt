package com.shounak.localmeshai.rag

/** Upload identity, never a filename or a match from the shared knowledge base. */
internal object ChatDocumentScope {
    private val documentReference = Regex("\\b(?:(?:this|that|the|my|our|uploaded|attached)\\s+(?:uploaded\\s+|attached\\s+)?(?:documents?|files?|pdfs?|passages?)|(?:in|from|according to)\\s+(?:the\\s+|this\\s+|my\\s+)?(?:document|file|pdf|passage)|(?:summari[sz]e|describe|explain|analy[sz]e|review|outline)\\s+(?:the\\s+|this\\s+|my\\s+)?(?:documents?|files?|pdfs?)|uploaded|attached)\\b")

    /** "What does the termination clause say?": a named part of a document, with or without a number. */
    private val documentPart = Regex("\\b(?:the|this|that|its|their)\\s+(?:[\\p{L}\\p{N}'-]+\\s+){0,3}?(?:clauses?|sections?|subsections?|chapters?|paragraphs?|appendix|appendices|annex(?:es)?|exhibits?|provisions?|footnotes?|articles?)\\b")

    /** Kinds of documents people upload and then refer to by kind: "what does the contract say?". */
    private val documentKind = Regex("\\b(?:the|this|that|my|our)\\s+(?:contract|agreement|lease|invoice|report|paper|essay|resume|résumé|cv|spreadsheet|slides|slide deck|transcript|handbook|manual|brochure|syllabus|thesis)s?\\b")

    /** Nouns that, after a stem, make it read as a file: "the contract file", "my budget spreadsheet". */
    private const val FILE_NOUNS = "files?|documents?|docs?|pdfs?|uploads?|attachments?|spreadsheets?|sheets?|slides?|decks?"

    private val genericStems = setOf("summary", "report", "notes", "document", "file", "manual", "overview", "outline", "readme", "text")

    private fun bounded(term: String) =
        Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)

    /** A stem with separators, digits or inner capitals ("Q3_Report", "TaxInvoice2025") is not a word in a sentence. */
    private fun isDistinctiveStem(stem: String): Boolean =
        stem.any { it == '_' || it == '-' || it.isDigit() } ||
            stem.drop(1).any { it.isUpperCase() } && stem.any { it.isLowerCase() }

    /**
     * Uploads the question names explicitly, which narrows a scoped search to just those files.
     *
     * Narrowing drops every other upload in the chat, so it needs a filename-like mention: the
     * full name with its extension, a distinctive stem, the stem in quotes, or the stem used as
     * a file ("the contract file"). A bare common word such as "contract" or "budget" in a
     * question does not narrow; [isDocumentQuestion] still treats it as a document hint.
     */
    fun referencedNames(query: String, names: List<String>): Set<String> = names.filter { name ->
        if (name.isBlank()) false else {
            val stem = name.substringBeforeLast('.').trim()
            // A name without an extension ("contract") is itself a bare word.
            val hasExtension = stem.isNotEmpty() && stem.length < name.trim().length
            val usableStem = stem.length >= 4 && stem.lowercase(java.util.Locale.ROOT) !in genericStems
            (hasExtension || isDistinctiveStem(name)) && bounded(name).containsMatchIn(query) || usableStem && (
                isDistinctiveStem(stem) && bounded(stem).containsMatchIn(query) ||
                    Regex("[\"'“‘`]\\s*${Regex.escape(stem)}\\s*[\"'”’`]", RegexOption.IGNORE_CASE).containsMatchIn(query) ||
                    Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(stem)}\\s+(?:$FILE_NOUNS)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)
                )
        }
    }.toSet()

    /** Loose mention of an upload's name or stem: evidence of a document question, never a filter. */
    private fun mentionedNames(query: String, names: List<String>): Boolean = names.any { name ->
        if (name.isBlank()) false else {
            val stem = name.substringBeforeLast('.')
            val genericStem = stem.lowercase(java.util.Locale.ROOT) in genericStems
            listOf(name).plus(if (stem.length >= 4 && !genericStem) listOf(stem) else emptyList()).any {
                bounded(it).containsMatchIn(query)
            }
        }
    }

    fun ids(uploads: List<Pair<Boolean, String?>>): Set<String> =
        uploads.filter { it.first }.mapNotNull { it.second?.takeIf(String::isNotBlank) }.toSet()

    fun isDocumentQuestion(query: String, names: List<String> = emptyList()): Boolean {
        if (RetrievalRelevance.isConversationOnly(query)) return false
        val lower = query.lowercase(java.util.Locale.ROOT)
        if (lower.trim().trimEnd('.', '?', '!') in setOf("describe this", "what does this say", "what does it say",
                "tell me about this", "explain this", "explain it", "summarize it", "summarise it", "summarize this", "summarise this")) return true
        return documentReference.containsMatchIn(lower) ||
            Regex("\\b(?:section|chapter|page|paragraph|clause|table|figure)\\s+\\d+\\b").containsMatchIn(lower) ||
            documentPart.containsMatchIn(lower) ||
            documentKind.containsMatchIn(lower) ||
            RetrievalRelevance.isUnqualifiedOverview(query) ||
            mentionedNames(query, names)
    }

    fun shouldIncludeText(query: String, name: String, text: String): Boolean =
        !RetrievalRelevance.isConversationOnly(query) &&
            (isDocumentQuestion(query, listOf(name)) || RetrievalRelevance.hasLexicalEvidence(query, text))
}
