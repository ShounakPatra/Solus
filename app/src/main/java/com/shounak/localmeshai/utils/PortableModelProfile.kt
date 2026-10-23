package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelTask
import org.json.JSONObject
import java.io.File

internal object PortableModelProfile {
    val textArchitectures = setOf("gpt2", "qwen2", "llama", "mistral", "gemma", "phi", "phi3")
    fun isCtc(config: JSONObject): Boolean = config.optString("solus_audio_decoder") == "ctc" ||
        config.optJSONArray("architectures")?.toString()?.contains("ForCTC") == true
    fun task(config: JSONObject, pipeline: JSONObject? = null): ModelTask {
        if (pipeline != null) return if (pipeline.optString("_class_name") in setOf("StableDiffusionPipeline", "OnnxStableDiffusionPipeline", "ORTStableDiffusionPipeline")) ModelTask.ImageGeneration else ModelTask.Unknown
        if (isCtc(config)) return ModelTask.SpeechRecognition
        if (config.optString("solus_task") == "vision_language") return ModelTask.VisionLanguage
        return when (config.optString("model_type")) {
            "paligemma", "llava", "qwen2_vl", "smolvlm" -> ModelTask.VisionLanguage
            "vit" -> ModelTask.ImageClassification
            "whisper" -> ModelTask.SpeechRecognition
            in textArchitectures -> ModelTask.Chat
            else -> when (config.optString("solus_task")) {
                "image_classification" -> ModelTask.ImageClassification
                "speech_recognition" -> ModelTask.SpeechRecognition
                "image_generation" -> ModelTask.ImageGeneration
                "text_generation" -> ModelTask.Chat
                else -> when {
                    config.optJSONArray("architectures")?.toString()?.contains("ForImageClassification") == true -> ModelTask.ImageClassification
                    config.optJSONArray("architectures")?.toString()?.contains("ForCausalLM") == true || config.has("eos_token_id") -> ModelTask.Chat
                    else -> ModelTask.Unknown
                }
            }
        }
    }
    fun root(primary: File): File {
        var directory = requireNotNull(primary.parentFile)
        repeat(4) {
            if (File(directory, "model_index.json").isFile) return directory
            directory = directory.parentFile ?: return@repeat
        }
        directory = requireNotNull(primary.parentFile)
        repeat(4) {
            if (File(directory, "config.json").isFile) return directory
            directory = directory.parentFile ?: return@repeat
        }
        return requireNotNull(primary.parentFile)
    }
    fun component(root: File, name: String): File {
        require(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.contains('\\') })
        val nested = File(root, name)
        return if (nested.isFile) nested else File(root, name.replace("/", "--"))
    }
    fun weights(primary: File): Array<String> {
        val index = File(primary.parentFile, "model.safetensors.index.json")
        if (!index.isFile) return arrayOf(primary.absolutePath)
        val map = PortableModelBundle.json(index).getJSONObject("weight_map")
        return map.keys().asSequence().map { key ->
            val name = map.getString(key)
            require(name == File(name).name && name !in setOf(".", "..") && !name.contains('\\') && !name.contains('/')) { "Unsafe weight shard path" }
            File(primary.parentFile, name).absolutePath
        }.distinct().toList().toTypedArray()
    }
}
