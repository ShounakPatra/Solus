package com.shounak.localmeshai.rag

import android.content.Context
import android.net.Uri
import com.shounak.localmeshai.rag.chunking.TextChunker
import com.shounak.localmeshai.rag.embedding.SemanticEmbeddingEngine
import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
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

            if (extractedText.isBlank() || extractedText.startsWith("No readable text could be extracted")) {
                return@withContext RagIngestionResult(
                    success = false,
                    documentId = "",
                    documentName = fileName,
                    chunkCount = 0,
                    totalCharacters = 0,
                    message = "No readable text could be extracted from $fileName"
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

        val texts = textChunks.map { it.text }
        val embeddings = embeddingEngine.embedBatch(texts)

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
     * Performs semantic vector search for [query] and returns matching chunks.
     */
    suspend fun retrieve(
        query: String,
        topK: Int = 3,
        minScore: Float = 0.30f
    ): RagRetrievalResult = withContext(Dispatchers.Default) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty() || vectorStore.getTotalChunkCount() == 0) {
            return@withContext RagRetrievalResult(
                query = trimmedQuery,
                matches = emptyList(),
                hasContext = false
            )
        }

        val queryEmb = embeddingEngine.embed(trimmedQuery)
        val matches = vectorStore.search(queryEmb, topK = topK, minSimilarity = minScore)

        RagRetrievalResult(
            query = trimmedQuery,
            matches = matches,
            hasContext = matches.isNotEmpty()
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
