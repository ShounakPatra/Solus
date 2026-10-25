package com.shounak.localmeshai.rag.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * Neural sentence embedder for BERT-family ONNX encoders (bge-small-en-v1.5 by default).
 *
 * Memory policy: the ORT session loads on first use and is released after [idleReleaseMillis]
 * without calls, so the embedder only shares RAM with the chat model while documents are being
 * indexed or a question is being embedded. The int8 bge-small model is ~34 MB on disk.
 */
class OnnxBertEmbeddingEngine(
    private val modelFile: File,
    private val vocabFile: File,
    override val algorithmId: String,
    override val dimension: Int = 384,
    private val queryPrefix: String = "",
    private val maxTokens: Int = 512,
    private val batchSize: Int = 8,
    private val intraOpThreads: Int = 2,
    private val idleReleaseMillis: Long = 60_000L,
    private val calibrationFloor: Float = 0f,
    private val calibrationSpan: Float = 1f
) : SemanticEmbeddingEngine {

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val sessionMutex = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private var hasTokenTypeIds = false
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var releaseJob: Job? = null

    init {
        require(calibrationSpan > 0f) { "calibrationSpan must be positive" }
        require(batchSize >= 1) { "batchSize must be at least 1" }
    }

    override suspend fun embed(text: String): FloatArray = embedBatch(listOf(text)).first()

    override suspend fun embedQuery(text: String): FloatArray = embed(queryPrefix + text)

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            sessionMutex.withLock {
                try {
                    val (ortSession, tk) = openLocked()
                    ensureActive() // Cold session creation can outlive the interactive caller's deadline.
                    val encoded = texts.map { if (it.isBlank()) null else tk.encode(it) }
                    val results = arrayOfNulls<FloatArray>(texts.size)
                    // Sort by length so each batch pads as little as possible.
                    val order = encoded.indices.filter { encoded[it] != null }.sortedBy { encoded[it]!!.size }
                    for (batch in order.chunked(batchSize)) {
                        ensureActive()
                        val vectors = runBatch(ortSession, batch.map { encoded[it]!! })
                        batch.forEachIndexed { i, index -> results[index] = vectors[i] }
                    }
                    results.map { it ?: FloatArray(dimension) }
                } finally {
                    scheduleRelease()
                }
            }
        }
    }

    override fun calibrate(rawCosine: Float): Float =
        ((rawCosine - calibrationFloor) / calibrationSpan).coerceIn(0f, 1f)

    override fun rawThreshold(calibrated: Float): Float = calibrationFloor + calibrationSpan * calibrated

    override fun release() {
        releaseJob?.cancel()
        releaseScope.launch { sessionMutex.withLock { closeLocked() } }
    }

    private fun openLocked(): Pair<OrtSession, WordPieceTokenizer> {
        releaseJob?.cancel()
        val existing = session
        val existingTokenizer = tokenizer
        if (existing != null && existingTokenizer != null) return existing to existingTokenizer
        require(modelFile.isFile) { "Embedding model is missing: ${modelFile.name}" }
        require(vocabFile.isFile) { "Embedding vocabulary is missing: ${vocabFile.name}" }
        val tk = WordPieceTokenizer.fromVocabFile(vocabFile, maxTokens)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val created = options.use { environment.createSession(modelFile.absolutePath, it) }
        try {
            require("input_ids" in created.inputNames && "attention_mask" in created.inputNames) {
                "Embedding graph must accept input_ids and attention_mask"
            }
            require("last_hidden_state" in created.outputNames) { "Embedding graph must output last_hidden_state" }
        } catch (e: Throwable) {
            created.close()
            throw e
        }
        hasTokenTypeIds = "token_type_ids" in created.inputNames
        session = created
        tokenizer = tk
        return created to tk
    }

    private fun runBatch(ortSession: OrtSession, batch: List<IntArray>): List<FloatArray> {
        val rows = batch.size
        val width = batch.maxOf { it.size }
        val ids = LongArray(rows * width)
        val mask = LongArray(rows * width)
        batch.forEachIndexed { r, tokens ->
            tokens.forEachIndexed { c, id ->
                ids[r * width + c] = id.toLong()
                mask[r * width + c] = 1L
            }
        }
        val shape = longArrayOf(rows.toLong(), width.toLong())
        val tensors = ArrayList<OnnxTensor>(3)
        try {
            val feed = LinkedHashMap<String, OnnxTensor>()
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).also { tensors += it; feed["input_ids"] = it }
            OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape).also { tensors += it; feed["attention_mask"] = it }
            if (hasTokenTypeIds) {
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(rows * width)), shape)
                    .also { tensors += it; feed["token_type_ids"] = it }
            }
            ortSession.run(feed, setOf("last_hidden_state")).use { result ->
                val output = result.get("last_hidden_state").orElse(null) as? OnnxTensor
                    ?: error("Embedding graph returned no last_hidden_state")
                val outShape = output.info.shape
                require(outShape.size == 3 && outShape[0] == rows.toLong() && outShape[2] == dimension.toLong()) {
                    "Unexpected embedding output shape ${outShape.contentToString()}"
                }
                val seq = outShape[1].toInt()
                val buffer = output.floatBuffer
                // CLS pooling (token 0), then L2 normalisation, as bge models are trained.
                return List(rows) { r ->
                    val vector = FloatArray(dimension)
                    val base = r * seq * dimension
                    for (d in 0 until dimension) vector[d] = buffer.get(base + d)
                    VectorEmbedding.l2Normalize(vector)
                }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        if (idleReleaseMillis <= 0L) {
            closeLocked()
            return
        }
        releaseJob = releaseScope.launch {
            delay(idleReleaseMillis)
            sessionMutex.withLock { closeLocked() }
        }
    }

    private fun closeLocked() {
        session?.close()
        session = null
        tokenizer = null
    }
}
