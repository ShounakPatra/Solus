package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelTask
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** A conservative memory estimate, not a claim that a chipset or model is incompatible. */
internal object ModelMemoryAssessment {
    private const val GIB = 1073741824.0
    data class Estimate(val requiredGiB: Double, val source: String)
    private data class HeaderKey(val path: String, val size: Long, val modified: Long)
    private val headerCache = object : LinkedHashMap<HeaderKey, Double?>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HeaderKey, Double?>?) = size > 32
    }

    fun estimate(model: ModelInfo, contextTokens: Int? = null): Estimate? {
        val format = model.bundle?.format ?: model.downloadSpec?.effectiveFormat ?: ModelFormat.fromFileName(model.fileName) ?: return null
        val artifacts = model.bundle?.allArtifacts.orEmpty().filter { artifact ->
            val name = artifact.displayName.lowercase(Locale.ROOT)
            !name.endsWith(".json") && !name.endsWith(".txt") && !name.endsWith(".model")
        }
        val local = artifacts.mapNotNull { (it.source as? ArtifactSource.AppManaged)?.path?.let(::File) }
            .ifEmpty { model.localPath?.let { listOf(File(it)) } ?: emptyList() }
        val recordedBytes = artifacts.sumOf { artifact ->
            val file = (artifact.source as? ArtifactSource.AppManaged)?.path?.let(::File)
            if (file?.isFile == true) file.length().toDouble() else artifact.sizeBytes.coerceAtLeast(0).toDouble()
        }
        val specBytes = model.downloadSpec?.totalExpectedBytes ?: 0L
        val bytes = when {
            recordedBytes > 0 -> recordedBytes
            local.isNotEmpty() && local.all { it.isFile } -> local.sumOf { it.length().toDouble() }
            specBytes > 0 -> specBytes.toDouble()
            model.expectedFileSizeBytes > 0 -> model.expectedFileSizeBytes.toDouble()
            model.downloadedBytes > 0 && model.progress >= 1f -> model.downloadedBytes.toDouble()
            else -> sizeBytes(model.size) ?: return null
        }
        if (!bytes.isFinite() || bytes <= 0) return null
        val measured = recordedBytes > 0 || local.any { it.isFile }
        val sfFiles = local.filter { it.name.endsWith(".safetensors", true) }
        val expanded = if (format == ModelFormat.Safetensors && sfFiles.size in 1..16) {
            val sizes = sfFiles.map(::float32Bytes)
            if (sizes.all { it != null }) sizes.filterNotNull().sum() else null
        } else null
        val weights = if (expanded != null) expanded / GIB else bytes / GIB * when (format) {
            ModelFormat.Safetensors -> 2.0 // Candle loads float weights as FP32; header data takes precedence.
            else -> 1.0
        }
        val factor = when (format) {
            ModelFormat.GGUF -> 1.15
            ModelFormat.TensorFlowLite, ModelFormat.Safetensors -> 1.25
            else -> 1.5
        }
        val media = when (model.bundle?.capabilities?.task ?: model.downloadSpec?.capabilities?.task) {
            ModelTask.ImageGeneration -> 1.5
            ModelTask.VisionLanguage, ModelTask.ImageClassification -> .5
            ModelTask.SpeechRecognition -> .25
            else -> if (model.isMultimodal) .5 else 0.0
        }
        // Reserve for interpreter/workspace and a modest context cache. It is deliberately labelled an estimate.
        val config = local.firstOrNull()?.parentFile?.resolve("config.json")?.takeIf { it.isFile }
            ?.let { runCatching { PortableModelBundle.json(it) }.getOrNull() }
        val text = config?.optJSONObject("text_config") ?: config
        val context = (contextTokens ?: model.bundle?.capabilities?.contextLength ?: model.downloadSpec?.capabilities?.contextLength ?: 2048).coerceIn(2, 1_048_576)
        val layers = text?.optInt("num_hidden_layers", text.optInt("n_layer", 0)) ?: 0
        val heads = text?.optInt("num_attention_heads", text.optInt("n_head", 0)) ?: 0
        val kvHeads = text?.optInt("num_key_value_heads", heads) ?: heads
        val hidden = text?.optInt("hidden_size", text.optInt("n_embd", 0)) ?: 0
        val headDim = text?.optInt("head_dim", if (heads > 0) hidden / heads else 0) ?: 0
        // FP32 caches for portable CPU runtimes; other backends may use smaller caches.
        val cache = if (layers > 0 && kvHeads > 0 && headDim > 0)
            2.0 * layers * kvHeads * headDim * context * 4 / GIB else .15 * context / 2048.0
        return Estimate(weights * factor + .6 + media + cache,
            if (expanded != null) "tensor shapes and FP32 runtime weights" else if (measured) "model artifact sizes and runtime overhead" else "declared download size and runtime overhead")
    }

    fun check(totalRamGiB: Double, model: ModelInfo, contextTokens: Int? = null): Pair<Boolean, String> {
        if (!totalRamGiB.isFinite() || totalRamGiB <= 0) return true to ""
        val estimate = estimate(model, contextTokens) ?: return true to "" // Unknown is not evidence of incompatibility.
        val budget = (totalRamGiB - maxOf(1.0, totalRamGiB * .2)).coerceAtLeast(0.0)
        if (estimate.requiredGiB <= budget) return true to ""
        return false to String.format(Locale.US,
            "Memory estimate: about %.1f GiB for this model, based on %s. This device has %.1f GiB total RAM; the model budget is %.1f GiB after a system reserve. Loading is blocked by default, but this is an estimate, not a verified incompatibility. Reduce context or close other apps, then use Try Anyway if you want to attempt loading.",
            estimate.requiredGiB, estimate.source, totalRamGiB, budget)
    }

    private fun sizeBytes(text: String): Double? {
        val match = Regex("""(?i)^\s*~?(\d+(?:\.\d+)?)\s*(KiB|MiB|GiB|KB|MB|GB)\b""").find(text) ?: return null
        val units = match.groupValues[2].lowercase(Locale.ROOT)
        val base = if ('i' in units) 1024.0 else 1000.0
        val power = when (units.first()) { 'k' -> 1; 'm' -> 2; else -> 3 }
        return match.groupValues[1].toDoubleOrNull()?.times(Math.pow(base, power.toDouble()))
    }

    /** Reads at most 1 MiB of immutable published headers, never tensor payloads. */
    @Synchronized private fun float32Bytes(file: File): Double? {
        if (!file.isFile) return null
        val key = HeaderKey(file.absolutePath, file.length(), file.lastModified())
        if (headerCache.containsKey(key)) return headerCache[key]
        val bytes = runCatching {
            RandomAccessFile(file, "r").use { input ->
                val sizeBytes = ByteArray(8); input.readFully(sizeBytes)
                val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).long
                require(size in 2..1048576 && size <= file.length() - 8)
                val header = ByteArray(size.toInt()); input.readFully(header)
                val json = JSONObject(header.toString(Charsets.UTF_8))
                val result = json.keys().asSequence().filter { it != "__metadata__" }.sumOf { name ->
                    val shape = json.getJSONObject(name).getJSONArray("shape")
                    var elements = 1.0
                    for (i in 0 until shape.length()) { val dim = shape.getLong(i); require(dim >= 0); elements *= dim }
                    elements * 4.0
                }
                require(result.isFinite() && result in 1.0..(4096 * GIB))
                result
            }
        }.getOrNull()
        headerCache[key] = bytes
        return bytes
    }
}
