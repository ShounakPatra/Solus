package com.shounak.localmeshai.rag.store

import android.content.Context
import com.shounak.localmeshai.data.StoreLoadState
import com.shounak.localmeshai.rag.embedding.VectorEmbedding
import com.shounak.localmeshai.utils.AtomicFileTransaction
import com.shounak.localmeshai.utils.AtomicJsonFile
import com.shounak.localmeshai.utils.AtomicReadResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RagChunkRecord(
    val id: String,
    val documentId: String,
    val documentName: String,
    val chunkIndex: Int,
    val text: String,
    val embedding: FloatArray,
    val timestamp: Long = System.currentTimeMillis(),
    val contentFingerprint: String? = null
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
    val addedAt: Long,
    val contentFingerprint: String? = null
)

class IncompatibleVectorStoreException(message: String) : IllegalStateException(message)

interface VectorStore {
    val loadState: StateFlow<StoreLoadState> get() = MutableStateFlow(StoreLoadState.Ready).asStateFlow()
    val algorithmId: String get() = "universal-fnv1a-384"
    val dimension: Int get() = 384
    val distanceMetric: String get() = "cosine"
    fun addChunks(chunks: List<RagChunkRecord>): Boolean
    fun replaceDocument(documentId: String, newChunks: List<RagChunkRecord>): Boolean
    fun removeDocument(documentId: String): Boolean
    fun clearAll()
    fun getDocuments(): List<RagDocumentSummary>
    fun getTotalChunkCount(): Int
    fun search(queryEmbedding: FloatArray, topK: Int = 3, minSimilarity: Float = 0.22f): List<RagSearchResult>
    fun getChunksForDocument(documentId: String, limit: Int = Int.MAX_VALUE): List<RagChunkRecord>
    fun getRecentChunks(limit: Int = 3): List<RagChunkRecord>
    fun persist()
    fun load()
    suspend fun loadAsync(): StoreLoadState = loadState.value
}

/**
 * Thread-safe vector store with in-memory similarity search and atomic file persistence.
 */
