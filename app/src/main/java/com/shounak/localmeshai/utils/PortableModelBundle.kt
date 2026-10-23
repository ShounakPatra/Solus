package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ThinkingCapability
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The supported causal-LM contract is deliberately explicit; a file extension is not a task. */
internal object PortableModelBundle {
    private const val MAX_JSON = 32L * 1024 * 1024
    fun format(name: String): ModelFormat? = when (name.substringAfterLast('.').lowercase()) {
        "onnx" -> ModelFormat.Onnx
        "tflite" -> ModelFormat.TensorFlowLite
        "safetensors" -> ModelFormat.Safetensors
        else -> null
    }

    fun json(file: File): JSONObject {
        require(file.isFile && file.length() in 2..MAX_JSON) { "Missing or oversized ${file.name}" }
        return JSONObject(file.readText())
    }

    fun inspect(primary: File, expected: ModelFormat, companions: Map<String, File> = emptyMap(), logicalName: String = primary.name): ModelCapabilities {
        require(format(logicalName) == expected && primary.isFile && primary.length() > 8) { "Invalid model artifact" }
        val dir = requireNotNull(primary.parentFile)
        fun companion(name: String) = companions[name] ?: File(dir, name)
        val pipelineFile = companion("model_index.json")
        val pipeline = pipelineFile.takeIf { it.isFile }?.let(::json)
        val config = companion("config.json").takeIf { it.isFile }?.let(::json) ?: JSONObject()
        val task = PortableModelProfile.task(config, pipeline)
        val context = config.optInt("max_position_embeddings", config.optInt("n_positions", 2048))
        require(context in 2..1_048_576) { "Invalid model context length" }
        if (task == com.shounak.localmeshai.models.ModelTask.Chat || task == com.shounak.localmeshai.models.ModelTask.SpeechRecognition) {
            val tokenizerFile = companion("tokenizer.json")
            if (tokenizerFile.isFile) {
                val tokenizer = json(tokenizerFile)
                val vocab = tokenizer.getJSONObject("model").opt("vocab")
                require((vocab is JSONObject && vocab.length() > 0) || (vocab is org.json.JSONArray && vocab.length() > 0)) { "tokenizer.json must contain a nonempty Hugging Face vocabulary" }
            } else {
                require(companion("vocab.json").isFile && companion("merges.txt").isFile) { "Missing tokenizer.json or BPE vocab.json and merges.txt" }
                require(json(companion("vocab.json")).length() > 0) { "Empty BPE vocabulary" }
            }
            val generation = companion("generation_config.json").takeIf { it.isFile }?.let(::json)
            val eos = generation?.opt("eos_token_id") ?: config.opt("eos_token_id")
            require(eos is Number || eos is org.json.JSONArray) { "config.json or generation_config.json must declare eos_token_id" }
        }
        require(config.optString("solus_prompt_format") in setOf("", "chatml", "plain", "llama2", "llama3", "gemma", "phi3", "phi", "mistral", "completion")) { "Unsupported solus_prompt_format" }
        when (expected) {
            ModelFormat.Safetensors -> {
                val indexFile = companion("model.safetensors.index.json")
                require(pipeline != null || companions.keys.count { format(it) == ModelFormat.Safetensors } <= 1 || indexFile.isFile) {
                    "Missing model.safetensors.index.json for the downloaded weight shards"
                }
                val files = if (pipeline != null) {
                    companions.filterKeys { format(it) == ModelFormat.Safetensors }.values.toList().ifEmpty { listOf(primary) }
                } else if (indexFile.exists()) {
                    val map = json(indexFile).getJSONObject("weight_map")
                    require(map.length() > 0)
                    map.keys().asSequence().map { map.getString(it) }.distinct().map { name ->
                        require(File(name).name == name && !name.contains('\\') && !name.contains('/')) { "Invalid weight shard path" }
                        companion(name)
                    }.toList()
                } else listOf(primary)
                require(primary in files) { "Selected weights are not referenced by the safetensors index" }
                files.forEach(::validateSafetensors)
            }
            ModelFormat.TensorFlowLite -> RandomAccessFile(primary, "r").use {
                val header = ByteArray(8); it.readFully(header)
                require(String(header, 4, 4, Charsets.US_ASCII) == "TFL3") { "Invalid TensorFlow Lite header" }
            }
            ModelFormat.Onnx -> {
                OnnxBundleValidation.validate(primary, companions, logicalPath = logicalName)
                companions.filterKeys { it.endsWith(".onnx", true) }.forEach { (name, file) ->
                    if (file != primary) OnnxBundleValidation.validate(file, companions, logicalPath = name)
                }
            }
            else -> error("Not a portable model")
        }
        return ModelCapabilities(supportsText = task == com.shounak.localmeshai.models.ModelTask.Chat,
            supportsVision = task == com.shounak.localmeshai.models.ModelTask.ImageClassification,
            supportsAudio = task == com.shounak.localmeshai.models.ModelTask.SpeechRecognition,
            task = task, thinking = if (task == com.shounak.localmeshai.models.ModelTask.Chat)
                PortableThinking.capability(config, companion("tokenizer_config.json").takeIf { it.isFile }?.let(::json) ?: JSONObject())
                else ThinkingCapability.Unsupported, contextLength = context)
    }

