package com.shounak.localmeshai.utils

import android.content.Context
import android.net.Uri
import com.shounak.localmeshai.models.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Complete pipelines keep directory structure. Repository code is never executed. */
internal object PortableModelArchive {
    internal suspend fun extract(input: java.io.InputStream, staging: File, progress: ((String) -> Unit)? = null): Map<String, File> {
        val files = linkedMapOf<String, File>()
        val seen = mutableSetOf<String>()
        var bytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                require(seen.size < 50_000) { "Model archive has too many entries" }
                val name = PortableBundlePath.normalize(entry.name.trimEnd('/'))
                require(seen.add(name.lowercase(java.util.Locale.ROOT))) { "Duplicate archive component: $name" }
                val target = File(staging, name).canonicalFile
                require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Unsafe archive entry" }
                if (entry.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                progress?.invoke("Extracting $name")
                target.outputStream().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        bytes += read
                        require(bytes <= 128L * 1024 * 1024 * 1024) { "Model archive exceeds the extraction limit" }
                        output.write(buffer, 0, read)
                    }
                }
                files[name] = target
            }
        }
        return files
    }

    suspend fun import(context: Context, uri: Uri, progress: ((String) -> Unit)?): LocalModelImporter.ImportResult {
        val staging = ModelDownloader.defaultStorage(context).getStagingDir("draft_${UUID.randomUUID()}")
        staging.mkdirs()
        try {
            val files = context.contentResolver.openInputStream(uri)?.use { extract(it, staging, progress) }
                ?: error("Cannot read model archive")
            val pipeline = files.entries.singleOrNull { it.key.substringAfterLast('/') == "model_index.json" }
            val root = pipeline?.value?.parentFile ?: files.entries.firstOrNull { it.key.substringAfterLast('/') == "config.json" }?.value?.parentFile
                ?: error("The model archive must include config.json or model_index.json")
            val components = files.values.filter { it.toPath().startsWith(root.toPath()) }.associateBy { it.relativeTo(root).invariantSeparatorsPath }
            val isOnnxPipeline = pipeline?.value?.let(PortableModelBundle::json)?.optString("_class_name")?.let { it.contains("Onnx") || it.startsWith("ORT") } == true
            val primary = if (pipeline != null) {
                if (isOnnxPipeline) listOf("unet/model.onnx", "unet/model_quantized.onnx").firstNotNullOfOrNull { components[it] }
                else components["unet/diffusion_pytorch_model.safetensors"]
            } else listOf("model.safetensors", "decoder_model.onnx", "model.onnx").firstNotNullOfOrNull { components[it] }
                ?: components.entries.firstOrNull { PortableModelBundle.format(it.key) != null }?.value
            requireNotNull(primary) { "No supported primary model component found" }
            val format = requireNotNull(PortableModelBundle.format(primary.name))
            val name = primary.relativeTo(root).invariantSeparatorsPath
            val capabilities = PortableModelBundle.inspect(primary, format, components, name)
            val ordered = components.entries.sortedWith(compareBy({ it.value != primary }, { it.key }))
            val items = ordered.mapIndexed { index, (path, file) -> StagedArtifactItem(file, path, isPrimary = index == 0, order = index) }
            val id = LocalModelImporter.generateStorageModelId(listOf(uri.toString()))
            val artifacts = items.map { ModelArtifact(ArtifactRole.Main, it.relativeTargetName,
                ArtifactSource.AppManaged(it.stagedFile.absolutePath), it.stagedFile.length(), it.stagedFile.lastModified()) }
            return LocalModelImporter.ImportResult(
                ModelBundle(id, root.name, artifacts, capabilities = capabilities, format = format,
                    backend = format.backendLabel, description = "${capabilities.task.id} pipeline imported with its processing assets.",
                    loadRestriction = PortableModelBundle.runtimeRestriction(primary, format, components)), emptyList(),
                stagedArtifactItem = items.first(), draftStagingDir = staging, stagedArtifactItems = items)
        } catch (failure: Throwable) { staging.deleteRecursively(); throw failure }
    }
}
