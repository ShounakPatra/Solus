package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.LoadRestriction
import com.shounak.localmeshai.models.LoadRestrictionCategory
import com.shounak.localmeshai.models.ModelArtifact
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelFormat
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

private const val TAG = "ModelArtifactStorage"

data class ManifestArtifactRecord(
    val relativePath: String,
    val role: String,
    val sizeBytes: Long,
    val checksumSha256: String? = null,
    val order: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("relativePath", relativePath)
        put("role", role)
        put("sizeBytes", sizeBytes)
        if (checksumSha256 != null) put("checksumSha256", checksumSha256)
        put("order", order)
    }

    companion object {
        fun fromJson(json: JSONObject): ManifestArtifactRecord {
            return ManifestArtifactRecord(
                relativePath = json.getString("relativePath"),
                role = json.optString("role", ArtifactRole.Main.name),
                sizeBytes = json.optLong("sizeBytes", 0L),
                checksumSha256 = if (json.isNull("checksumSha256") || !json.has("checksumSha256")) null else json.getString("checksumSha256"),
                order = json.optInt("order", 0)
            )
        }
    }
}

/**
 * Metadata record for a validated and published model artifact.
 */
data class ModelManifest(
    val modelId: String,
    val relativePath: String,
    val sourceIdentity: String,
    val sizeBytes: Long,
    val checksumSha256: String?,
    val archiveChecksumSha256: String? = null,
    val runnableRelativePath: String? = null,
    val packageType: String = "SingleFile",
    val publishedAt: Long = System.currentTimeMillis(),
    val artifactRelativePaths: List<String> = emptyList(),
    val artifacts: List<ManifestArtifactRecord> = emptyList(),
    val capabilities: ModelCapabilities? = null,
    val format: String? = null,
    val loadRestriction: LoadRestriction? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("modelId", modelId)
        put("relativePath", relativePath)
        put("sourceIdentity", sourceIdentity)
        put("sizeBytes", sizeBytes)
        put("checksumSha256", checksumSha256 ?: JSONObject.NULL)
        put("archiveChecksumSha256", archiveChecksumSha256 ?: JSONObject.NULL)
        put("runnableRelativePath", runnableRelativePath ?: JSONObject.NULL)
        put("packageType", packageType)
        put("publishedAt", publishedAt)
        if (format != null) put("format", format)
        if (artifactRelativePaths.isNotEmpty()) {
            val arr = org.json.JSONArray()
            artifactRelativePaths.forEach { arr.put(it) }
            put("artifactRelativePaths", arr)
        }
        if (artifacts.isNotEmpty()) {
            val arr = org.json.JSONArray()
            artifacts.forEach { arr.put(it.toJson()) }
            put("artifacts", arr)
        }
        if (capabilities != null) {
            put("capabilities", capabilities.toJson())
        }
        if (loadRestriction != null) {
            val resObj = JSONObject().apply {
                put("reason", loadRestriction.reason)
                put("category", loadRestriction.category.name)
            }
            put("loadRestriction", resObj)
        }
    }.toString(2)

    companion object {
        fun fromJson(jsonStr: String): ModelManifest {
            val json = JSONObject(jsonStr)
            val relPaths = mutableListOf<String>()
            if (json.has("artifactRelativePaths") && !json.isNull("artifactRelativePaths")) {
                val arr = json.getJSONArray("artifactRelativePaths")
                for (i in 0 until arr.length()) {
                    relPaths.add(arr.getString(i))
                }
            }
            val artifactRecords = mutableListOf<ManifestArtifactRecord>()
            if (json.has("artifacts") && !json.isNull("artifacts")) {
                val arr = json.getJSONArray("artifacts")
                for (i in 0 until arr.length()) {
                    artifactRecords.add(ManifestArtifactRecord.fromJson(arr.getJSONObject(i)))
                }
            }
            val caps = if (json.has("capabilities") && !json.isNull("capabilities")) {
                ModelCapabilities.fromJson(json.getJSONObject("capabilities"))
            } else null
            val format = if (json.has("format") && !json.isNull("format")) json.getString("format") else null
            val loadRestriction = if (json.has("loadRestriction") && !json.isNull("loadRestriction")) {
                val resObj = json.getJSONObject("loadRestriction")
                val cat = LoadRestrictionCategory.valueOf(resObj.getString("category"))
                LoadRestriction(reason = resObj.getString("reason"), category = cat)
            } else null

            return ModelManifest(
                modelId = json.getString("modelId"),
                relativePath = json.getString("relativePath"),
                sourceIdentity = json.getString("sourceIdentity"),
                sizeBytes = json.getLong("sizeBytes"),
                checksumSha256 = if (json.isNull("checksumSha256") || !json.has("checksumSha256")) null else json.getString("checksumSha256"),
                archiveChecksumSha256 = if (json.isNull("archiveChecksumSha256") || !json.has("archiveChecksumSha256")) null else json.getString("archiveChecksumSha256"),
                runnableRelativePath = if (json.isNull("runnableRelativePath") || !json.has("runnableRelativePath")) null else json.getString("runnableRelativePath"),
                packageType = json.optString("packageType", "SingleFile"),
                publishedAt = json.optLong("publishedAt", System.currentTimeMillis()),
                artifactRelativePaths = relPaths,
                artifacts = artifactRecords,
                capabilities = caps,
                format = format,
                loadRestriction = loadRestriction
            )
        }
    }
}

/**
 * Represents a staged artifact component ready for publication into a model bundle.
 */
data class StagedArtifactItem(
    val stagedFile: File,
    val relativeTargetName: String,
    val expectedSha256: String? = null,
    val isPrimary: Boolean = false,
    val role: ArtifactRole = ArtifactRole.Main,
    val order: Int = 0
)