class InMemoryRagVectorStore(
    private val context: Context? = null,
    private val storageDir: File? = null,
    private val storageFileName: String = "rag_vector_store.json",
    private val transaction: AtomicFileTransaction? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    expectedDimension: Int? = null,
    override val algorithmId: String = "universal-fnv1a-384",
    override val distanceMetric: String = "cosine"
) : VectorStore {

    private val _loadState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    override val loadState: StateFlow<StoreLoadState> = _loadState.asStateFlow()

    @Volatile
    private var isLoaded: Boolean = false

    private var explicitDimension: Int? = expectedDimension

    override val dimension: Int
        get() = explicitDimension ?: recordsState.firstOrNull()?.embedding?.size ?: 384

    @Volatile
    private var recordsState: List<RagChunkRecord> = emptyList()
    private val lock = Any()

    var isMarkedForReindexing: Boolean = false
        private set

    var lastError: String? = null
        private set

    private val atomicFile: AtomicJsonFile<List<RagChunkRecord>>? by lazy {
        val file = getStorageFile() ?: return@lazy null
        if (transaction != null) {
            AtomicJsonFile(file, transaction)
        } else {
            AtomicJsonFile(file)
        }
    }

    private fun ensureLoaded() {
        if (!isLoaded) {
            synchronized(lock) {
                if (!isLoaded) {
                    load()
                }
            }
        }
    }

    private fun getStorageFile(): File? {
        val dir = storageDir ?: context?.filesDir ?: return null
        return File(dir, storageFileName)
    }

    override fun addChunks(chunks: List<RagChunkRecord>): Boolean {
        ensureLoaded()
        if (chunks.isEmpty()) return true
        val expectedDim = explicitDimension ?: recordsState.firstOrNull()?.embedding?.size
        if (expectedDim != null) {
            val mismatched = chunks.firstOrNull { it.embedding.size != expectedDim }
            if (mismatched != null) {
                lastError = "Incompatible vector dimension: expected $expectedDim, got ${mismatched.embedding.size}"
                return false
            }
        } else {
            val firstDim = chunks.first().embedding.size
            val mismatched = chunks.firstOrNull { it.embedding.size != firstDim }
            if (mismatched != null) {
                lastError = "Incompatible vector dimension across batch: expected $firstDim, got ${mismatched.embedding.size}"
                return false
            }
        }
        synchronized(lock) {
            val nextList = recordsState + chunks
            val success = persistSnapshot(nextList)
            if (success) {
                recordsState = nextList
                lastError = null
                return true
            }
            return false
        }
    }

    override fun replaceDocument(documentId: String, newChunks: List<RagChunkRecord>): Boolean {
        ensureLoaded()
        if (newChunks.isEmpty()) {
            lastError = "Cannot replace document with empty chunks"
            return false
        }
        val expectedDim = explicitDimension ?: recordsState.firstOrNull { it.documentId != documentId }?.embedding?.size
        if (expectedDim != null) {
            val mismatched = newChunks.firstOrNull { it.embedding.size != expectedDim }
            if (mismatched != null) {
                lastError = "Incompatible vector dimension: expected $expectedDim, got ${mismatched.embedding.size}"
                return false
            }
        } else {
            val firstDim = newChunks.first().embedding.size
            val mismatched = newChunks.firstOrNull { it.embedding.size != firstDim }
            if (mismatched != null) {
                lastError = "Incompatible vector dimension across batch: expected $firstDim, got ${mismatched.embedding.size}"
                return false
            }
        }
        synchronized(lock) {
            val remaining = recordsState.filter { it.documentId != documentId }
            val nextList = remaining + newChunks
            val success = persistSnapshot(nextList)
            if (success) {
                recordsState = nextList
                lastError = null
                return true
            }
            return false
        }
    }

    override fun removeDocument(documentId: String): Boolean {
        ensureLoaded()
        synchronized(lock) {
            val nextList = recordsState.filter { it.documentId != documentId }
            if (nextList.size == recordsState.size) return false
            val success = persistSnapshot(nextList)
            if (success) {
                recordsState = nextList
                lastError = null
                return true
            }
            return false
        }
    }

    override fun clearAll() {
        ensureLoaded()
        synchronized(lock) {
            val nextList = emptyList<RagChunkRecord>()
            val success = persistSnapshot(nextList)
            if (success) {
                recordsState = nextList
                lastError = null
            }
        }
    }

    override fun getDocuments(): List<RagDocumentSummary> {
        ensureLoaded()
        val current = recordsState
        val groups = current.groupBy { it.documentId }
        return groups.map { (docId, docChunks) ->
            val first = docChunks.first()
            RagDocumentSummary(
                documentId = docId,
                documentName = first.documentName,
                chunkCount = docChunks.size,
                totalChars = docChunks.sumOf { it.text.length },
                addedAt = first.timestamp,
                contentFingerprint = first.contentFingerprint
            )
        }.sortedByDescending { it.addedAt }
    }

    override fun getTotalChunkCount(): Int {
        ensureLoaded()
        return recordsState.size
    }

    override fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        minSimilarity: Float
    ): List<RagSearchResult> {
        ensureLoaded()
        val current = recordsState
        if (current.isEmpty() || queryEmbedding.isEmpty()) return emptyList()

        val results = ArrayList<RagSearchResult>(current.size)
        for (record in current) {
            val sim = VectorEmbedding.cosineSimilarity(queryEmbedding, record.embedding)
            if (sim >= minSimilarity) {
                results.add(RagSearchResult(record, sim))
            }
        }

        results.sortByDescending { it.similarity }
        return if (results.size > topK) results.take(topK) else results
    }

    override fun getChunksForDocument(documentId: String, limit: Int): List<RagChunkRecord> {
        ensureLoaded()
        return recordsState
            .filter { it.documentId == documentId }
            .sortedBy { it.chunkIndex }
            .take(limit)
    }

    override fun getRecentChunks(limit: Int): List<RagChunkRecord> {
        ensureLoaded()
        val current = recordsState
        val latestDocId = current.maxByOrNull { it.timestamp }?.documentId ?: return emptyList()
        return getChunksForDocument(latestDocId, limit)
    }

    private fun persistSnapshot(snapshot: List<RagChunkRecord>): Boolean {
        if (_loadState.value !is StoreLoadState.Ready) {
            lastError = "Knowledge base storage requires recovery before changes can be saved."
            return false
        }
        val af = atomicFile ?: return true
        val resolvedDim = explicitDimension ?: snapshot.firstOrNull()?.embedding?.size ?: dimension
        val result = af.write(snapshot) {
            serializeRecords(
                records = it,
                algorithmId = algorithmId,
                dimension = resolvedDim,
                distanceMetric = distanceMetric
            )
        }
        return if (result.isSuccess) {
            true
        } else {
            lastError = result.exceptionOrNull()?.message ?: "Failed to persist RAG vector store"
            false
        }
    }

    override fun persist() {
        ensureLoaded()
        synchronized(lock) {
            persistSnapshot(recordsState)
        }
    }

    override fun load() {
        val af = atomicFile ?: run {
            _loadState.value = StoreLoadState.Ready
            isLoaded = true
            return
        }
        synchronized(lock) {
            val result = af.read { deserializeRecords(it, explicitDimension, algorithmId, distanceMetric) }
            when (result) {
                is AtomicReadResult.Success -> {
                    recordsState = result.data
                    isMarkedForReindexing = false
                    lastError = null
                    _loadState.value = StoreLoadState.Ready
                }
                is AtomicReadResult.Missing -> {
                    recordsState = emptyList()
                    isMarkedForReindexing = false
                    lastError = null
                    _loadState.value = StoreLoadState.Ready
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    recordsState = emptyList()
                    lastError = "Unsupported RAG vector store file version: ${result.foundVersion}"
                    _loadState.value = StoreLoadState.RecoveryRequired(
                        "RAG store uses newer version (${result.foundVersion} > ${result.maxSupportedVersion})",
                        result.file
                    )
                }
                is AtomicReadResult.Corrupt -> {
                    val rootCause = generateSequence(result.cause) { it.cause }
                        .firstOrNull { it is IncompatibleVectorStoreException }
                    if (rootCause != null) {
                        recordsState = emptyList()
                        isMarkedForReindexing = true
                        lastError = "Vector store index requires reindexing due to configuration mismatch: ${rootCause.message}"
                        _loadState.value = StoreLoadState.Ready
                    } else {
                        recordsState = emptyList()
                        lastError = "Corrupt RAG store detected: ${result.cause.message}"
                        _loadState.value = StoreLoadState.RecoveryRequired(
                            "RAG store is corrupt: ${result.cause.message}",
                            result.file
                        )
                    }
                }
            }
            isLoaded = true
        }
    }

    override suspend fun loadAsync(): StoreLoadState = withContext(ioDispatcher) {
        load()
        _loadState.value
    }

    companion object {
        fun serializeRecords(
            records: List<RagChunkRecord>,
            algorithmId: String = "universal-fnv1a-384",
            dimension: Int = records.firstOrNull()?.embedding?.size ?: 384,
            distanceMetric: String = "cosine"
        ): String {
            val root = JSONObject().apply {
                put("version", 2)
                put("algorithmId", algorithmId)
                put("dimension", dimension)
                put("distanceMetric", distanceMetric)
                val jsonArray = JSONArray()
                for (rec in records) {
                    val obj = JSONObject().apply {
                        put("id", rec.id)
                        put("docId", rec.documentId)
                        put("name", rec.documentName)
                        put("idx", rec.chunkIndex)
                        put("text", rec.text)
                        put("ts", rec.timestamp)
                        rec.contentFingerprint?.let { put("fp", it) }
                        val embArr = JSONArray()
                        for (v in rec.embedding) {
                            embArr.put(v.toDouble())
                        }
                        put("emb", embArr)
                    }
                    jsonArray.put(obj)
                }
                put("records", jsonArray)
            }
            return root.toString()
        }

        fun deserializeRecords(
            text: String,
            expectedDimension: Int? = null,
            expectedAlgorithmId: String? = null,
            expectedDistanceMetric: String? = null
        ): List<RagChunkRecord> {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "RAG store is blank" }
            val tokenizer = org.json.JSONTokener(trimmed)
            val document = tokenizer.nextValue()
            require(tokenizer.nextClean() == 0.toChar()) { "Trailing data after RAG JSON" }
            require(document is JSONObject || document is JSONArray) { "RAG store must be an object or legacy array" }
            val root = document as? JSONObject
            val version = root?.let {
                if (!it.has("version")) 1 else {
                    val raw = it.get("version")
                    require(raw is Int || raw is Long) { "RAG version must be an integer" }
                    val number = (raw as Number).toLong()
                    require(number > 0) { "Invalid RAG version" }
                    if (number > 2) throw com.shounak.localmeshai.utils.UnsupportedVersionException(
                        number.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), 2)
                    number.toInt()
                }
            } ?: 1
            val jsonArray = root?.getJSONArray("records") ?: document as JSONArray
            fun nonnegativeInteger(json: JSONObject, key: String): Long {
                val value = json.get(key)
                require((value is Int || value is Long) && (value as Number).toLong() >= 0) { "Invalid RAG field $key" }
                return (value as Number).toLong()
            }
            val fileDimension = root?.takeIf { it.has("dimension") }?.let {
                nonnegativeInteger(it, "dimension").also { value -> require(value in 1..Int.MAX_VALUE.toLong()) }.toInt()
            }
            fun metadata(key: String): String? = root?.takeIf { it.has(key) }?.let {
                require(it.get(key) is String && it.getString(key).isNotBlank()) { "Invalid RAG field $key" }
                it.getString(key)
            }
            val fileAlgorithm = metadata("algorithmId")
            val fileMetric = metadata("distanceMetric")
            if (version == 2) require(fileDimension != null && fileAlgorithm != null && fileMetric != null) { "RAG metadata is incomplete" }

            // Validate every record before reporting an index configuration mismatch.
            // Otherwise a damaged document could become a writable, empty reindex store.
            val loaded = ArrayList<RagChunkRecord>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                for (key in listOf("id", "docId", "name", "text")) {
                    require(obj.get(key) is String) { "Invalid RAG field $key" }
                    if (key != "text") require(obj.getString(key).isNotBlank()) { "Blank RAG field $key" }
                }
                val index = nonnegativeInteger(obj, "idx")
                require(index <= Int.MAX_VALUE)
                if (obj.has("ts")) nonnegativeInteger(obj, "ts")
                if (obj.has("fp")) require(obj.get("fp") is String)
                val embArr = obj.getJSONArray("emb")
                require(embArr.length() > 0 && (fileDimension == null || embArr.length() == fileDimension)) { "Invalid stored embedding dimension" }
                val emb = FloatArray(embArr.length()) { j ->
                    val value = embArr.get(j)
                    require(value is Number) { "Embedding values must be numbers" }
                    value.toFloat().also { require(it.isFinite()) { "Embedding value is not finite" } }
                }
                loaded.add(RagChunkRecord(
                    id = obj.getString("id"), documentId = obj.getString("docId"), documentName = obj.getString("name"),
                    chunkIndex = index.toInt(), text = obj.getString("text"), embedding = emb,
                    timestamp = obj.optLong("ts", System.currentTimeMillis()),
                    contentFingerprint = if (obj.has("fp")) obj.getString("fp") else null
                ))
            }
            if (expectedDimension != null && fileDimension != null && expectedDimension != fileDimension) {
                throw IncompatibleVectorStoreException("Dimension mismatch: expected $expectedDimension, got $fileDimension")
            }
            if (expectedAlgorithmId != null && fileAlgorithm != null && expectedAlgorithmId != fileAlgorithm) {
                throw IncompatibleVectorStoreException("Algorithm mismatch: expected $expectedAlgorithmId, got $fileAlgorithm")
            }
            if (expectedDistanceMetric != null && fileMetric != null && expectedDistanceMetric != fileMetric) {
                throw IncompatibleVectorStoreException("Distance metric mismatch: expected $expectedDistanceMetric, got $fileMetric")
            }
            if (expectedDimension != null && loaded.any { it.embedding.size != expectedDimension }) {
                throw IncompatibleVectorStoreException("Chunk embedding dimension does not match target dimension $expectedDimension")
            }
            return loaded
        }
    }
}
