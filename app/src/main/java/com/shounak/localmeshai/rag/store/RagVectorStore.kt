package com.shounak.localmeshai.rag.store

import android.content.Context
import com.shounak.localmeshai.rag.embedding.VectorEmbedding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class RagChunkRecord(
    val id: String,
    val documentId: String,
    val documentName: String,
    val chunkIndex: Int,
    val text: String,
    val embedding: FloatArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RagChunkRecord
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

data class RagSearchResult(
    val chunk: RagChunkRecord,
    val similarity: Float
)

data class RagDocumentSummary(
    val documentId: String,
    val documentName: String,
    val chunkCount: Int,
    val totalChars: Int,
    val addedAt: Long
)

interface VectorStore {
    fun addChunks(chunks: List<RagChunkRecord>)
    fun removeDocument(documentId: String): Boolean
    fun clearAll()
    fun getDocuments(): List<RagDocumentSummary>
    fun getTotalChunkCount(): Int
    fun search(queryEmbedding: FloatArray, topK: Int = 3, minSimilarity: Float = 0.30f): List<RagSearchResult>
    fun persist()
    fun load()
}

/**
 * Thread-safe vector store with in-memory similarity search and file persistence.
 */
class InMemoryRagVectorStore(
    private val context: Context? = null,
    private val storageFileName: String = "rag_vector_store.json"
) : VectorStore {

    private val records = CopyOnWriteArrayList<RagChunkRecord>()
    private val lock = Any()

    init {
        load()
    }

    override fun addChunks(chunks: List<RagChunkRecord>) {
        if (chunks.isEmpty()) return
        synchronized(lock) {
            records.addAll(chunks)
            persist()
        }
    }

    override fun removeDocument(documentId: String): Boolean {
        synchronized(lock) {
            val removed = records.removeIf { it.documentId == documentId }
            if (removed) {
                persist()
            }
            return removed
        }
    }

    override fun clearAll() {
        synchronized(lock) {
            records.clear()
            persist()
        }
    }

    override fun getDocuments(): List<RagDocumentSummary> {
        val groups = records.groupBy { it.documentId }
        return groups.map { (docId, docChunks) ->
            val first = docChunks.first()
            RagDocumentSummary(
                documentId = docId,
                documentName = first.documentName,
                chunkCount = docChunks.size,
                totalChars = docChunks.sumOf { it.text.length },
                addedAt = first.timestamp
            )
        }.sortedByDescending { it.addedAt }
    }

    override fun getTotalChunkCount(): Int = records.size

    override fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        minSimilarity: Float
    ): List<RagSearchResult> {
        if (records.isEmpty() || queryEmbedding.isEmpty()) return emptyList()

        val results = ArrayList<RagSearchResult>(records.size)
        for (record in records) {
            val sim = VectorEmbedding.cosineSimilarity(queryEmbedding, record.embedding)
            if (sim >= minSimilarity) {
                results.add(RagSearchResult(record, sim))
            }
        }

        results.sortByDescending { it.similarity }
        return if (results.size > topK) results.take(topK) else results
    }

    override fun persist() {
        val ctx = context ?: return
        try {
            val file = File(ctx.filesDir, storageFileName)
            val jsonArray = JSONArray()
            synchronized(lock) {
                for (rec in records) {
                    val obj = JSONObject().apply {
                        put("id", rec.id)
                        put("docId", rec.documentId)
                        put("name", rec.documentName)
                        put("idx", rec.chunkIndex)
                        put("text", rec.text)
                        put("ts", rec.timestamp)
                        val embArr = JSONArray()
                        for (v in rec.embedding) {
                            embArr.put(v.toDouble())
                        }
                        put("emb", embArr)
                    }
                    jsonArray.put(obj)
                }
            }
            file.writeText(jsonArray.toString())
        } catch (_: Throwable) {
            // Ignore persistence errors gracefully
        }
    }

    override fun load() {
        val ctx = context ?: return
        try {
            val file = File(ctx.filesDir, storageFileName)
            if (!file.exists()) return
            val text = file.readText()
            if (text.isBlank()) return
            val jsonArray = JSONArray(text)
            val loaded = ArrayList<RagChunkRecord>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val embArr = obj.getJSONArray("emb")
                val emb = FloatArray(embArr.length())
                for (j in 0 until embArr.length()) {
                    emb[j] = embArr.getDouble(j).toFloat()
                }
                loaded.add(
                    RagChunkRecord(
                        id = obj.getString("id"),
                        documentId = obj.getString("docId"),
                        documentName = obj.getString("name"),
                        chunkIndex = obj.getInt("idx"),
                        text = obj.getString("text"),
                        embedding = emb,
                        timestamp = obj.optLong("ts", System.currentTimeMillis())
                    )
                )
            }
            synchronized(lock) {
                records.clear()
                records.addAll(loaded)
            }
        } catch (_: Throwable) {
            // Ignore load errors gracefully
        }
    }
}