/**
 * Manages model filesystem hierarchy, containment safety, and artifact publication manifests.
 * Guarantees every model has isolated directory storage preventing filename collisions.
 */
class ModelArtifactStorage(val rootDir: File) {

    init {
        rootDir.mkdirs()
    }

    fun getModelDir(modelId: String, create: Boolean = false): File {
        val sanitizedId = sanitizeId(modelId)
        val dir = File(rootDir, sanitizedId)
        verifyContainment(rootDir, dir)
        if (create) {
            dir.mkdirs()
        }
        return dir
    }

    fun getStagingDir(modelId: String): File {
        val modelDir = getModelDir(modelId, create = true)
        val staging = File(modelDir, ".staging")
        verifyContainment(modelDir, staging)
        return staging
    }

    fun getStagingFile(modelId: String, fileName: String): File {
        val safeName = sanitizeFileName(fileName)
        val stagingDir = getStagingDir(modelId)
        stagingDir.mkdirs()
        val file = File(stagingDir, "$safeName.download")
        verifyContainment(stagingDir, file)
        return file
    }

    fun getManifestFile(modelId: String): File {
        val modelDir = getModelDir(modelId, create = false)
        val file = File(modelDir, "manifest.json")
        verifyContainment(modelDir, file)
        return file
    }

    fun getManifest(modelId: String): ModelManifest? {
        val file = getManifestFile(modelId)
        if (!file.exists() || !file.isFile) return null
        val atomicFile = AtomicJsonFile<ModelManifest>(file)
        return when (val result = atomicFile.read { ModelManifest.fromJson(it) }) {
            is AtomicReadResult.Success -> result.data
            else -> null
        }
    }

    fun getPublishedArtifact(modelId: String): File? {
        val modelDir = getModelDir(modelId, create = false)
        if (!modelDir.exists() || !modelDir.isDirectory) return null
        val manifest = getManifest(modelId) ?: return null
        if (manifest.modelId != modelId) return null

        if (manifest.packageType == "ArtifactBundle") {
            val bundle = getPublishedBundle(modelId) ?: return null
            val primaryArtifact = bundle.mainArtifacts.firstOrNull() ?: return null
            val path = (primaryArtifact.source as? ArtifactSource.AppManaged)?.path ?: return null
            val file = File(path)
            return if (file.exists() && file.isFile && file.length() > 0L) file else null
        }

        val relPath = manifest.runnableRelativePath ?: manifest.relativePath
        if (relPath.isBlank() || relPath.contains("..")) return null

        val artifactFile = File(modelDir, relPath)
        try {
            verifyContainment(modelDir, artifactFile)
        } catch (e: SecurityException) {
            return null
        }
        if (!artifactFile.exists()) return null

        if (artifactFile.isFile) {
            if (artifactFile.length() <= 0L) return null
            if (manifest.archiveChecksumSha256 == null &&
                manifest.packageType != "ZipDirectory" &&
                manifest.artifactRelativePaths.isEmpty() &&
                manifest.sizeBytes > 0L &&
                artifactFile.length() != manifest.sizeBytes
            ) {
                return null
            }
            return artifactFile
        } else if (artifactFile.isDirectory) {
            val runnable = runCatching { resolveRunnableInDirectory(artifactFile, isText = true) }.getOrNull()
            if (runnable == null || !runnable.exists() || runnable.length() <= 0L) return null
            return runnable
        }
        return null
    }

    fun getPublishedBundle(modelId: String): ModelBundle? {
        val sanitizedId = sanitizeId(modelId)
        val manifest = getManifest(sanitizedId) ?: return null
        if (manifest.packageType != "ArtifactBundle") return null
        val modelDir = getModelDir(sanitizedId, create = false)
        if (!modelDir.exists() || !modelDir.isDirectory) return null

        val records = if (manifest.artifacts.isNotEmpty()) {
            manifest.artifacts.sortedBy { it.order }
        } else {
            val shardRegex = Regex("""^(.*?)[-_.](\d{5})[-_.]of[-_.](\d{5})\.gguf$""", RegexOption.IGNORE_CASE)
            manifest.artifactRelativePaths.mapIndexed { idx, relPath ->
                val f = File(relPath)
                val isProj = f.name.lowercase(Locale.ROOT).contains("mmproj") || f.name.lowercase(Locale.ROOT).contains("projector")
                val isShard = shardRegex.containsMatchIn(f.name)
                val role = when {
                    isProj -> ArtifactRole.Projector
                    isShard -> ArtifactRole.Shard
                    else -> ArtifactRole.Main
                }
                ManifestArtifactRecord(relativePath = relPath, role = role.name, sizeBytes = 0L, order = idx)
            }
        }

        if (records.isEmpty()) return null

        val artifacts = mutableListOf<ModelArtifact>()
        for (record in records) {
            val relPath = record.relativePath
            if (relPath.isBlank() || relPath.contains("..")) return null
            val file = File(modelDir, relPath)
            try {
                verifyContainment(modelDir, file)
            } catch (_: SecurityException) {
                return null
            }
            if (!file.exists() || !file.isFile || file.length() <= 0L) return null
            if (record.sizeBytes > 0L && file.length() != record.sizeBytes) return null

            val role = runCatching { ArtifactRole.valueOf(record.role) }.getOrDefault(ArtifactRole.Main)
            artifacts.add(
                ModelArtifact(
                    role = role,
                    displayName = file.name,
                    source = ArtifactSource.AppManaged(file.absolutePath),
                    sizeBytes = file.length(),
                    lastModified = file.lastModified()
                )
            )
        }

        val mainArtifacts = artifacts.filter { it.role != ArtifactRole.Projector }
        if (mainArtifacts.isEmpty()) return null
        val projectorArtifact = artifacts.firstOrNull { it.role == ArtifactRole.Projector }

        val formatStr = manifest.format ?: when {
            mainArtifacts.any { it.displayName.endsWith(".task", ignoreCase = true) } -> ModelFormat.MediaPipeTask.name
            mainArtifacts.any { it.displayName.endsWith(".litertlm", ignoreCase = true) } -> ModelFormat.LiteRtLm.name
            else -> ModelFormat.GGUF.name
        }
        val bundleFormat = ModelFormat.entries.firstOrNull { it.name.equals(formatStr, ignoreCase = true) } ?: ModelFormat.GGUF
        val backend = when (bundleFormat) {
            ModelFormat.LiteRtLm -> "LiteRT-LM GPU"
            ModelFormat.MediaPipeTask -> "MediaPipe LLM CPU-safe"
            ModelFormat.GGUF -> "llama.cpp GGUF"
        }

        // Restoration reads manifests only. Deep inspection belongs to the background validation queue.
        val caps = manifest.capabilities ?: ModelCapabilities()

        val primaryName = mainArtifacts.firstOrNull()?.displayName?.substringBeforeLast('.')?.replace('-', ' ')?.replace('_', ' ') ?: sanitizedId

        return ModelBundle(
            id = sanitizedId,
            displayName = primaryName,
            mainArtifacts = mainArtifacts,
            projectorArtifact = projectorArtifact,
            capabilities = caps,
            backend = backend,
            format = bundleFormat,
            loadRestriction = manifest.loadRestriction
        )
    }

