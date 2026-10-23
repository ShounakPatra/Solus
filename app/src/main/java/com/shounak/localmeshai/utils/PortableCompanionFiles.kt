package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.DownloadArtifactSpec
import com.shounak.localmeshai.models.ModelDownloadSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Persistent message prefix also lets older saved failure snapshots render accurately. */
internal class MissingModelFilesException(detail: String) : IllegalArgumentException("Missing files: $detail")

internal class ModelDownloadHttpException(val statusCode: Int, message: String) : IOException(message)

internal object PortableCompanionFiles {
    val names = listOf("config.json", "tokenizer.json", "tokenizer_config.json", "generation_config.json",
        "special_tokens_map.json", "preprocessor_config.json", "processor_config.json", "vocab.json", "merges.txt")

    /** Repair old weight-only specs as well as new direct imports, without changing the weight URL. */
    fun complete(spec: ModelDownloadSpec): List<DownloadArtifactSpec> {
        if (!spec.effectiveFormat.isPortable || spec.artifacts.any { it.fileName == "model_index.json" }) return spec.artifacts
        val primary = spec.artifacts.firstOrNull() ?: return spec.artifacts
        val url = primary.downloadUrl.toHttpUrlOrNull() ?: return spec.artifacts
        val existing = spec.artifacts.map { it.fileName }.toSet()
        // Old direct specs required tokenizer.json even when the repository only offers BPE files.
        val declared = spec.artifacts.map { if (it.fileName == "tokenizer.json") it.copy(optional = true) else it }
        return declared + names.filter { it !in existing }.map { name ->
            DownloadArtifactSpec(url.newBuilder().setPathSegment(url.pathSegments.lastIndex, name).build().toString(),
                name, optional = true)
        }
    }

    fun isMissing(message: String?): Boolean = message?.let {
        it.startsWith("Missing files:") || it.startsWith("Missing tokenizer.json or BPE")
    } == true

    fun unsupported(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return if (extension in setOf("bin", "pt", "pth", "ckpt", "h5", "hdf5"))
            ".$extension files aren't supported. Look for a .safetensors version of this model with its config.json and tokenizer files. Its architecture must also be supported."
        else null
    }
}
