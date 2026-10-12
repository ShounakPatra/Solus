package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ai.InferenceMetrics

/**
 * Monotonic request timings; contains no prompt or response content.
 */
class InferenceTiming(private val clock: () -> Long = System::nanoTime) {
    val sentAt: Long = clock()
    val requestAcceptedAt: Long get() = sentAt

    var acknowledgedAt: Long? = null
        private set
    var preparationStartedAt: Long? = null
        private set
    var preparationFinishedAt: Long? = null
        private set
    var queueStartedAt: Long? = null
        private set
    var queueFinishedAt: Long? = null
        private set
    var loadingStartedAt: Long? = null
        private set
    var loadingFinishedAt: Long? = null
        private set
    var startedAt: Long? = null
        private set
    var firstChunkAt: Long? = null
        private set
    var firstTokenAt: Long? = null
        private set
    var firstVisibleTextAt: Long? = null
        private set
    var generationFinishedAt: Long? = null
        private set

    @Synchronized
    fun recordAcknowledged() {
        if (acknowledgedAt == null) acknowledgedAt = clock()
    }

    @Synchronized
    fun startPreparation() {
        if (preparationStartedAt == null) preparationStartedAt = clock()
    }

    @Synchronized
    fun endPreparation() {
        if (preparationFinishedAt == null) preparationFinishedAt = clock()
    }

    @Synchronized
    fun startQueue() {
        if (queueStartedAt == null) queueStartedAt = clock()
    }

    @Synchronized
    fun endQueue() {
        if (queueFinishedAt == null) queueFinishedAt = clock()
    }

    @Synchronized
    fun startLoading() {
        if (loadingStartedAt == null) loadingStartedAt = clock()
    }

    @Synchronized
    fun endLoading() {
        if (loadingFinishedAt == null) loadingFinishedAt = clock()
    }

    @Synchronized
    fun startInference() {
        val now = clock()
        if (startedAt == null) startedAt = now
        if (preparationFinishedAt == null && preparationStartedAt != null) preparationFinishedAt = now
        if (queueFinishedAt == null && queueStartedAt != null) queueFinishedAt = now
        if (loadingFinishedAt == null && loadingStartedAt != null) loadingFinishedAt = now
    }

    @Synchronized
    fun recordFirstToken() {
        if (firstTokenAt == null) firstTokenAt = clock()
    }

    @Synchronized
    fun recordFirstVisibleText() {
        val now = clock()
        if (firstVisibleTextAt == null) firstVisibleTextAt = now
    }

    @Synchronized
    fun recordChunk(text: String) {
        if (text.isNotEmpty() && firstChunkAt == null) firstChunkAt = clock()
    }

    @Synchronized
    fun endGeneration() {
        if (generationFinishedAt == null) generationFinishedAt = clock()
    }