    fun publishArtifact(
        modelId: String,
        stagedSource: File,
        relativeTargetName: String,
        sourceIdentity: String,
        expectedSha256: String? = null,
        archiveSha256: String? = null,
        isCancelled: (() -> Boolean)? = null,
        isTextModel: Boolean = true
    ): File {
        if (!stagedSource.exists()) {
            throw IOException("Staged artifact does not exist: ${stagedSource.absolutePath}")
        }
        if (isCancelled?.invoke() == true) {
            throw kotlinx.coroutines.CancellationException("Artifact publication cancelled")
        }

        val sanitizedId = sanitizeId(modelId)
        val safeName = sanitizeFileName(relativeTargetName)
        val modelDir = getModelDir(sanitizedId, create = true)
        verifyContainment(rootDir, modelDir)
        verifyContainment(modelDir, stagedSource)

        val genDirName = "gen_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
        val genDir = File(modelDir, genDirName)
        verifyContainment(modelDir, genDir)
        if (!genDir.mkdirs()) {
            throw IOException("Failed to create generation directory: ${genDir.absolutePath}")
        }

        var committed = false
        try {
            val genTarget = File(genDir, safeName)
            verifyContainment(genDir, genTarget)

            if (stagedSource.isDirectory) {
                val moved = stagedSource.renameTo(genTarget)
                if (!moved) {
                    stagedSource.copyRecursively(genTarget, overwrite = true)
                    stagedSource.deleteRecursively()
                }
            } else {
                val moved = stagedSource.renameTo(genTarget)
                if (!moved) {
                    stagedSource.copyTo(genTarget, overwrite = true)
                    stagedSource.delete()
                }
            }

            if (isCancelled?.invoke() == true) {
                throw kotlinx.coroutines.CancellationException("Artifact publication cancelled after copy")
            }

            val sizeBytes: Long
            val runnableRelativePath: String
            val checksumToRecord: String?
            val returnedArtifact: File

            if (genTarget.isDirectory) {
                sizeBytes = genTarget.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val runnableFile = resolveRunnableInDirectory(genTarget, isText = isTextModel)
                val relRunnable = runnableFile.relativeTo(modelDir).path.replace('\\', '/')
                runnableRelativePath = relRunnable
                checksumToRecord = null
                returnedArtifact = runnableFile
            } else {
                sizeBytes = genTarget.length()
                if (!expectedSha256.isNullOrBlank()) {
                    val actualChecksum = calculateSha256(genTarget, isCancelled)
                    if (!actualChecksum.equals(expectedSha256.trim(), ignoreCase = true)) {
                        throw IOException("Artifact checksum verification failed. Expected: $expectedSha256, actual: $actualChecksum")
                    }
                }
                checksumToRecord = expectedSha256?.trim() ?: calculateSha256(genTarget, isCancelled)
                runnableRelativePath = "$genDirName/$safeName"
                returnedArtifact = genTarget
            }

            val manifest = ModelManifest(
                modelId = sanitizedId,
                relativePath = "$genDirName/$safeName",
                sourceIdentity = sourceIdentity,
                sizeBytes = sizeBytes,
                checksumSha256 = checksumToRecord,
                archiveChecksumSha256 = archiveSha256?.trim(),
                runnableRelativePath = runnableRelativePath,
                packageType = if (genTarget.isDirectory) "ZipDirectory" else "SingleFile",
                publishedAt = System.currentTimeMillis()
            )

            if (returnedArtifact.isFile && checksumToRecord != null) {
                runCatching {
                    ModelVerificationCache(modelDir).recordChecksum(returnedArtifact, checksumToRecord, sourceIdentity)
                }
            }
            val manifestFile = getManifestFile(sanitizedId)
            val atomicManifest = AtomicJsonFile<ModelManifest>(manifestFile)
            val writeResult = atomicManifest.write(manifest) { it.toJson() }
            if (writeResult.isFailure) {
                throw IOException("Failed to write publication manifest: ${writeResult.exceptionOrNull()?.message}")
            }
            committed = true

            runCatching { cleanupObsoleteGenerations(sanitizedId, activeGenerationDirName = genDirName) }
            runCatching { getStagingDir(sanitizedId).deleteRecursively() }

            return returnedArtifact
        } catch (e: Throwable) {
            if (!committed) {
                genDir.deleteRecursively()
            }
            throw e
        }
    }

