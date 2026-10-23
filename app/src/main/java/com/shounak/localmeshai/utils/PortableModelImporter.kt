package com.shounak.localmeshai.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.shounak.localmeshai.models.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale
import java.util.UUID

internal object PortableModelImporter {
    fun names(context: Context, uris: List<Uri>): List<String> = uris.map { uri ->
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    }

    /** Copy all companions into one unpublished generation; do not depend on neighbouring SAF access. */
    suspend fun import(context: Context, uris: List<Uri>, names: List<String>, progress: ((String) -> Unit)?): LocalModelImporter.ImportResult {
        val modelNames = names.filter { PortableModelBundle.format(it) != null }
        require(modelNames.isNotEmpty()) { "Select a supported model and any processing files required by its export" }
        val format = requireNotNull(PortableModelBundle.format(modelNames.first()))
        require(modelNames.all { PortableModelBundle.format(it) == format }) { "Select one model format at a time" }
        require(format != ModelFormat.TensorFlowLite || modelNames.size == 1) { "Select a single TFLite graph" }
        require(names.map { it.lowercase(Locale.ROOT) }.distinct().size == names.size) { "Duplicate model filenames" }
        require(names.all { it == ModelArtifactStorage.sanitizeFileName(it) }) { "Model filenames must not contain directory paths" }
        val primaryName = modelNames.firstOrNull { it == "decoder_model.onnx" } ?: modelNames.sorted().first()
        val staging = ModelDownloader.defaultStorage(context).getStagingDir("draft_${UUID.randomUUID()}")
        staging.mkdirs()
        try {
            val items = uris.indices.map { index ->
                val target = File(staging, names[index])
                progress?.invoke("Copying ${names[index]} (${index + 1}/${uris.size})")
                context.contentResolver.openInputStream(uris[index])?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: error("Cannot read ${names[index]}")
                StagedArtifactItem(target, target.name, isPrimary = target.name == primaryName, order = index)
            }.sortedBy { if (it.isPrimary) 0 else 1 }.mapIndexed { index, item -> item.copy(order = index) }
            val primary = File(staging, primaryName)
            require(format != ModelFormat.Safetensors || modelNames.size == 1 || File(staging, "model.safetensors.index.json").isFile) { "Select model.safetensors.index.json with the weight shards" }
            val companions = items.associate { it.relativeTargetName to it.stagedFile }
            val capabilities = PortableModelBundle.inspect(primary, format, companions)
            require(format != ModelFormat.Onnx || modelNames.size == 1 || capabilities.task == ModelTask.SpeechRecognition) { "Use a complete pipeline archive for multiple component graphs" }
            val id = LocalModelImporter.generateStorageModelId(uris.map(Uri::toString))
            val artifacts = items.map { ModelArtifact(ArtifactRole.Main, it.relativeTargetName, ArtifactSource.AppManaged(it.stagedFile.absolutePath), it.stagedFile.length(), it.stagedFile.lastModified()) }
            return LocalModelImporter.ImportResult(
                bundle = ModelBundle(id, primary.nameWithoutExtension, artifacts, capabilities = capabilities,
                    description = "${format.backendLabel} ${capabilities.task.id} model imported with processing assets.", backend = format.backendLabel, format = format,
                    loadRestriction = PortableModelBundle.runtimeRestriction(primary, format, companions)),
                newlyAcquiredGrants = emptyList(), stagedArtifactItem = items.first(), draftStagingDir = staging,
                stagedArtifactItems = items
            )
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }
}
