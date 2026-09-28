package com.shounak.localmeshai.rag

import android.content.Context
import android.net.Uri
import com.shounak.localmeshai.rag.chunking.TextChunker
import com.shounak.localmeshai.rag.embedding.SemanticEmbeddingEngine
import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.embedding.VectorEmbedding
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.rag.store.RagChunkRecord
import com.shounak.localmeshai.rag.store.RagDocumentSummary
import com.shounak.localmeshai.rag.store.RagSearchResult
import com.shounak.localmeshai.rag.store.VectorStore
import com.shounak.localmeshai.utils.DocumentTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

data class RagIngestionResult(
    val success: Boolean,
    val documentId: String,
    val documentName: String,
    val chunkCount: Int,
    val totalCharacters: Int,
    val message: String
)

data class RagRetrievalResult(
    val query: String,
    val matches: List<RagSearchResult>,
    val hasContext: Boolean
) {
    val topMatchScore: Float
        get() = matches.firstOrNull()?.similarity ?: 0f

    val sourceNames: List<String>
        get() = matches.map { it.chunk.documentName }.distinct()
}

/**
 * Primary orchestrator for the on-device RAG subsystem.
 *
 * Completely decoupled from LLM inference engines (such as llama.cpp),
 * enabling document indexing and semantic retrieval for any AI runtime.
 */