    data class PublicationTransaction(
        val modelId: String,
        val primaryFile: File,
        val effectiveManifest: ModelManifest,
        val newGenerationDirName: String,
        private val journalFile: File,
        private val previousManifestFile: File?,
        private val previousManifestContent: String?,
        private val storage: ModelArtifactStorage
    ) {
        fun commit() {
            val committedJournal = JSONObject().apply {
                put("state", "COMMITTED")
                put("modelId", modelId)
                put("newGenDirName", newGenerationDirName)
                put("timestamp", System.currentTimeMillis())
            }
            writeAtomic(journalFile, committedJournal.toString(2))
            storage.cleanupObsoleteGenerations(modelId, activeGenerationDirName = newGenerationDirName)
            runCatching { storage.getStagingDir(modelId).deleteRecursively() }
            previousManifestFile?.delete()
            journalFile.delete()
            clearRecoveryRequired(modelId)
        }

        fun rollback(): Boolean {
            return try {
                var manifestRestored = false
                val manifestFile = storage.getManifestFile(modelId)
                if (previousManifestContent != null) {
                    try {
                        writeAtomic(manifestFile, previousManifestContent)
                        manifestRestored = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed atomic restore of previous manifest during rollback: ${e.message}")
                        recordRecoveryRequired(modelId, "Failed to restore previous manifest during rollback: ${e.message}")
                        return false
                    }
                } else {
                    manifestFile.delete()
                    manifestRestored = true
                }

                val modelDir = storage.getModelDir(modelId, create = false)
                val newGenDir = File(modelDir, newGenerationDirName)
                var deletionSucceeded = true
                if (newGenDir.exists()) {
                    val deleted = reserveAndExecuteDeletion(newGenDir) { dir ->
                        dir.deleteRecursively()
                    }
                    if (!deleted || newGenDir.exists()) {
                        deletionSucceeded = false
                        Log.w(TAG, "Failed or skipped deletion of newGenDir during rollback (leased or locked): ${newGenDir.name}")
                        recordRecoveryRequired(modelId, "Generation ${newGenDir.name} could not be cleaned up during rollback (leased or in-use)")
                    }
                }

                if (manifestRestored && deletionSucceeded) {
                    previousManifestFile?.delete()
                    journalFile.delete()
                    clearRecoveryRequired(modelId)
                    true
                } else {
                    false
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error during rollback for $modelId", e)
                recordRecoveryRequired(modelId, "Rollback failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Begins an atomic publication transaction for an artifact bundle.
     * Staging files are moved into the new generation, journal and backup manifest are created,
     * but obsolete generations are NOT cleaned until [PublicationTransaction.commit] is called.
     * Calling [PublicationTransaction.rollback] restores the previous manifest and generation cleanly.
     */
    fun beginTransaction(
        modelId: String,
        stagedItems: List<StagedArtifactItem>,
        primaryItemName: String? = null,
        sourceIdentity: String,
        packageType: String = "ArtifactBundle",
        capabilities: ModelCapabilities? = null,
        format: ModelFormat? = null,
        loadRestriction: LoadRestriction? = null,
        isCancelled: (() -> Boolean)? = null
    ): PublicationTransaction {
        if (stagedItems.isEmpty()) {
            throw IOException("No staged items provided for bundle publication")
        }
        val safeNames = stagedItems.map { sanitizeFileName(it.relativeTargetName).lowercase(Locale.ROOT) }
        if (safeNames.distinct().size != safeNames.size) {
            throw IOException("Duplicate artifact target file names in bundle publication")
        }
        for (item in stagedItems) {
            if (!item.stagedFile.exists() || item.stagedFile.length() <= 0L) {
                throw IOException("Staged artifact does not exist or is empty: ${item.stagedFile.absolutePath}")
            }
        }
        if (isCancelled?.invoke() == true) {
            throw kotlinx.coroutines.CancellationException("Bundle publication cancelled")
        }

        val sanitizedId = sanitizeId(modelId)
        if (isRecoveryRequired(sanitizedId)) {
            val reason = getRecoveryRequiredReason(sanitizedId) ?: "interrupted transaction"
            throw IOException("Model $sanitizedId is in recovery-required state: $reason. Further publication is blocked until reconciled.")
        }
        val modelDir = getModelDir(sanitizedId, create = true)
        verifyContainment(rootDir, modelDir)

        val manifestFile = getManifestFile(sanitizedId)
        val previousManifestContent = if (manifestFile.exists()) manifestFile.readText() else null
        val previousGenDirName = runCatching {
            if (previousManifestContent != null) {
                val prevObj = JSONObject(previousManifestContent)
                val rel = prevObj.optString("runnableRelativePath").ifBlank { prevObj.optString("relativePath") }
                rel.substringBefore('/').takeIf { it.startsWith("gen_") }
            } else null
        }.getOrNull()
        val bakManifestFile = if (previousManifestContent != null) File(modelDir, "manifest.json.tx_bak") else null
        if (bakManifestFile != null) {
            manifestFile.copyTo(bakManifestFile, overwrite = true)
        }

        val genDirName = "gen_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
        val genDir = File(modelDir, genDirName)
        verifyContainment(modelDir, genDir)
        if (!genDir.mkdirs()) {
            throw IOException("Failed to create generation directory: ${genDir.absolutePath}")
        }

        val journalFile = File(modelDir, "publication_journal.json")
        try {
            val journalJson = JSONObject().apply {
                put("state", "PREPARED")
                put("modelId", sanitizedId)
                put("newGenDirName", genDirName)
                put("previousGenDirName", previousGenDirName.orEmpty())
                put("registrationIdentity", sourceIdentity)
                put("hasPreviousManifest", previousManifestContent != null)
                put("timestamp", System.currentTimeMillis())
            }
            writeAtomic(journalFile, journalJson.toString(2))

            var totalBytes = 0L
            val relPaths = mutableListOf<String>()
            val manifestRecords = mutableListOf<ManifestArtifactRecord>()
            var primaryFile: File? = null
            var primaryRelativePath: String? = null

            for (item in stagedItems) {
                if (isCancelled?.invoke() == true) {
                    throw kotlinx.coroutines.CancellationException("Bundle publication cancelled during staging")
                }
                val safeName = sanitizeFileName(item.relativeTargetName)
                val genTarget = File(genDir, safeName)
                verifyContainment(genDir, genTarget)

                val moved = item.stagedFile.renameTo(genTarget)
                if (!moved) {
                    item.stagedFile.copyTo(genTarget, overwrite = true)
                    item.stagedFile.delete()
                }

                if (!item.expectedSha256.isNullOrBlank()) {
                    val actualChecksum = calculateSha256(genTarget, isCancelled)
                    if (!actualChecksum.equals(item.expectedSha256.trim(), ignoreCase = true)) {
                        throw IOException("Bundle artifact checksum verification failed for $safeName. Expected: ${item.expectedSha256}, actual: $actualChecksum")
                    }
                }

                val relPath = "$genDirName/$safeName"
                relPaths.add(relPath)
                val itemSize = genTarget.length()
                totalBytes += itemSize
                manifestRecords.add(
                    ManifestArtifactRecord(
                        relativePath = relPath,
                        role = item.role.name,
                        sizeBytes = itemSize,
                        checksumSha256 = item.expectedSha256,
                        order = item.order
                    )
                )

                val matchesPrimary = when {
                    primaryItemName != null -> safeName.equals(sanitizeFileName(primaryItemName), ignoreCase = true)
                    item.isPrimary -> true
                    else -> false
                }
                if (matchesPrimary || primaryFile == null) {
                    primaryFile = genTarget
                    primaryRelativePath = relPath
                }
            }

            val runnableRel = primaryRelativePath ?: relPaths.first()
            val finalPrimary = primaryFile ?: File(modelDir, runnableRel)
            val updatedCapabilities = capabilities?.copy(
                thinkingArtifactIdentity = finalPrimary.absolutePath
            )
            val inferredFormat = format ?: when {
                finalPrimary.name.endsWith(".task", ignoreCase = true) -> ModelFormat.MediaPipeTask
                finalPrimary.name.endsWith(".litertlm", ignoreCase = true) -> ModelFormat.LiteRtLm
                else -> ModelFormat.GGUF
            }
            val manifest = ModelManifest(
                modelId = sanitizedId,
                relativePath = runnableRel,
                sourceIdentity = sourceIdentity,
                sizeBytes = totalBytes,
                checksumSha256 = null,
                runnableRelativePath = runnableRel,
                packageType = packageType,
                publishedAt = System.currentTimeMillis(),
                artifactRelativePaths = relPaths,
                artifacts = manifestRecords,
                capabilities = updatedCapabilities,
                format = inferredFormat.name,
                loadRestriction = loadRestriction
            )

            val atomicManifest = AtomicJsonFile<ModelManifest>(manifestFile)
            val writeResult = atomicManifest.write(manifest) { it.toJson() }
            if (writeResult.isFailure) {
                throw IOException("Failed to write publication manifest: ${writeResult.exceptionOrNull()?.message}")
            }

            return PublicationTransaction(
                modelId = sanitizedId,
                primaryFile = finalPrimary,
                effectiveManifest = manifest,
                newGenerationDirName = genDirName,
                journalFile = journalFile,
                previousManifestFile = bakManifestFile,
                previousManifestContent = previousManifestContent,
                storage = this
            )
        } catch (e: Throwable) {
            genDir.deleteRecursively()
            journalFile.delete()
            bakManifestFile?.delete()
            throw e
        }
    }

    /**
     * Publishes a collection of staged artifact files atomically as a single model bundle.
     * All items are validated and moved into the generation directory before the manifest is committed.
     */
    fun publishArtifactBundle(
        modelId: String,
        stagedItems: List<StagedArtifactItem>,
        primaryItemName: String? = null,
        sourceIdentity: String,
        packageType: String = "ArtifactBundle",
        capabilities: ModelCapabilities? = null,
        format: ModelFormat? = null,
        loadRestriction: LoadRestriction? = null,
        isCancelled: (() -> Boolean)? = null
    ): File {
        val tx = beginTransaction(
            modelId = modelId,
            stagedItems = stagedItems,
            primaryItemName = primaryItemName,
            sourceIdentity = sourceIdentity,
            packageType = packageType,
            capabilities = capabilities,
            format = format,
            loadRestriction = loadRestriction,
            isCancelled = isCancelled
        )
        tx.commit()
        return tx.primaryFile
    }

    /**
     * Recovers any interrupted publication transactions left on disk during startup.
     */
    fun recoverPendingTransactions(
        customModelStore: com.shounak.localmeshai.models.CustomModelStore? = null,
        isStoreAvailable: Boolean = true
    ) {
        if (!rootDir.exists() || !rootDir.isDirectory) return
        rootDir.listFiles()?.forEach { modelDir ->
            if (modelDir.isDirectory) {
                val journalFile = File(modelDir, "publication_journal.json")
                if (journalFile.exists()) {
                    runCatching {
                        val json = JSONObject(journalFile.readText())
                        val state = json.optString("state", "PREPARED")
                        val modelId = json.optString("modelId", modelDir.name)
                        val newGenDirName = json.optString("newGenDirName", "")
                        val bakManifest = File(modelDir, "manifest.json.tx_bak").let {
                            if (it.exists()) it else File(modelDir, "manifest.json.bak")
                        }
                        val manifestFile = File(modelDir, "manifest.json")

                        if (state == "COMMITTED") {
                            bakManifest.delete()
                            journalFile.delete()
                            if (newGenDirName.isNotBlank()) {
                                cleanupObsoleteGenerations(modelId, newGenDirName)
                            }
                            clearRecoveryRequired(modelId)
                        } else if (state == "PREPARED") {
                            var referencedByStore = false
                            var storeInspectionFailed = false

                            if (!isStoreAvailable) {
                                storeInspectionFailed = true
                            } else if (customModelStore != null) {
                                try {
                                    val def = customModelStore.getById(modelId)
                                    if (def != null) {
                                        val newGenDir = File(modelDir, newGenDirName)
                                        val locs = def.bundle?.allArtifacts?.map { it.source.location } ?: emptyList()
                                        referencedByStore = locs.any { loc ->
                                            val f = File(loc)
                                            val canonicalParent = runCatching { f.canonicalFile.parentFile }.getOrNull()
                                            canonicalParent != null && canonicalParent == newGenDir.canonicalFile
                                        }
                                    }
                                } catch (e: Throwable) {
                                    Log.w(TAG, "CustomModelStore inspection failed during recovery: ${e.message}")
                                    storeInspectionFailed = true
                                }
                            }

                            if (storeInspectionFailed) {
                                // Retain both generations, manifest, backup, and journal if store is unreadable
                                Log.w(TAG, "Retaining generations for $modelId due to store inspection failure")
                                recordRecoveryRequired(modelId, "Custom model store unreadable during transaction recovery")
                            } else if (referencedByStore) {
                                // Durable model record exists and references new generation: finalize transaction
                                bakManifest.delete()
                                journalFile.delete()
                                if (newGenDirName.isNotBlank()) {
                                    cleanupObsoleteGenerations(modelId, newGenDirName)
                                }
                                clearRecoveryRequired(modelId)
                            } else {
                                // Durable record never committed: restore previous generation
                                val hasPreviousManifest = json.optBoolean("hasPreviousManifest", false)
                                if (bakManifest.exists()) {
                                    var restoreOk = false
                                    try {
                                        writeAtomic(manifestFile, bakManifest.readText())
                                        restoreOk = true
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed atomic restore of backup manifest: ${e.message}")
                                        recordRecoveryRequired(modelId, "Failed to restore backup manifest during recovery: ${e.message}")
                                    }
                                    if (restoreOk) {
                                        var delOk = true
                                        if (newGenDirName.isNotBlank()) {
                                            val newGenDir = File(modelDir, newGenDirName)
                                            if (newGenDir.exists()) {
                                                val deleted = reserveAndExecuteDeletion(newGenDir) { it.deleteRecursively() }
                                                if (!deleted || newGenDir.exists()) {
                                                    delOk = false
                                                    recordRecoveryRequired(modelId, "Generation $newGenDirName could not be cleaned up during recovery (leased or in-use)")
                                                }
                                            }
                                        }
                                        if (delOk) {
                                            bakManifest.delete()
                                            journalFile.delete()
                                            clearRecoveryRequired(modelId)
                                        }
                                    }
                                } else if (hasPreviousManifest) {
                                    // Journal says previous manifest existed, but backup is missing!
                                    // Preserve files and report recovery trouble instead of deleting manifest!
                                    Log.w(TAG, "Previous manifest backup missing for $modelId; preserving files to prevent data loss")
                                    recordRecoveryRequired(modelId, "Previous manifest backup missing during rollback recovery")
                                } else {
                                    // First publication rollback (no previous manifest ever existed)
                                    manifestFile.delete()
                                    var delOk = true
                                    if (newGenDirName.isNotBlank()) {
                                        val newGenDir = File(modelDir, newGenDirName)
                                        if (newGenDir.exists()) {
                                            val deleted = reserveAndExecuteDeletion(newGenDir) { it.deleteRecursively() }
                                            if (!deleted || newGenDir.exists()) {
                                                delOk = false
                                                recordRecoveryRequired(modelId, "Generation $newGenDirName could not be cleaned up during recovery (leased or in-use)")
                                            }
                                        }
                                    }
                                    if (delOk) {
                                        journalFile.delete()
                                        clearRecoveryRequired(modelId)
                                    }
                                }
                            }
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "Failed recovering pending transaction in ${modelDir.name}", e)
                    }
                }
            }
        }
    }

    fun getPublishedArtifacts(modelId: String): List<File> {
        val modelDir = getModelDir(modelId, create = false)
        if (!modelDir.exists() || !modelDir.isDirectory) return emptyList()
        val manifest = getManifest(modelId) ?: return emptyList()
        if (manifest.packageType == "ArtifactBundle") {
            val bundle = getPublishedBundle(modelId) ?: return emptyList()
            return bundle.allArtifacts.map { File((it.source as ArtifactSource.AppManaged).path) }
        }
        val primary = getPublishedArtifact(modelId)
        return if (primary != null) listOf(primary) else emptyList()
    }

    fun leaseGeneration(modelId: String, genDirName: String): AutoCloseable {
        return leaseGenerationGlobal(modelId, genDirName)
    }

    fun isGenerationLeased(modelId: String, genDirName: String): Boolean {
        return isGenerationLeasedGlobal(modelId, genDirName)
    }

    fun cleanupObsoleteGenerations(modelId: String, activeGenerationDirName: String) {
        val sanitizedId = sanitizeId(modelId)
        val modelDir = getModelDir(sanitizedId, create = false)
        if (!modelDir.exists() || !modelDir.isDirectory) return
        modelDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("gen_") && file.name != activeGenerationDirName) {
                val key = file.canonicalPath
                val canDelete = synchronized(leaseLock) {
                    val count = globalActiveLeases[key]?.get() ?: 0
                    if (count > 0 || isGenerationLeased(sanitizedId, file.name)) {
                        false
                    } else {
                        deletingGenerations.add(key)
                        true
                    }
                }
                if (canDelete) {
                    try {
                        file.deleteRecursively()
                    } finally {
                        synchronized(leaseLock) {
                            deletingGenerations.remove(key)
                        }
                    }
                }
            }
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val sanitizedId = sanitizeId(modelId)
        val modelDir = getModelDir(sanitizedId, create = false)
        if (!modelDir.exists()) return true
        val modelDirKey = modelDir.canonicalPath
        val canDelete = synchronized(leaseLock) {
            if (hasAnyActiveLeases(sanitizedId)) return false
            val hasLeasedChild = globalActiveLeases.any { (k, count) ->
                k.startsWith(modelDirKey + File.separator) && count.get() > 0
            }
            if (hasLeasedChild) return false
            deletingGenerations.add(modelDirKey)
            true
        }
        if (!canDelete) return false
        return try {
            modelDir.deleteRecursively()
        } finally {
            synchronized(leaseLock) {
                deletingGenerations.remove(modelDirKey)
            }
        }
    }

    fun getPartialDownloadBytes(modelId: String, fileName: String): Long {
        val stagingFile = getStagingFile(modelId, fileName)
        return if (stagingFile.exists() && stagingFile.isFile) stagingFile.length() else 0L
    }

    fun isRecoveryRequired(modelId: String): Boolean = Companion.isRecoveryRequired(modelId)
    fun getRecoveryRequiredModels(): Map<String, String> = Companion.getRecoveryRequiredModels()

    companion object {
        private val leaseLock = Any()
        // Process-wide, reference-counted lease registry
        private val globalActiveLeases = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        private val deletingGenerations = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        private val recoveryRequiredModels = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun writeAtomic(target: File, content: String) {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        }

        fun getRecoveryRequiredModels(): Map<String, String> = recoveryRequiredModels.toMap()

        fun isRecoveryRequired(modelId: String): Boolean =
            recoveryRequiredModels.containsKey(runCatching { sanitizeId(modelId) }.getOrDefault(modelId))

        fun getRecoveryRequiredReason(modelId: String): String? =
            recoveryRequiredModels[runCatching { sanitizeId(modelId) }.getOrDefault(modelId)]

        fun recordRecoveryRequired(modelId: String, reason: String) {
            val key = runCatching { sanitizeId(modelId) }.getOrDefault(modelId)
            recoveryRequiredModels[key] = reason
        }

        fun clearRecoveryRequired(modelId: String) {
            val key = runCatching { sanitizeId(modelId) }.getOrDefault(modelId)
            recoveryRequiredModels.remove(key)
        }

        fun resolveCanonicalGenerationPath(file: File): String {
            val canonical = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
            var curr: File? = if (canonical.isDirectory) canonical else canonical.parentFile
            while (curr != null) {
                if (curr.name.startsWith("gen_") && curr.isDirectory) {
                    return curr.canonicalPath
                }
                curr = curr.parentFile
            }
            return canonical.canonicalPath
        }

        fun leaseArtifact(file: File): AutoCloseable {
            val key = resolveCanonicalGenerationPath(file)
            val counter: java.util.concurrent.atomic.AtomicInteger
            synchronized(leaseLock) {
                val isBeingDeleted = deletingGenerations.any { deleting ->
                    key == deleting || key.startsWith(deleting + File.separator)
                }
                if (isBeingDeleted) {
                    throw IllegalStateException("Model artifact at $key is currently undergoing deletion")
                }
                counter = globalActiveLeases.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }
                counter.incrementAndGet()
            }
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            return AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    synchronized(leaseLock) {
                        val rem = counter.decrementAndGet()
                        if (rem <= 0) {
                            globalActiveLeases.remove(key, counter)
                        }
                    }
                }
            }
        }

        fun isArtifactLeased(file: File): Boolean {
            val key = resolveCanonicalGenerationPath(file)
            return synchronized(leaseLock) {
                (globalActiveLeases[key]?.get() ?: 0) > 0
            }
        }

        fun hasLeasedDescendants(file: File): Boolean {
            val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val genPath = resolveCanonicalGenerationPath(file)
            synchronized(leaseLock) {
                return globalActiveLeases.any { (k, count) ->
                    count.get() > 0 && (
                        k == canonicalPath ||
                        k == genPath ||
                        k.startsWith(canonicalPath + File.separator) ||
                        canonicalPath.startsWith(k + File.separator) ||
                        k.startsWith(genPath + File.separator) ||
                        genPath.startsWith(k + File.separator)
                    )
                }
            }
        }

        fun reserveAndExecuteDeletion(file: File, block: (File) -> Boolean): Boolean {
            val canonicalKey = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val genKey = resolveCanonicalGenerationPath(file)
            synchronized(leaseLock) {
                val isLeased = globalActiveLeases.any { (k, count) ->
                    count.get() > 0 && (
                        k == canonicalKey ||
                        k == genKey ||
                        k.startsWith(canonicalKey + File.separator) ||
                        canonicalKey.startsWith(k + File.separator) ||
                        k.startsWith(genKey + File.separator) ||
                        genKey.startsWith(k + File.separator)
                    )
                }
                if (isLeased) return false
                val isBeingDeleted = deletingGenerations.any { deleting ->
                    canonicalKey == deleting ||
                    genKey == deleting ||
                    canonicalKey.startsWith(deleting + File.separator) ||
                    deleting.startsWith(canonicalKey + File.separator) ||
                    genKey.startsWith(deleting + File.separator) ||
                    deleting.startsWith(genKey + File.separator)
                }
                if (isBeingDeleted) return false
                deletingGenerations.add(canonicalKey)
                deletingGenerations.add(genKey)
            }
            return try {
                block(file)
            } finally {
                synchronized(leaseLock) {
                    deletingGenerations.remove(canonicalKey)
                    deletingGenerations.remove(genKey)
                }
            }
        }

        fun leaseGenerationGlobal(modelId: String, genDirName: String): AutoCloseable {
            val key = "${sanitizeId(modelId)}/$genDirName"
            val counter: java.util.concurrent.atomic.AtomicInteger
            synchronized(leaseLock) {
                if (deletingGenerations.contains(key)) {
                    throw IllegalStateException("Generation $key is currently undergoing deletion")
                }
                counter = globalActiveLeases.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }
                counter.incrementAndGet()
            }
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            return AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    synchronized(leaseLock) {
                        val rem = counter.decrementAndGet()
                        if (rem <= 0) {
                            globalActiveLeases.remove(key, counter)
                        }
                    }
                }
            }
        }

        fun isGenerationLeasedGlobal(modelId: String, genDirName: String): Boolean {
            val key = "${sanitizeId(modelId)}/$genDirName"
            return (globalActiveLeases[key]?.get() ?: 0) > 0
        }

        fun hasAnyActiveLeases(modelId: String): Boolean {
            val prefix = "${sanitizeId(modelId)}/"
            return globalActiveLeases.any { (k, count) -> k.startsWith(prefix) && count.get() > 0 }
        }

        fun sanitizeId(id: String): String {
            val trimmed = id.trim()
            if (trimmed.isBlank() || trimmed == "." || trimmed == ".." || trimmed.contains("/") || trimmed.contains("\\")) {
                throw IllegalArgumentException("Invalid model ID: $id")
            }
            val clean = trimmed.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (clean.isBlank() || clean == "." || clean == ".." || clean.contains("..")) {
                throw IllegalArgumentException("Invalid model ID: $id")
            }
            return clean
        }

        fun sanitizeFileName(raw: String): String {
            val decoded = try {
                URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed URL encoding in filename: $raw", e)
            }

            if (decoded.contains("..") || decoded.contains("/") || decoded.contains("\\")) {
                throw IllegalArgumentException("Filename contains traversal components or path separators: $decoded")
            }

            if (decoded.any { it.isISOControl() || it < ' ' }) {
                throw IllegalArgumentException("Filename contains illegal control characters: $decoded")
            }

            val trimmed = decoded.trim()
            if (trimmed.isBlank() || trimmed == "." || trimmed == "..") {
                throw IllegalArgumentException("Invalid empty or dot filename: $raw")
            }
            return trimmed
        }

        fun inferFileName(url: String, defaultName: String = "model.bin"): String {
            val httpUrl = url.trim().toHttpUrlOrNull()
            val rawName = if (httpUrl != null) {
                httpUrl.pathSegments.lastOrNull { it.isNotBlank() } ?: defaultName
            } else {
                val clean = url.trim().substringBefore('?').substringBefore('#')
                clean.substringAfterLast('/', defaultName)
            }
            return sanitizeFileName(rawName)
        }

        fun verifyContainment(parentDir: File, target: File) {
            val parentPath = parentDir.canonicalFile.toPath()
            val targetPath = target.canonicalFile.toPath()
            if (!targetPath.startsWith(parentPath)) {
                throw SecurityException("Security violation: path traversal detected for target ${target.absolutePath}")
            }
        }

        fun calculateSha256(file: File, isCancelled: (() -> Boolean)? = null): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read >= 0) {
                    if (isCancelled?.invoke() == true) {
                        throw kotlinx.coroutines.CancellationException("Checksum calculation cancelled")
                    }
                    if (read > 0) digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun resolveRunnableInDirectory(dir: File, isText: Boolean = true): File {
            if (!dir.exists() || !dir.isDirectory) {
                throw IOException("Extracted bundle is not a valid directory: ${dir.name}")
            }
            val supportedExtensions = if (isText) {
                listOf(".task", ".litertlm", ".gguf")
            } else {
                listOf(".task", ".litertlm")
            }
            val candidates = dir.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    supportedExtensions.any { ext -> file.name.endsWith(ext, ignoreCase = true) }
                }
                .toList()

            if (candidates.isEmpty()) {
                throw IOException("Extracted bundle does not contain any supported runnable model file (${supportedExtensions.joinToString()}).")
            }
            if (candidates.size > 1) {
                throw IOException("Extracted bundle is ambiguous: contains multiple candidate model files (${candidates.map { it.name }}).")
            }
            return candidates.first()
        }
    }
}
