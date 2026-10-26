package com.shounak.localmeshai.rag

import android.content.Context
import android.net.Uri
import com.shounak.localmeshai.rag.chunking.TextChunker
import com.shounak.localmeshai.rag.embedding.EmbeddingModels
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
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
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
    val hasContext: Boolean,
    val unavailableReason: String? = null
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
    embeddingEngine: SemanticEmbeddingEngine = UniversalSemanticEmbeddingEngine(),
    vectorStore: VectorStore = InMemoryRagVectorStore(context, expectedDimension = embeddingEngine.dimension),
    val chunker: TextChunker = TextChunker(),
    /** A store left behind by an embedding model that is no longer installed; migrated on [loadAsync]. */
    private var orphanedStore: VectorStore? = null,
    private val deletionJournal: com.shounak.localmeshai.data.ChatDeletionJournal? = context?.let {
        com.shounak.localmeshai.data.ChatSessionRepository.getInstance(it).deletionJournal
    }
) {
    // Independent ownership is intentional: JNI may ignore cancellation. The caller may
    // stop waiting, but the worker retains the index lock until native work unwinds.
    private val retrievalScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    suspend fun retrieveForChat(
        query: String, topK: Int = 3, minScore: Float = 0.35f,
        preferredDocumentId: String? = null, allowGlobalSearch: Boolean = true,
        timeoutMillis: Long = 10_000L,
        allowedDocumentIds: Set<String>? = null
    ): RagRetrievalResult {
        val task = retrievalScope.async {
            retrieve(query, topK, minScore, preferredDocumentId, allowGlobalSearch, allowedDocumentIds)
        }
        return try {
            val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { task.await() }
                ?: RagRetrievalResult(query, emptyList(), false, "Document search timed out; document contents are temporarily unavailable. Do not infer that no documents exist.")
            val removed = deletedDocumentIds()
            val visible = result.matches.filterNot { it.chunk.documentId in removed }
            if (visible.size == result.matches.size) result else result.copy(matches = visible, hasContext = visible.isNotEmpty(),
                unavailableReason = if (visible.isEmpty()) "The linked document was removed. Attach the intended document again." else result.unavailableReason)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RagRetrievalResult(query, emptyList(), false, "Document search is temporarily unavailable. Do not infer that no documents exist or invent their contents.")
        } finally { task.cancel() }
    }

    /** An embedder and the store holding its vectors. Swapped as one unit so readers never mix them. */
    private class ActiveIndex(val engine: SemanticEmbeddingEngine, val store: VectorStore)

    @Volatile
    private var active = ActiveIndex(embeddingEngine, vectorStore)

    /** Active embedder. Swapped only by [switchEmbeddingEngine], which re-embeds every stored chunk. */
    val embeddingEngine: SemanticEmbeddingEngine get() = active.engine

    /** Index paired with [embeddingEngine]; vectors from different engines never share a store. */
    val vectorStore: VectorStore get() = active.store

    private val _embeddingStatus = kotlinx.coroutines.flow.MutableStateFlow<EmbeddingModelStatus>(EmbeddingModelStatus.Idle)
    val embeddingStatus: kotlinx.coroutines.flow.StateFlow<EmbeddingModelStatus> = _embeddingStatus
    private val _embeddingFilesRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    val embeddingFilesRevision: kotlinx.coroutines.flow.StateFlow<Int> = _embeddingFilesRevision

    private val _loadState = kotlinx.coroutines.flow.MutableStateFlow(vectorStore.loadState.value)
    val loadState: kotlinx.coroutines.flow.StateFlow<com.shounak.localmeshai.data.StoreLoadState> = _loadState

    suspend fun loadAsync(): com.shounak.localmeshai.data.StoreLoadState = ingestionMutex.withLock {
        val state = withContext(Dispatchers.IO) {
            val loaded = if (vectorStore.loadState.value is com.shounak.localmeshai.data.StoreLoadState.Loading) {
                vectorStore.loadAsync()
            } else vectorStore.loadState.value
            if (loaded is com.shounak.localmeshai.data.StoreLoadState.Ready) {
                try {
                    purgeRetiredDocumentsLocked(deletedDocumentIds())
                    recoverOrphanedStore()
                    loaded
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _embeddingStatus.value = EmbeddingModelStatus.Failed(error.message ?: "Search index recovery failed")
                    com.shounak.localmeshai.data.StoreLoadState.Error(error)
                }
            } else loaded
        }
        _loadState.value = state
        context?.let { ctx ->
            com.shounak.localmeshai.data.StartupCoordinator.getInstance(ctx).updateRagStoreState(state)
        }
        state
    }

    private val ingestionMutex = Mutex()
    private val originals = context?.let { RagOriginalStore(java.io.File(it.filesDir, "rag_originals")) }

    fun originalDocumentFile(documentId: String): java.io.File? =
        if (documentId in deletedDocumentIds()) null else originals?.find(documentId)

    private fun saveOriginal(documentId: String, name: String, input: java.io.InputStream, checkActive: () -> Unit): java.io.File? =
        synchronized(mutationLock) {
            if (documentId in deletedDocumentIds() || vectorStore.getDocuments().none { it.documentId == documentId }) null
            else originals?.save(documentId, name, input, checkActive)
        }

    suspend fun retainOriginalDocument(documentId: String, name: String, file: java.io.File) = withContext(Dispatchers.IO) {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        file.inputStream().use { input -> saveOriginal(documentId, name, input) { job?.ensureActive() } }
    }
    private val mutationLock = Any()
    private var deletionVersion = 0L

    init {
        if (context != null && deletionJournal != null) retrievalScope.launch {
            deletionJournal.documentChanges.collect { ids ->
                try { ingestionMutex.withLock { withContext(Dispatchers.IO) { purgeRetiredDocumentsLocked(ids) } } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { _embeddingStatus.value = EmbeddingModelStatus.Failed(failure.message ?: "Document cleanup needs recovery") }
            }
        }
    }

    private fun deletedDocumentIds(): Set<String> = deletionJournal?.deletedRagDocuments().orEmpty()

    suspend fun retainForLibrary(documentId: String): Boolean = withContext(Dispatchers.IO) {
        deletionJournal?.retainRagDocument(documentId) ?: true
    }

    /** Called under ingestionMutex; durable identities reject readers and late indexing before cleanup finishes. */
    private fun purgeRetiredDocumentsLocked(ids: Set<String>) {
        if (ids.isEmpty()) return
        synchronized(mutationLock) {
            deletionVersion++
            for (store in listOfNotNull(vectorStore, orphanedStore).distinct()) {
                val present = store.getDocuments().map { it.documentId }.toSet()
                for (id in ids.intersect(present)) check(store.removeDocument(id)) { "Could not remove indexed document $id" }
            }
            ids.forEach { originals?.remove(it) }
        }
    }

    val totalIndexedChunks: Int
        get() = indexedDocuments.sumOf { it.chunkCount }

    val indexedDocuments: List<RagDocumentSummary>
        get() {
            val removed = runCatching { deletedDocumentIds() }.getOrElse { return emptyList() }
            return vectorStore.getDocuments().filterNot { it.documentId in removed }
        }

    private fun computeContentFingerprint(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Ingests a document from an Android content [uri] using [DocumentTextExtractor].
     */
    suspend fun ingestDocument(
        uri: Uri,
        fileName: String,
        mimeType: String = "",
        targetDocumentId: String? = null
    ): RagIngestionResult = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext RagIngestionResult(
            success = false,
            documentId = targetDocumentId ?: "",
            documentName = fileName,
            chunkCount = 0,
            totalCharacters = 0,
            message = "Android Context is required for Uri extraction"
        )

        try {
            var extractionError: String? = null
            val extractedText = DocumentTextExtractor.extract(
                context = ctx,
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                budget = com.shounak.localmeshai.utils.DocumentExtractionBudget(
                    maxExtractedChars = 200_000,
                    limitDescription = "200,000 characters for RAG knowledge base",
                    maxInputBytes = 20L * 1024L * 1024L
                ),
                onFailure = { extractionError = it }
            )

            val isExtractionError = extractedText.isBlank() || extractionError != null

            if (isExtractionError) {
                val errorMsg = if (extractedText.isBlank()) {
                    "No readable text could be extracted from $fileName"
                } else {
                    extractedText.take(160)
                }
                return@withContext RagIngestionResult(
                    success = false,
                    documentId = targetDocumentId ?: "",
                    documentName = fileName,
                    chunkCount = 0,
                    totalCharacters = 0,
                    message = errorMsg
                )
            }

            val indexed = ingestTextInternal(
                documentName = fileName,
                content = extractedText,
                targetDocumentId = targetDocumentId
            )
            if (indexed.success) {
                try {
                    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        saveOriginal(indexed.documentId, fileName, input) { job?.ensureActive() }
                    } ?: error("Could not retain the original upload")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    return@withContext indexed.copy(message = indexed.message + "; original-file preview unavailable: ${failure.message}")
                }
            }
            indexed
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            RagIngestionResult(
                success = false,
                documentId = targetDocumentId ?: "",
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
        content: String,
        targetDocumentId: String? = null
    ): RagIngestionResult = withContext(Dispatchers.Default) {
        ingestTextInternal(documentName, content, targetDocumentId)
    }

    private suspend fun ingestTextInternal(
        documentName: String,
        content: String,
        targetDocumentId: String? = null
    ): RagIngestionResult = ingestionMutex.withLock {
        val version = synchronized(mutationLock) { deletionVersion }
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return RagIngestionResult(
                success = false,
                documentId = targetDocumentId ?: "",
                documentName = documentName,
                chunkCount = 0,
                totalCharacters = 0,
                message = "Document text is empty"
            )
        }

        val fingerprint = computeContentFingerprint(trimmed)

        // Content equality does not establish ownership across chats or KB imports.
        val existingDocs = vectorStore.getDocuments()
        val matchingDoc = existingDocs.firstOrNull {
            targetDocumentId != null && it.documentId == targetDocumentId && it.contentFingerprint == fingerprint
        }
        if (matchingDoc != null) {
            return RagIngestionResult(
                success = true,
                documentId = matchingDoc.documentId,
                documentName = matchingDoc.documentName,
                chunkCount = matchingDoc.chunkCount,
                totalCharacters = matchingDoc.totalChars,
                message = "Reused existing index for identical content ($documentName)"
            )
        }

        val docId = targetDocumentId ?: UUID.randomUUID().toString()
        if (docId in deletedDocumentIds()) return@withLock RagIngestionResult(false, docId, documentName, 0, 0, "This document was removed. Attach it again to create a new upload.")
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
        val embeddings = try {
            embeddingEngine.embedBatch(textsToEmbed)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return RagIngestionResult(
                success = false,
                documentId = docId,
                documentName = documentName,
                chunkCount = 0,
                totalCharacters = 0,
                message = "Embedding generation failed: ${e.message}"
            )
        }

        if (embeddings.size != textChunks.size) {
            return RagIngestionResult(
                success = false,
                documentId = docId,
                documentName = documentName,
                chunkCount = 0,
                totalCharacters = 0,
                message = "Embedding batch size mismatch"
            )
        }

        // Validate dimension against vectorStore expected dimension
        val expectedDim = vectorStore.dimension
        val mismatched = embeddings.firstOrNull { it.size != expectedDim }
        if (mismatched != null) {
            return RagIngestionResult(
                success = false,
                documentId = docId,
                documentName = documentName,
                chunkCount = 0,
                totalCharacters = 0,
                message = "Incompatible vector dimension: expected $expectedDim, got ${mismatched.size}"
            )
        }

        val records = textChunks.mapIndexed { idx, chunk ->
            RagChunkRecord(
                id = chunk.id,
                documentId = docId,
                documentName = documentName,
                chunkIndex = chunk.index,
                text = chunk.text,
                embedding = embeddings[idx],
                timestamp = System.currentTimeMillis(),
                contentFingerprint = fingerprint
            )
        }

        kotlin.coroutines.coroutineContext.ensureActive()
        val persisted = synchronized(mutationLock) {
            if (version != deletionVersion || docId in deletedDocumentIds()) {
                return@withLock RagIngestionResult(false, docId, documentName, 0, 0,
                    "Documents changed during indexing. Retry if you still want to add this document.")
            }
            if (targetDocumentId != null) {
                vectorStore.replaceDocument(targetDocumentId, records)
            } else {
                vectorStore.addChunks(records)
            }
        }

        if (!persisted) {
            val errorMsg = (vectorStore as? InMemoryRagVectorStore)?.lastError ?: "Failed to persist document to storage"
            return RagIngestionResult(
                success = false,
                documentId = docId,
                documentName = documentName,
                chunkCount = 0,
                totalCharacters = 0,
                message = errorMsg
            )
        }

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
        val overviewIntent = summaryPatterns.any { lower.contains(it) }
        val unqualifiedReference = lower in setOf("what is this", "what is it about", "what does it say",
            "explain everything", "explain all", "explain this", "tell me about this", "brief me") ||
            RetrievalRelevance.isUnqualifiedOverview(lower)
        return overviewIntent && (isExplicitDocumentQuery(lower) || unqualifiedReference || findMatchingDocumentForQuery(lower) != null)
    }

    /**
     * Checks if a user query explicitly refers to an attached or referenced document.
     */
    fun isExplicitDocumentQuery(query: String): Boolean {
        val lower = query.lowercase(Locale.US).trim()
        val explicitPatterns = listOf(
            "this document", "the document", "this file", "the file", "attached document",
            "attached file", "this pdf", "the pdf", "what does this say", "what does the doc say",
            "what did i upload", "what i uploaded", "uploaded file", "uploaded doc", "uploaded document", "the uploaded file",
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
        if (docs.size == 1 && (isExplicitDocumentQuery(lower) || RetrievalRelevance.isUnqualifiedOverview(lower))) {
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
        preferredDocumentId: String? = null,
        allowGlobalSearch: Boolean = true,
        allowedDocumentIds: Set<String>? = null
    ): RagRetrievalResult = withContext(Dispatchers.Default) {
        if (RetrievalRelevance.isConversationOnly(query) || query.isBlank() || allowedDocumentIds?.isEmpty() == true) {
            return@withContext RagRetrievalResult(query.trim(), emptyList(), false)
        }
        // Indexing can take minutes. Never queue an interactive answer behind it.
        if (!ingestionMutex.tryLock()) {
            return@withContext RagRetrievalResult(query.trim(), emptyList(), false,
                "Documents are still being indexed; their contents are temporarily unavailable. Do not infer that no documents exist.")
        }
        try {
        val removed = deletedDocumentIds()
        val eligibleIds = allowedDocumentIds?.minus(removed)
            ?: if (removed.isNotEmpty()) indexedDocuments.map { it.documentId }.toSet() else null
        if (eligibleIds != null) {
            return@withContext retrieveScoped(query.trim(), eligibleIds, preferredDocumentId, topK, minScore)
        }
        val trimmedQuery = query.trim()
        if (vectorStore.getTotalChunkCount() == 0 || (!allowGlobalSearch && preferredDocumentId == null)) {
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
        } else if (effectiveDocId == null && (isExplicitDocumentQuery(trimmedQuery) || RetrievalRelevance.isUnqualifiedOverview(trimmedQuery))) {
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
        val current = active
        val engine = current.engine
        val store = current.store
        val queryEmb = if (trimmedQuery.isNotEmpty()) engine.embedQuery(trimmedQuery) else FloatArray(0)
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
                engine.embedQuery(cleanedQuery)
            } else null
        } else null

        // If a specific document is attached or resolved from query, prioritize with adaptive tolerance
        val filteredMatches = if (effectiveDocId != null) {
            val directDocThreshold = minScore
            val directDocMatches = store.getChunksForDocument(effectiveDocId, Int.MAX_VALUE)
                .map { chunk ->
                    val sim1 = VectorEmbedding.cosineSimilarity(queryEmb, chunk.embedding)
                    val sim2 = if (cleanedQueryEmb != null) VectorEmbedding.cosineSimilarity(cleanedQueryEmb, chunk.embedding) else 0f
                    RagSearchResult(chunk, engine.calibrate(maxOf(sim1, sim2)))
                }
                .filter { it.similarity >= directDocThreshold }
                .sortedByDescending { it.similarity }
                .take(topK)

            if (directDocMatches.isNotEmpty()) {
                directDocMatches
            } else if (!allowGlobalSearch) {
                emptyList()
            } else {
                // Fallback to searching all indexed chunks if direct document match was empty
                calibratedSearch(engine, store, queryEmb, topK, minScore)
            }
        } else {
            // General semantic vector search across all indexed chunks in the knowledge base
            calibratedSearch(engine, store, queryEmb, topK, minScore)
        }

        val relevantMatches = if (engine is UniversalSemanticEmbeddingEngine &&
            !isExplicitDocumentQuery(trimmedQuery) && !isOverviewOrSummaryQuery(trimmedQuery)) {
            filteredMatches.filter { RetrievalRelevance.hasLexicalEvidence(trimmedQuery, it.chunk.text) }
        } else filteredMatches
        RagRetrievalResult(
            query = trimmedQuery,
            matches = relevantMatches,
            hasContext = relevantMatches.isNotEmpty()
        )
        } finally {
            ingestionMutex.unlock()
        }
    }

    /** Called under ingestionMutex. Filter before ranking, including overview shortcuts. */
    private suspend fun retrieveScoped(query: String, ids: Set<String>, preferredId: String?, topK: Int, minScore: Float): RagRetrievalResult {
        val current = active
        val scopedChunks = ids.flatMap { current.store.getChunksForDocument(it, Int.MAX_VALUE) }
        val named = ChatDocumentScope.referencedNames(query, scopedChunks.map { it.documentName }.distinct())
        val chunks = if (named.isEmpty()) scopedChunks else scopedChunks.filter { it.documentName in named }
        if (chunks.isEmpty()) return RagRetrievalResult(query, emptyList(), false)
        val explicit = ChatDocumentScope.isDocumentQuestion(query, chunks.map { it.documentName }.distinct())
        val matches = if (explicit && isOverviewOrSummaryQuery(query)) {
            val overview = chunks.filter { named.isNotEmpty() || preferredId == null || preferredId !in ids || it.documentId == preferredId }
            overview.take(topK).map { RagSearchResult(it, 0.85f) }
        } else {
            val vector = current.engine.embedQuery(query)
            if (vector.isEmpty()) return RagRetrievalResult(query, emptyList(), false)
            chunks.asSequence().filter { current.engine !is UniversalSemanticEmbeddingEngine || explicit || RetrievalRelevance.hasLexicalEvidence(query, it.text) }
                .map { RagSearchResult(it, current.engine.calibrate(VectorEmbedding.cosineSimilarity(vector, it.embedding))) }
                .filter { it.similarity >= minScore }.sortedByDescending { it.similarity }.take(topK).toList()
        }
        return RagRetrievalResult(query, matches, matches.isNotEmpty())
    }

    private fun calibratedSearch(
        engine: SemanticEmbeddingEngine,
        store: VectorStore,
        queryEmb: FloatArray,
        topK: Int,
        minScore: Float
    ): List<RagSearchResult> =
        store.search(queryEmb, topK = topK, minSimilarity = engine.rawThreshold(minScore))
            .map { it.copy(similarity = engine.calibrate(it.similarity)) }

    /**
     * Replaces the embedding engine, re-embedding every stored chunk into [newStore].
     *
     * Chunk text, ids, names and fingerprints are kept, so no document has to be uploaded again.
     * The old store is cleared only after the new one has persisted, so a failure leaves the
     * knowledge base exactly as it was.
     */
    suspend fun switchEmbeddingEngine(
        newEngine: SemanticEmbeddingEngine,
        newStore: VectorStore,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        beforeActivation: () -> Unit = {}
    ): Result<Int> = ingestionMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                require(newStore.dimension == newEngine.dimension) {
                    "Store dimension ${newStore.dimension} does not match engine dimension ${newEngine.dimension}"
                }
                check(newStore.loadAsync() is com.shounak.localmeshai.data.StoreLoadState.Ready) {
                    "Destination search index requires recovery"
                }
                val oldEngine = embeddingEngine
                val oldStore = vectorStore
                if (oldStore.loadState.value is com.shounak.localmeshai.data.StoreLoadState.Loading) oldStore.loadAsync()
                check(oldStore.loadState.value is com.shounak.localmeshai.data.StoreLoadState.Ready) {
                    "Current search index requires recovery"
                }
                val version = synchronized(mutationLock) { deletionVersion }
                require(oldStore !== newStore || oldEngine.algorithmId == newEngine.algorithmId) {
                    "Different embedding engines require separate indexes"
                }
                // Validate native initialization even when there are no documents to migrate.
                val probe = newEngine.embedQuery("Document search")
                check(probe.size == newEngine.dimension && probe.all { it.isFinite() }) { "Invalid search embedding output" }
                // Leftovers from an interrupted earlier switch would otherwise resurrect deleted documents.
                if (oldStore !== newStore && newStore.getTotalChunkCount() > 0) newStore.clearAll()
                check(oldStore === newStore || newStore.getTotalChunkCount() == 0) { "Could not reset destination search index" }
                val migrated = if (oldStore === newStore) 0 else migrateChunks(oldStore, newEngine, newStore, onProgress)
                ensureActive()
                synchronized(mutationLock) {
                    check(deletionVersion == version) { "Documents changed during re-indexing. Retry to use the updated knowledge base." }
                    // Save the selector durably before clearing the old index. A killed process can
                    // always find a complete store, rather than a partially migrated destination.
                    beforeActivation()
                    active = ActiveIndex(newEngine, newStore)
                    _loadState.value = newStore.loadState.value
                    if (oldStore !== newStore) oldStore.clearAll()
                }
                if (oldEngine !== newEngine) oldEngine.release()
                migrated
            }.onFailure {
                // Free the candidate model's RAM; the knowledge base keeps using the old engine.
                if (newEngine !== embeddingEngine) newEngine.release()
            }
        }
    }

    private suspend fun migrateChunks(
        from: VectorStore,
        engine: SemanticEmbeddingEngine,
        to: VectorStore,
        onProgress: (done: Int, total: Int) -> Unit,
        preserveExisting: Boolean = false
    ): Int {
        val documents = from.getDocuments()
        val total = documents.sumOf { it.chunkCount }
        var done = 0
        onProgress(0, total)
        for (doc in documents) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val chunks = from.getChunksForDocument(doc.documentId, Int.MAX_VALUE).sortedBy { it.chunkIndex }
            if (chunks.isEmpty()) continue
            if (doc.documentId in deletedDocumentIds() || preserveExisting && to.getChunksForDocument(doc.documentId, 1).isNotEmpty()) {
                done += chunks.size
                onProgress(done, total)
                continue
            }
            val prefix = doc.documentName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
            val vectors = engine.embedBatch(chunks.map { if (prefix.isNotBlank()) "$prefix: ${it.text}" else it.text })
            check(vectors.size == chunks.size) { "Embedding batch size mismatch while re-indexing ${doc.documentName}" }
            check(vectors.all { it.size == to.dimension }) { "Incompatible vector dimension while re-indexing ${doc.documentName}" }
            val records = chunks.mapIndexed { i, chunk -> chunk.copy(embedding = vectors[i]) }
            val persisted = synchronized(mutationLock) {
                if (doc.documentId in deletedDocumentIds() || preserveExisting && to.getChunksForDocument(doc.documentId, 1).isNotEmpty()) true
                else to.replaceDocument(doc.documentId, records)
            }
            if (!persisted) {
                val reason = (to as? InMemoryRagVectorStore)?.lastError ?: "Failed to persist re-indexed document"
                throw IllegalStateException(reason)
            }
            done += chunks.size
            onProgress(done, total)
        }
        return done
    }

    /** Moves documents out of a store whose embedding model was removed, re-embedding with the active engine. */
    private suspend fun recoverOrphanedStore() {
        val orphan = orphanedStore ?: return
        withContext(Dispatchers.IO) {
            check(orphan.loadAsync() is com.shounak.localmeshai.data.StoreLoadState.Ready) { "Previous search index requires recovery" }
            val version = synchronized(mutationLock) { deletionVersion }
            // Preserve uploads already added to fallback; durable retirement records were
            // applied to both stores before this step and also guard every migrated write.
            migrateChunks(orphan, embeddingEngine, vectorStore, { _, _ -> }, preserveExisting = true)
            check(synchronized(mutationLock) { deletionVersion == version }) { "Documents changed during recovery. Retry document search to finish safely." }
            context?.let { com.shounak.localmeshai.utils.AppSettings.getInstance(it).commitEmbeddingModel(embeddingEngine.algorithmId) }
            orphan.clearAll()
            orphanedStore = null
        }
    }

    /**
     * Turns the optional neural embedder on ([modelId] from [EmbeddingModels]) or back to the
     * built-in lexical engine ([EmbeddingModels.LEXICAL_ID]), downloading and re-indexing as needed.
     * Progress is published on [embeddingStatus]; the chosen model is saved in AppSettings on success.
     */
    suspend fun setEmbeddingModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("Android Context is required"))
        if (modelId == embeddingEngine.algorithmId && modelId != EmbeddingModels.LEXICAL_ID) return@withContext Result.success(Unit)
        val result = runCatching {
            if (modelId == embeddingEngine.algorithmId) {
                // Also remove partial downloads, or retry file cleanup after a completed switch.
                _embeddingStatus.value = EmbeddingModelStatus.Reindexing(0, 0)
                EmbeddingModels.all.forEach { EmbeddingModels.delete(ctx, it) }
                return@runCatching
            }
            val engine: SemanticEmbeddingEngine
            if (modelId == EmbeddingModels.LEXICAL_ID) {
                engine = UniversalSemanticEmbeddingEngine()
            } else {
                val spec = requireNotNull(EmbeddingModels.find(modelId)) { "Unknown embedding model $modelId" }
                _embeddingStatus.value = EmbeddingModelStatus.Downloading(0L, spec.downloadBytes)
                EmbeddingModels.download(ctx, spec) { done, total ->
                    _embeddingStatus.value = EmbeddingModelStatus.Downloading(done, total)
                }
                engine = EmbeddingModels.createEngine(ctx, spec)
            }
            _embeddingStatus.value = EmbeddingModelStatus.Reindexing(0, totalIndexedChunks)
            switchEmbeddingEngine(engine, storeFor(ctx, engine),
                onProgress = { done, total -> _embeddingStatus.value = EmbeddingModelStatus.Reindexing(done, total) },
                beforeActivation = { com.shounak.localmeshai.utils.AppSettings.getInstance(ctx).commitEmbeddingModel(modelId) }
            ).getOrThrow()
            if (modelId == EmbeddingModels.LEXICAL_ID) EmbeddingModels.all.forEach { EmbeddingModels.delete(ctx, it) }
        }
        val error = result.exceptionOrNull()
        // Even fast cleanup of an inactive download must invalidate the card's file state.
        _embeddingFilesRevision.value += 1
        _embeddingStatus.value = when (error) {
            null, is kotlinx.coroutines.CancellationException -> EmbeddingModelStatus.Idle
            else -> EmbeddingModelStatus.Failed(error.message ?: error.javaClass.simpleName)
        }
        if (error is kotlinx.coroutines.CancellationException) throw error
        result
    }

    private val modelSwitchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    @Volatile private var modelSwitchJob: kotlinx.coroutines.Job? = null

    /**
     * Fire-and-forget [setEmbeddingModel] on an app-lifetime scope, so leaving the Settings
     * screen does not cancel a download or re-index halfway. Ignored while a switch is running.
     */
    fun requestEmbeddingModel(modelId: String) {
        synchronized(this) {
            if (modelSwitchJob?.isActive == true) return
            modelSwitchJob = modelSwitchScope.launch { setEmbeddingModel(modelId) }
        }
    }

    /** Frees the embedder's model session now (it also frees itself after a minute idle). */
    fun releaseEmbeddingResources() = embeddingEngine.release()

    /**
     * Formats an augmented prompt embedding the retrieved RAG context.
     */
    fun buildAugmentedPrompt(
        rawUserQuery: String,
        retrieval: RagRetrievalResult
    ): String {
        retrieval.unavailableReason?.let { return "[Document search status: $it]\n\n$rawUserQuery" }
        if (!retrieval.hasContext || retrieval.matches.isEmpty()) {
            return rawUserQuery
        }

        return buildString {
            append("[Relevant Document Context]\n")
            retrieval.matches.forEachIndexed { i, match ->
                val scorePct = (match.similarity * 100f).toInt()
                append("--- Document: \"${match.chunk.documentName}\" (Section #${match.chunk.chunkIndex + 1}, Match heuristic: $scorePct%) ---\n")
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
        return synchronized(mutationLock) {
            check(deletionJournal?.recordRagDeletions(setOf(documentId)) != false) { "Could not record document deletion" }
            deletionVersion++
            vectorStore.removeDocument(documentId).also { if (it) originals?.remove(documentId) }
        }
    }

    /**
     * Wipes all indexed documents and vector embeddings.
     */
    fun clearKnowledgeBase() {
        synchronized(mutationLock) {
            val retired = (vectorStore.getDocuments() + orphanedStore?.getDocuments().orEmpty()).map { it.documentId }.toSet()
            check(deletionJournal?.recordRagDeletions(retired) != false) { "Could not record document deletion" }
            deletionVersion++
            val ids = vectorStore.getDocuments().map { it.documentId }
            vectorStore.clearAll()
            ids.forEach { originals?.remove(it) }
        }
    }

    companion object {
        @Volatile
        private var instance: RagManager? = null

        fun getInstance(context: Context): RagManager {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(context: Context): RagManager {
            val selected = com.shounak.localmeshai.utils.AppSettings.getInstance(context).settings.value.ragEmbeddingModel
            val spec = EmbeddingModels.find(selected)
            if (spec != null && EmbeddingModels.isInstalled(context, spec)) {
                val engine = EmbeddingModels.createEngine(context, spec)
                return RagManager(context, engine, storeFor(context, engine))
            }
            val lexical = UniversalSemanticEmbeddingEngine()
            // The neural model was selected but its files are gone: keep the documents by
            // re-embedding them lexically on first load, and fall back in settings.
            val orphan = spec?.let {
                InMemoryRagVectorStore(
                    context,
                    storageFileName = storeFileName(it.id),
                    expectedDimension = it.dimension,
                    algorithmId = it.id
                )
            }
            return RagManager(context, lexical, storeFor(context, lexical), orphanedStore = orphan)
        }

        internal fun storeFileName(algorithmId: String): String =
            if (algorithmId == EmbeddingModels.LEXICAL_ID) "rag_vector_store.json" else "rag_vector_store_$algorithmId.json"

        internal fun storeFor(context: Context?, engine: SemanticEmbeddingEngine): VectorStore =
            InMemoryRagVectorStore(
                context,
                storageFileName = storeFileName(engine.algorithmId),
                expectedDimension = engine.dimension,
                algorithmId = engine.algorithmId
            )
    }
}

sealed interface EmbeddingModelStatus {
    data object Idle : EmbeddingModelStatus
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : EmbeddingModelStatus
    data class Reindexing(val chunksDone: Int, val chunksTotal: Int) : EmbeddingModelStatus
    data class Failed(val message: String) : EmbeddingModelStatus
}