class RagManager(
    private val context: Context? = null,
    val embeddingEngine: SemanticEmbeddingEngine = UniversalSemanticEmbeddingEngine(),
    val vectorStore: VectorStore = InMemoryRagVectorStore(context),
    val chunker: TextChunker = TextChunker()
) {

    val totalIndexedChunks: Int
        get() = vectorStore.getTotalChunkCount()

    val indexedDocuments: List<RagDocumentSummary>
        get() = vectorStore.getDocuments()

    /**
     * Ingests a document from an Android content [uri] using [DocumentTextExtractor].
     */
    suspend fun ingestDocument(
        uri: Uri,
        fileName: String,
        mimeType: String = ""
    ): RagIngestionResult = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext RagIngestionResult(
            success = false,
            documentId = "",
            documentName = fileName,
            chunkCount = 0,
            totalCharacters = 0,
            message = "Android Context is required for Uri extraction"
        )

        try {
            val extractedText = DocumentTextExtractor.extract(
                context = ctx,
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                maxExtractedChars = 200_000,
                limitDescription = "200,000 characters for RAG knowledge base",
                maxInputBytes = 20L * 1024L * 1024L
            )

            val isExtractionError = extractedText.isBlank() ||
                extractedText.startsWith("No readable text could be extracted", ignoreCase = true) ||
                extractedText.startsWith("Could not ", ignoreCase = true) ||
                extractedText.startsWith("The attached file is ", ignoreCase = true) ||
                extractedText.contains("OCR is required", ignoreCase = true)

            if (isExtractionError) {
                val errorMsg = if (extractedText.isBlank()) {
                    "No readable text could be extracted from $fileName"
                } else {
                    extractedText.take(160)
                }
                return@withContext RagIngestionResult(
                    success = false,
                    documentId = "",
                    documentName = fileName,
                    chunkCount = 0,
                    totalCharacters = 0,
                    message = errorMsg
                )
            }

            ingestTextInternal(documentName = fileName, content = extractedText)
        } catch (e: Throwable) {
            RagIngestionResult(
                success = false,
                documentId = "",
                documentName = fileName,
                chunkCount = 0,
                totalCharacters = 0,
                message = "Ingestion failed: ${e.message}"
            )
        }
    }

    /**
     * Ingests raw text into the RAG vector store under [documentName].
     */
    suspend fun ingestText(
        documentName: String,
        content: String
    ): RagIngestionResult = withContext(Dispatchers.Default) {
        ingestTextInternal(documentName, content)
    }

    private suspend fun ingestTextInternal(
        documentName: String,
        content: String
    ): RagIngestionResult {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return RagIngestionResult(
                success = false,
                documentId = "",
                documentName = documentName,
                chunkCount = 0,
                totalCharacters = 0,
                message = "Document text is empty"
            )
        }

        // Remove any prior version of the document with the same name to prevent duplicates
        val existingDocs = vectorStore.getDocuments().filter { it.documentName.equals(documentName, ignoreCase = true) }
        for (doc in existingDocs) {
            vectorStore.removeDocument(doc.documentId)
        }

        val docId = UUID.randomUUID().toString()
        val textChunks = chunker.chunk(trimmed)
        if (textChunks.isEmpty()) {
            return RagIngestionResult(
                success = false,
                documentId = docId,
                documentName = documentName,
                chunkCount = 0,
                totalCharacters = 0,
                message = "No chunks could be produced from document"
            )
        }

        val docContextPrefix = documentName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
        val textsToEmbed = textChunks.map { chunk ->
            if (docContextPrefix.isNotBlank()) "$docContextPrefix: ${chunk.text}" else chunk.text
        }
        val embeddings = embeddingEngine.embedBatch(textsToEmbed)

        val records = textChunks.mapIndexed { idx, chunk ->
            RagChunkRecord(
                id = chunk.id,
                documentId = docId,
                documentName = documentName,
                chunkIndex = chunk.index,
                text = chunk.text,
                embedding = embeddings[idx],
                timestamp = System.currentTimeMillis()
            )
        }

        vectorStore.addChunks(records)

        return RagIngestionResult(
            success = true,
            documentId = docId,
            documentName = documentName,
            chunkCount = records.size,
            totalCharacters = trimmed.length,
            message = "Successfully indexed ${records.size} chunks from $documentName"
        )
    }

    /**
     * Checks if a user query represents a summary, overview, or general document inquiry.
     */
    fun isOverviewOrSummaryQuery(query: String): Boolean {
        val lower = query.lowercase(Locale.US).trim()
        if (lower.isEmpty()) return true
        val summaryPatterns = listOf(
            "summarize", "summary", "overview", "tldr", "tl;dr", "outline",
            "what is this", "what is the document", "what is this document", "what is this file",
            "what does this document say", "what does this file say", "what is it about", "what does it say",
            "explain this document", "explain this file", "explain everything", "explain all", "explain this",
            "tell me about this", "tell me about the document",
            "key points", "main points", "key takeaways", "brief me", "describe this document", "analyze this document"
        )
        return summaryPatterns.any { lower.contains(it) }
    }

    /**
     * Checks if a user query explicitly refers to an attached or referenced document.
     */
    fun isExplicitDocumentQuery(query: String): Boolean {
        val lower = query.lowercase(Locale.US).trim()
        val explicitPatterns = listOf(
            "this document", "the document", "this file", "the file", "attached document",
            "attached file", "this pdf", "the pdf", "what does this say", "what does the doc say",
            "what did i upload", "uploaded file", "uploaded doc", "uploaded document", "the uploaded file",
            "the uploaded document", "in the file", "in the doc", "in the pdf", "from the file",
            "from the doc", "from the pdf", "from the document", "according to the doc",
            "according to the file", "according to the pdf"
        )
        return explicitPatterns.any { lower.contains(it) }
    }

    /**
     * Tries to find an indexed document whose name or keywords match the user's query.
     */
    fun findMatchingDocumentForQuery(query: String): RagDocumentSummary? {
        val lower = query.lowercase(Locale.US).trim()
        val docs = indexedDocuments
        if (docs.isEmpty() || lower.isEmpty()) return null

        val ignoredTokens = setOf(
            "document", "documents", "file", "files", "text", "page", "data", "upload", "uploaded"
        )

        // 1. Direct match on full file name or stem (with or without extension, dashes/underscores as spaces)
        for (doc in docs) {
            val fullName = doc.documentName.lowercase(Locale.US)
            val stem = fullName.substringBeforeLast('.').trim()
            val stemSpaced = stem.replace('_', ' ').replace('-', ' ').trim()

            if (fullName.length >= 3 && lower.contains(fullName)) return doc
            if (stem.length >= 3 && lower.contains(stem)) return doc
            if (stemSpaced.length >= 3 && lower.contains(stemSpaced)) return doc
        }

        // 2. Keyword token match (tokens with length >= 4)
        var bestDoc: RagDocumentSummary? = null
        var maxMatchedWords = 0
        for (doc in docs) {
            val fullName = doc.documentName.lowercase(Locale.US)
            val stem = fullName.substringBeforeLast('.')
            val tokens = stem.split('_', '-', ' ', '.')
                .map { it.trim() }
                .filter { it.length >= 4 && it !in ignoredTokens }
            val matchedWords = tokens.count { lower.contains(it) }
            if (matchedWords > maxMatchedWords) {
                maxMatchedWords = matchedWords
                bestDoc = doc
            }
        }
        if (maxMatchedWords > 0) {
            return bestDoc
        }

        // 3. Fallback: If only 1 document is indexed and query is explicit or summary
        if (docs.size == 1 && (isExplicitDocumentQuery(lower) || isOverviewOrSummaryQuery(lower))) {
            return docs.first()
        }

        return null
    }

    /**
     * Determines whether the user query appears to ask about an uploaded document.
     */
    fun isQueryRelatedToDocument(query: String): Boolean {
        if (totalIndexedChunks == 0) return false
        val lower = query.lowercase(Locale.US).trim()
        if (lower.isEmpty()) return false

        if (isExplicitDocumentQuery(lower) || isOverviewOrSummaryQuery(lower)) return true
        if (findMatchingDocumentForQuery(lower) != null) return true

        val uploadKeywords = listOf(
            "uploaded", "attached", "in the file", "in the doc", "in the pdf",
            "from the file", "from the doc", "from the pdf", "from the document",
            "according to the", "what did i upload", "what does the text say",
            "what does it say", "in section", "on page"
        )
        return uploadKeywords.any { lower.contains(it) }
    }

    /**
     * Performs semantic vector search for [query] and returns matching chunks.
     */
    suspend fun retrieve(
        query: String,
        topK: Int = 3,
        minScore: Float = 0.35f,
        preferredDocumentId: String? = null
    ): RagRetrievalResult = withContext(Dispatchers.Default) {
        val trimmedQuery = query.trim()
        if (vectorStore.getTotalChunkCount() == 0) {
            return@withContext RagRetrievalResult(
                query = trimmedQuery,
                matches = emptyList(),
                hasContext = false
            )
        }

        val targetDoc = if (preferredDocumentId != null) {
            indexedDocuments.firstOrNull { it.documentId == preferredDocumentId }
        } else {
            findMatchingDocumentForQuery(trimmedQuery)
        }
        val effectiveDocId = preferredDocumentId ?: targetDoc?.documentId

        // 1. Check if user is asking for an overview or summary:
        // - If effectiveDocId != null: user attached/referenced a document and wants overview/summary
        // - If effectiveDocId == null: user explicitly asked about "the document" / "the file"
        if (effectiveDocId != null && isOverviewOrSummaryQuery(trimmedQuery)) {
            val overviewChunks = vectorStore.getChunksForDocument(effectiveDocId, topK)
            if (overviewChunks.isNotEmpty()) {
                val matches = overviewChunks.mapIndexed { i, chunk ->
                    RagSearchResult(chunk, 0.85f - (i * 0.05f))
                }
                return@withContext RagRetrievalResult(
                    query = trimmedQuery,
                    matches = matches,
                    hasContext = true
                )
            }
        } else if (effectiveDocId == null && (isExplicitDocumentQuery(trimmedQuery) || isOverviewOrSummaryQuery(trimmedQuery))) {
            val overviewChunks = vectorStore.getRecentChunks(topK)
            if (overviewChunks.isNotEmpty()) {
                val matches = overviewChunks.mapIndexed { i, chunk ->
                    RagSearchResult(chunk, 0.85f - (i * 0.05f))
                }
                return@withContext RagRetrievalResult(
                    query = trimmedQuery,
                    matches = matches,
                    hasContext = true
                )
            }
        }

        // 2. Perform semantic vector similarity search
        val queryEmb = if (trimmedQuery.isNotEmpty()) embeddingEngine.embed(trimmedQuery) else FloatArray(0)
        if (queryEmb.isEmpty()) {
            return@withContext RagRetrievalResult(
                query = trimmedQuery,
                matches = emptyList(),
                hasContext = false
            )
        }

        // Also generate semantic query embedding without document name noise if targetDoc was matched
        val cleanedQueryEmb = if (targetDoc != null) {
            val stem = targetDoc.documentName.substringBeforeLast('.')
            val stemSpaced = stem.replace('_', ' ').replace('-', ' ')
            val cleanedQuery = trimmedQuery
                .replace(targetDoc.documentName, "", ignoreCase = true)
                .replace(stem, "", ignoreCase = true)
                .replace(stemSpaced, "", ignoreCase = true)
                .trim()
            if (cleanedQuery.isNotBlank() && !cleanedQuery.equals(trimmedQuery, ignoreCase = true)) {
                embeddingEngine.embed(cleanedQuery)
            } else null
        } else null

        // If a specific document is attached or resolved from query, prioritize with adaptive tolerance
        val filteredMatches = if (effectiveDocId != null) {
            val directDocThreshold = maxOf(0.20f, minScore * 0.60f)
            val directDocMatches = vectorStore.getChunksForDocument(effectiveDocId, 100)
                .map { chunk ->
                    val sim1 = VectorEmbedding.cosineSimilarity(queryEmb, chunk.embedding)
                    val sim2 = if (cleanedQueryEmb != null) VectorEmbedding.cosineSimilarity(cleanedQueryEmb, chunk.embedding) else 0f
                    RagSearchResult(chunk, maxOf(sim1, sim2))
                }
                .filter { it.similarity >= directDocThreshold }
                .sortedByDescending { it.similarity }
                .take(topK)

            if (directDocMatches.isNotEmpty()) {
                directDocMatches
            } else {
                // Fallback to searching all indexed chunks if direct document match was empty
                vectorStore.search(queryEmb, topK = topK, minSimilarity = minScore)
            }
        } else {
            // General semantic vector search across all indexed chunks in the knowledge base
            vectorStore.search(queryEmb, topK = topK, minSimilarity = minScore)
        }

        RagRetrievalResult(
            query = trimmedQuery,
            matches = filteredMatches,
            hasContext = filteredMatches.isNotEmpty()
        )
    }

    /**
     * Formats an augmented prompt embedding the retrieved RAG context.
     */
    fun buildAugmentedPrompt(
        rawUserQuery: String,
        retrieval: RagRetrievalResult
    ): String {
        if (!retrieval.hasContext || retrieval.matches.isEmpty()) {
            return rawUserQuery
        }

        return buildString {
            append("[Relevant Document Context]\n")
            retrieval.matches.forEachIndexed { i, match ->
                val scorePct = (match.similarity * 100f).toInt()
                append("--- Document: \"${match.chunk.documentName}\" (Section #${match.chunk.chunkIndex + 1}, Relevance: $scorePct%) ---\n")
                append(match.chunk.text.trim())
                append("\n\n")
            }
            append("[End of Document Context]\n\n")
            append("Instruction: Use the document context above to answer the user's question accurately. ")
            append("If the context does not contain the answer, respond based on your knowledge while noting that the document does not mention it.\n\n")
            append(rawUserQuery.trim())
        }
    }

    /**
     * Removes all chunks belonging to [documentId].
     */
    fun removeDocument(documentId: String): Boolean {
        return vectorStore.removeDocument(documentId)
    }

    /**
     * Wipes all indexed documents and vector embeddings.
     */
    fun clearKnowledgeBase() {
        vectorStore.clearAll()
    }

    companion object {
        @Volatile
        private var instance: RagManager? = null

        fun getInstance(context: Context): RagManager {
            return instance ?: synchronized(this) {
                instance ?: RagManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