    fun runtimeRestriction(primary: File, expected: ModelFormat, companions: Map<String, File> = emptyMap()): com.shounak.localmeshai.models.LoadRestriction? {
        fun file(name: String) = companions[name] ?: File(primary.parentFile, name)
        val config = file("config.json").takeIf { it.isFile }?.let(::json) ?: JSONObject()
        val pipeline = file("model_index.json").takeIf { it.isFile }?.let(::json)
        val task = PortableModelProfile.task(config, pipeline)
        val architecture = config.optString("model_type", "unknown")
        val reason = when {
            task == com.shounak.localmeshai.models.ModelTask.Unknown -> "Imported model has no supported task/architecture metadata. Supply its complete configuration and processing assets; weights alone are not an executable pipeline."
            expected == ModelFormat.TensorFlowLite && task != com.shounak.localmeshai.models.ModelTask.Chat -> "This TFLite task needs a separate media adapter."
            expected == ModelFormat.Safetensors && task == com.shounak.localmeshai.models.ModelTask.Chat && architecture !in PortableModelProfile.textArchitectures -> "Imported safetensors architecture '$architecture' needs its own inference adapter. Supported text families: ${PortableModelProfile.textArchitectures.joinToString()}."
            expected == ModelFormat.Safetensors && task == com.shounak.localmeshai.models.ModelTask.ImageClassification && architecture != "vit" -> "Safetensors image classification currently requires the ViT architecture; '$architecture' needs its own adapter."
            task == com.shounak.localmeshai.models.ModelTask.SpeechRecognition && !file("tokenizer.json").isFile -> "Whisper needs a complete tokenizer.json including its language and task special tokens."
            task == com.shounak.localmeshai.models.ModelTask.SpeechRecognition && expected == ModelFormat.Onnx &&
                listOf("encoder_model.onnx", "decoder_model.onnx").any { !file(it).isFile } -> "Whisper ONNX needs both encoder_model.onnx and the no-cache decoder_model.onnx with its tokenizer and configuration."
            expected == ModelFormat.Safetensors && config.has("quantization_config") && !config.isNull("quantization_config") -> "Imported prequantized safetensors layout needs a quantization-specific adapter."
            expected == ModelFormat.Safetensors && !config.isNull("rope_scaling") && architecture != "llama" -> "Imported scaled RoPE checkpoint cannot run in this architecture adapter."
            task == com.shounak.localmeshai.models.ModelTask.ImageGeneration && expected == ModelFormat.Safetensors &&
                listOf("text_encoder/config.json", "text_encoder/model.safetensors", "unet/diffusion_pytorch_model.safetensors", "vae/diffusion_pytorch_model.safetensors").any { name ->
                    !file(name).isFile && !file(name.replace("/", "--")).isFile
                } -> "Imported diffusion weights need the complete text_encoder, unet, vae and tokenizer folders. Import the whole Diffusers pipeline."
            task == com.shounak.localmeshai.models.ModelTask.ImageGeneration && pipeline?.optString("_class_name") !in setOf("StableDiffusionPipeline", "OnnxStableDiffusionPipeline", "ORTStableDiffusionPipeline") -> "Image generation requires a complete supported Diffusers pipeline, not an isolated UNet, VAE or LoRA checkpoint."
            task == com.shounak.localmeshai.models.ModelTask.ImageGeneration && expected == ModelFormat.Safetensors && pipeline?.optString("_class_name") != "StableDiffusionPipeline" -> "Safetensors diffusion needs a Diffusers StableDiffusionPipeline checkpoint."
            task == com.shounak.localmeshai.models.ModelTask.ImageGeneration && listOf("text_encoder/model.onnx", "unet/model.onnx", "vae_decoder/model.onnx").any { !file(it).isFile } && expected == ModelFormat.Onnx -> "ONNX image generation needs the complete text_encoder, unet and vae_decoder component graphs."
            task == com.shounak.localmeshai.models.ModelTask.ImageGeneration &&
                !file("tokenizer/tokenizer.json").isFile && !(file("tokenizer/vocab.json").isFile && file("tokenizer/merges.txt").isFile) -> "Image generation needs the pipeline's CLIP tokenizer assets."
            task == com.shounak.localmeshai.models.ModelTask.ImageGeneration && !file("scheduler/scheduler_config.json").isFile -> "Image generation needs scheduler/scheduler_config.json to interpret the denoiser output."
            task == com.shounak.localmeshai.models.ModelTask.ImageGeneration && !supportsDiffusionSchedule(json(file("scheduler/scheduler_config.json"))) -> "This image pipeline uses a noise schedule or prediction type outside the supported SD1/SD2 DDIM adapter."
            else -> return null
        }
        return com.shounak.localmeshai.models.LoadRestriction(reason, com.shounak.localmeshai.models.LoadRestrictionCategory.RuntimeIncompatibility)
    }

