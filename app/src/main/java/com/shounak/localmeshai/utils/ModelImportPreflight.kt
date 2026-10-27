package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelDownloadSpec
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ModelTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Import admission checks export metadata before the download is registered or weights fetched.
 * Native kernel/operator compatibility and tensor correctness still require post-download validation.
 */
internal object ModelImportPreflight {
    private val defaultClient = OkHttpClient()
    suspend fun check(spec: ModelDownloadSpec, token: String?, baseClient: OkHttpClient = defaultClient): Unit = withContext(Dispatchers.IO) {
        val client = ModelDownloadUrlPolicy.configureClient(baseClient.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)) { token }.build()
        val format = spec.effectiveFormat
        val primary = spec.artifacts.firstOrNull() ?: error("No model artifact selected")
        val remote by lazy { RemoteModelMetadata(client, primary.downloadUrl, if (format == ModelFormat.Onnx) 4096L else 65536L) }
        if (!format.isPortable) {
            val header = remote.read(0, 8)
            val valid = when (format) {
                ModelFormat.GGUF -> String(header, 0, 4, Charsets.US_ASCII) == "GGUF"
                ModelFormat.LiteRtLm -> String(header, Charsets.US_ASCII) == "LITERTLM"
                ModelFormat.MediaPipeTask -> (0..4).any { offset -> header.sliceArray(offset until offset + 4).contentEquals(byteArrayOf(80,75,3,4)) }
                else -> true
            }
            require(valid) { "The source is not a ${format.name} model container. Choose the model's download file, rather than a web page or a raw export." }
            return@withContext
        }
        val tflite = if (format == ModelFormat.TensorFlowLite) {
            remote.read(0, 8)
            require(remote.length > 8) { "The source must report a model size for metadata inspection" }
            TfliteExportInspector.inspect(remote.length, remote::read).also { it.check(JSONObject()) }
        } else null
        if (format == ModelFormat.Safetensors) {
            val header = java.nio.ByteBuffer.wrap(remote.read(0, 8)).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            require(header in 2..1_048_576) { "Invalid or oversized safetensors metadata header" }
            JSONObject(remote.read(8, header.toInt()).toString(Charsets.UTF_8))
        }
        val artifacts = PortableCompanionFiles.complete(spec).associateBy { it.fileName }.toMutableMap()
        val available = spec.artifacts.filter { !it.optional }.map { it.fileName }.toMutableSet()
        val metadata = mutableMapOf<String, JSONObject>()
        fun json(name: String): JSONObject? {
            metadata[name]?.let { return it }
            val artifact = artifacts[name] ?: return null
            val call = client.newCall(Request.Builder().url(artifact.downloadUrl).build())
            call.execute().use { response ->
                if (response.code == 404) { available.remove(name); return null }
                if (!response.isSuccessful) throw IOException("Cannot inspect $name (HTTP ${response.code}); full model download has not started")
                if (response.body.contentLength() > 1_048_576) {
                    call.cancel()
                    throw IllegalArgumentException("Oversized $name")
                }
                val bytes = response.body.byteStream().use { it.readMetadataBytes(1_048_577) }
                if (bytes.size > 1_048_576) {
                    call.cancel()
                    throw IllegalArgumentException("Oversized $name")
                }
                return JSONObject(bytes.toString(Charsets.UTF_8)).also { available.add(name); metadata[name] = it }
            }
        }
        fun exists(name: String): Boolean {
            if (name in available) return true
            val artifact = artifacts[name] ?: return false
            client.newCall(Request.Builder().url(artifact.downloadUrl).head().build()).execute().use { response ->
                if (response.code == 404) return false
                if (response.isSuccessful) { available.add(name); return true }
                if (response.code !in setOf(405, 501)) throw IOException("Cannot check required $name (HTTP ${response.code})")
            }
            // Some direct file servers have no HEAD handler. Probe existence without
            // reading the tokenizer/weight body, including when Range is ignored.
            val call = client.newCall(Request.Builder().url(artifact.downloadUrl).header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity").build())
            call.execute().use { response ->
                try {
                    if (response.code == 404) return false
                    if (!response.isSuccessful) throw IOException("Cannot check required $name (HTTP ${response.code})")
                    available.add(name)
                    return true
                } finally { call.cancel() }
            }
        }
        val config = if (tflite?.config != null && artifacts["config.json"]?.optional != false) tflite.config
            else json("config.json") ?: tflite?.config ?: JSONObject()
        val pipeline = json("model_index.json")
        if (format == ModelFormat.Safetensors && pipeline == null &&
            spec.artifacts.count { it.fileName.endsWith(".safetensors") } > 1) {
            val index = artifacts.keys.firstOrNull { it.endsWith(".safetensors.index.json") }
                ?: throw MissingModelFilesException("Safetensors shards need their .safetensors.index.json")
            val weights = json(index)?.getJSONObject("weight_map")
                ?: throw MissingModelFilesException(index)
            require(weights.length() > 0) { "Empty safetensors weight index" }
            weights.keys().forEach { key ->
                val name = PortableBundlePath.normalize(weights.getString(key))
                require(name in available) { "Missing files: safetensors weight shard $name" }
            }
        }
        require(config.length() > 0 || pipeline != null) {
            if (format == ModelFormat.TensorFlowLite) "This TFLite export has no supported embedded task/processing metadata. A text export also needs its tokenizer and generation metadata, or the author's packaged .task/.litertlm version. config.json is not mandatory for self-contained supported classifiers."
            else "Missing files: config.json or pipeline model_index.json was not found. Select a complete supported model repository before downloading weights."
        }
        coroutineContext.ensureActive()
        tflite?.check(config)
        val task = PortableModelProfile.task(config, pipeline)
        PortableModelBundle.metadataRestriction(format, config, pipeline, ::exists,
            { name -> json(name) ?: throw MissingModelFilesException(name) })?.let { throw IllegalArgumentException(it.reason) }
        if (task in setOf(ModelTask.Chat, ModelTask.VisionLanguage) ||
            (task == ModelTask.SpeechRecognition && !PortableModelProfile.isCtc(config))) {
            require(exists("tokenizer.json") || (config.optString("model_type") == "gpt2" && exists("vocab.json") && exists("merges.txt"))) {
                "Missing files: this export needs tokenizer.json (GPT-2 may use vocab.json and merges.txt). Full model download has not started."
            }
            if (runCatching { PortableModelBundle.eosTokens(config) }.isFailure)
                PortableModelBundle.eosTokens(config, json("generation_config.json"))
        } else if (task == ModelTask.SpeechRecognition) {
            require(exists("vocab.json")) { "Missing files: CTC transcription requires vocab.json" }
        }
        // Reject unsupported metadata before probing a potentially large remote graph. Start
        // the graph's bounded inspection deadline here, after companion metadata requests.
        val onnx = if (format == ModelFormat.Onnx) {
            remote.read(0, 8)
            require(remote.length > 8) { "The source must report an ONNX graph size for metadata inspection" }
            OnnxExportInspector.inspectSignatures(remote.length, remote::read)
        } else null
        onnx?.check(task)
        onnx?.external?.forEach { name ->
            val directory = primary.fileName.substringBeforeLast('/', "")
            val relative = if (directory.isBlank()) name else "$directory/$name"
            if (relative !in artifacts) {
                val url = primary.downloadUrl.toHttpUrl()
                artifacts[relative] = com.shounak.localmeshai.models.DownloadArtifactSpec(
                    url.newBuilder().removePathSegment(url.pathSegments.lastIndex).addPathSegments(name).build().toString(), relative, optional = true)
            }
            require(exists(relative) || exists(name)) { "Missing files: ONNX external weights $relative must accompany this graph" }
        }
    }
}