    @Synchronized
    fun buildMetrics(
        requestId: String,
        sessionId: String,
        attempt: Int = 1,
        generatedTokens: Int? = null,
        isTokenCountEstimated: Boolean = false,
        nativeDecodeDurationMs: Long? = null,
        prefillDurationMs: Long? = null,
        promptTokens: Int? = null,
        reusedTokens: Int? = null,
        evaluatedTokens: Int? = null
    ): InferenceMetrics {
        val now = clock()
        val ackMs = acknowledgedAt?.let { (it - sentAt) / 1_000_000 }
            ?: ((now - sentAt) / 1_000_000)
        val prepMs = if (preparationStartedAt != null && preparationFinishedAt != null) {
            (preparationFinishedAt!! - preparationStartedAt!!) / 1_000_000
        } else if (startedAt != null) {
            (startedAt!! - sentAt) / 1_000_000
        } else 0L

        val queueMs = if (queueStartedAt != null && queueFinishedAt != null) {
            (queueFinishedAt!! - queueStartedAt!!) / 1_000_000
        } else 0L

        val loadMs = if (loadingStartedAt != null && loadingFinishedAt != null) {
            (loadingFinishedAt!! - loadingStartedAt!!) / 1_000_000
        } else 0L

        val firstTokenLatencyMs = firstTokenAt?.let { (it - sentAt) / 1_000_000 }
        val firstVisibleTextLatencyMs = firstVisibleTextAt?.let { (it - sentAt) / 1_000_000 }

        val decodeDurationMs = nativeDecodeDurationMs ?: run {
            val genEnd = generationFinishedAt ?: now
            val start = firstTokenAt ?: firstVisibleTextAt ?: firstChunkAt
            if (start != null && genEnd >= start) {
                (genEnd - start) / 1_000_000
            } else null
        }

        val throughput = if (generatedTokens != null && generatedTokens > 0 && decodeDurationMs != null && decodeDurationMs > 0) {
            generatedTokens.toDouble() / (decodeDurationMs.toDouble() / 1000.0)
        } else null

        return InferenceMetrics(
            requestId = requestId,
            sessionId = sessionId,
            attempt = attempt,
            ackLatencyMs = ackMs,
            preparationTimeMs = prepMs,
            queueTimeMs = queueMs,
            loadingValidationTimeMs = loadMs,
            firstTokenLatencyMs = firstTokenLatencyMs,
            firstVisibleTextLatencyMs = firstVisibleTextLatencyMs,
            prefillDurationMs = prefillDurationMs,
            decodeDurationMs = decodeDurationMs,
            generatedTokens = generatedTokens,
            isTokenCountEstimated = isTokenCountEstimated,
            decodeThroughputTokPerSec = throughput,
            promptTokens = promptTokens,
            reusedTokens = reusedTokens,
            evaluatedTokens = evaluatedTokens
        )
    }

    fun summary(): String {
        val first = firstVisibleTextAt
        val start = startedAt
        return "prepareMs=${start?.let { (it - sentAt) / 1_000_000 } ?: -1} " +
            "firstTextMs=${first?.let { (it - sentAt) / 1_000_000 } ?: -1} " +
            "prefillMs=${if (first != null && start != null) (first - start) / 1_000_000 else -1} " +
            "totalMs=${(clock() - sentAt) / 1_000_000}"
    }

    fun detailedSummary(
        requestId: String,
        sessionId: String,
        attempt: Int = 1,
        generatedTokens: Int? = null,
        isTokenCountEstimated: Boolean = false,
        nativeDecodeDurationMs: Long? = null,
        prefillDurationMs: Long? = null,
        promptTokens: Int? = null,
        reusedTokens: Int? = null,
        evaluatedTokens: Int? = null
    ): String {
        val m = buildMetrics(
            requestId = requestId,
            sessionId = sessionId,
            attempt = attempt,
            generatedTokens = generatedTokens,
            isTokenCountEstimated = isTokenCountEstimated,
            nativeDecodeDurationMs = nativeDecodeDurationMs,
            prefillDurationMs = prefillDurationMs,
            promptTokens = promptTokens,
            reusedTokens = reusedTokens,
            evaluatedTokens = evaluatedTokens
        )
        return formatMetricsSummary(m)
    }

    fun formatMetricsSummary(m: InferenceMetrics): String {
        val promptStr = if (m.promptTokens != null) "promptToks=${m.promptTokens} " else ""
        val reusedStr = if (m.reusedTokens != null) "reusedToks=${m.reusedTokens} " else ""
        val evalStr = if (m.evaluatedTokens != null) "evalToks=${m.evaluatedTokens} " else ""
        val prefillStr = if (m.prefillDurationMs != null) "prefillMs=${m.prefillDurationMs} " else ""

        return "req=${m.requestId} ackMs=${m.ackLatencyMs} prepMs=${m.preparationTimeMs} queueMs=${m.queueTimeMs} " +
            "loadMs=${m.loadingValidationTimeMs} ${prefillStr}${promptStr}${reusedStr}${evalStr}" +
            "firstTokMs=${m.firstTokenLatencyMs ?: -1} firstVisMs=${m.firstVisibleTextLatencyMs ?: -1} " +
            "decodeMs=${m.decodeDurationMs ?: -1} toks=${m.generatedTokens ?: 0} " +
            "tok/s=${m.decodeThroughputTokPerSec?.let { "%.1f".format(it) } ?: "N/A"}"
    }
}