    internal fun supportsDiffusionSchedule(config: JSONObject): Boolean =
        config.optInt("num_train_timesteps", 1000) == 1000 &&
        config.optString("beta_schedule", "scaled_linear") == "scaled_linear" &&
        kotlin.math.abs(config.optDouble("beta_start", 0.00085) - 0.00085) < 1e-9 &&
        kotlin.math.abs(config.optDouble("beta_end", 0.012) - 0.012) < 1e-9 &&
        config.optString("prediction_type", "epsilon") in setOf("epsilon", "v_prediction") &&
        !config.optBoolean("clip_sample", false) && !config.optBoolean("set_alpha_to_one", false)

    /** Refresh version-dependent runtime policy using small metadata files, never tensor contents. */
    fun refreshRuntimeMetadata(bundle: com.shounak.localmeshai.models.ModelBundle): com.shounak.localmeshai.models.ModelBundle {
        if (!bundle.format.isPortable) return bundle
        return runCatching {
            val primary = bundle.mainArtifacts.firstOrNull()?.source as? com.shounak.localmeshai.models.ArtifactSource.AppManaged ?: return bundle
            val file = File(primary.path)
            val root = PortableModelProfile.root(file)
            val configFile = File(root, "config.json")
            val pipelineFile = File(root, "model_index.json")
            if (!configFile.isFile && !pipelineFile.isFile) return bundle
            val config = configFile.takeIf { it.isFile }?.let(::json) ?: JSONObject()
            val task = PortableModelProfile.task(config, pipelineFile.takeIf { it.isFile }?.let(::json))
            val companions = bundle.mainArtifacts.mapNotNull { artifact ->
                val source = artifact.source as? com.shounak.localmeshai.models.ArtifactSource.AppManaged ?: return@mapNotNull null
                val component = File(source.path)
                component.relativeTo(root).invariantSeparatorsPath to component
            }.toMap()
            val old = bundle.loadRestriction
            val restriction = if (old == null || old.category == com.shounak.localmeshai.models.LoadRestrictionCategory.RuntimeIncompatibility)
                runtimeRestriction(file, bundle.format, companions) else old
            bundle.copy(capabilities = bundle.capabilities.copy(task = task,
                thinking = if (task == com.shounak.localmeshai.models.ModelTask.Chat) PortableThinking.capability(config,
                    File(root, "tokenizer_config.json").takeIf { it.isFile }?.let(::json) ?: JSONObject()) else ThinkingCapability.Unsupported,
                supportsText = task == com.shounak.localmeshai.models.ModelTask.Chat,
                supportsVision = task == com.shounak.localmeshai.models.ModelTask.ImageClassification,
                supportsAudio = task == com.shounak.localmeshai.models.ModelTask.SpeechRecognition), loadRestriction = restriction)
        }.getOrDefault(bundle)
    }

    fun validateSafetensors(file: File) {
        require(file.isFile && file.length() > 8) { "Missing safetensors shard: ${file.name}" }
        RandomAccessFile(file, "r").use { input ->
            val prefix = ByteArray(8); input.readFully(prefix)
            val length = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).long
            require(length in 2..MAX_JSON && length <= input.length() - 8) { "Invalid safetensors header length" }
            val header = ByteArray(length.toInt()); input.readFully(header)
            val metadata = JSONObject(String(header, Charsets.UTF_8))
            var tensors = 0
            metadata.keys().forEach { name ->
                if (name != "__metadata__") {
                    val tensor = metadata.getJSONObject(name)
                    val offsets = tensor.getJSONArray("data_offsets")
                    val start = offsets.getLong(0); val end = offsets.getLong(1)
                    require(start >= 0 && end >= start && end <= input.length() - 8 - length) { "Truncated safetensors tensor $name" }
                    require(tensor.has("dtype") && tensor.has("shape")) { "Invalid safetensors tensor $name" }
                    tensors++
                }
            }
            require(tensors > 0) { "Safetensors file contains no tensors" }
        }
    }
}
