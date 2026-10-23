package com.shounak.localmeshai.ai

import com.shounak.localmeshai.models.ModelTask
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import com.shounak.localmeshai.utils.PortableModelBundle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

/** Uses the production tokenizer, prompt renderer and decoder; samples independent conversations. */
internal object PortableBenchmarkRunner {
    suspend fun run(file: File, modelId: String, progress: ((BenchmarkProgress) -> Unit)?): BenchmarkResult =
        runLocked(file, modelId, progress)
    private suspend fun runLocked(file: File, modelId: String, progress: ((BenchmarkProgress) -> Unit)?): BenchmarkResult {
        val format = requireNotNull(PortableModelBundle.format(file.name))
        require(PortableModelBundle.inspect(file, format).task == ModelTask.Chat) {
            "This benchmark measures text generation. Image, audio and diffusion pipelines need task-specific benchmarks."
        }
        PortableModelBundle.runtimeRestriction(file, format)?.let { error(it.reason) }
        return BenchmarkAdmission.run(modelId) {
                val prompts = listOf("The capital of France is", "Explain gravity briefly.", "Write a short greeting.")
                val ttfts = mutableListOf<Double>()
                val rates = mutableListOf<Double>()
                var cold = 0.0
                fun now() = System.nanoTime()
                suspend fun sample(engine: PortableTextEngine, prompt: String): Pair<Double, Double> {
                    val start = now()
                    var first = 0L
                    var last = 0L
                    var tokens = 0
                    engine.generate(engine.renderPrompt(listOf("user" to prompt), prompt, ""), 32,
                        0f, 1f, 1, {}, { count ->
                            val time = now()
                            if (first == 0L) first = time
                            last = time; tokens = count
                        })
                    currentCoroutineContext().ensureActive()
                    check(tokens > 0) { "Model generated no tokens; no valid benchmark sample" }
                    return PortableBenchmarkMetrics.sample(start, first, last, tokens)
                }
                progress?.invoke(BenchmarkProgress("Cold load", 0, 4))
                val coldStart = now()
                PortableTextEngine.open(file, format, 2048).use { engine ->
                    val beforeSample = now()
                    val first = sample(engine, prompts[0])
                    cold = (beforeSample - coldStart) / 1e6 + first.first
                }
                PortableTextEngine.open(file, format, 2048).use { engine ->
                    prompts.forEachIndexed { index, prompt ->
                        progress?.invoke(BenchmarkProgress("Warm generation", index + 1, 4, prompt))
                        val sample = sample(engine, prompt)
                        ttfts.add(sample.first); rates.add(sample.second)
                    }
                }
                progress?.invoke(BenchmarkProgress("Complete", 4, 4))
                val rate = rates.sorted()[rates.size / 2]
                BenchmarkResult(false,
                    "${format.backendLabel}: CPU text generation, ${String.format(Locale.US, "%.1f", rate)} tokens/s. UI frame timing is not reported for this benchmark.",
                    ttfts.sorted()[ttfts.size / 2], 0.0, 0.0, cold, 0.0, 0.0,
                    rate, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, uiFramesMeasured = false)
        }
    }
}
