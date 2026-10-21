package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.DownloadArtifactSpec
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelDownloadSpec
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ModelPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class RepoFile(
    val path: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null
) {
    val fileName: String
        get() = path.substringAfterLast('/')

    val directory: String
        get() = path.substringBeforeLast('/', "")

    val isGguf: Boolean
        get() = fileName.endsWith(".gguf", ignoreCase = true)

    val isLiteRt: Boolean
        get() = fileName.endsWith(".litertlm", ignoreCase = true)

    val isTask: Boolean
        get() = fileName.endsWith(".task", ignoreCase = true)

    val isProjector: Boolean
        get() {
            if (!isGguf) return false
            val lower = fileName.lowercase(Locale.ROOT)
            return lower.contains("mmproj") || lower.contains("projector")
        }
}

enum class HfInputKind {
    REPOSITORY,
    FOLDER,
    FILE
}

data class ParsedHfInput(
    val repoId: String,
    val requestedRevision: String? = null,
    val path: String? = null,
    val kind: HfInputKind = HfInputKind.REPOSITORY
)

data class ModelVariant(
    val name: String,
    val mainFiles: List<RepoFile>,
    val availableProjectors: List<RepoFile> = emptyList(),
    val projectorFile: RepoFile? = null,
    val isSplit: Boolean = false,
    val totalSizeBytes: Long = 0L
)

data class ResolvedRepoInfo(
    val repoId: String,
    val requestedRevision: String,
    val commitSha: String,
    val files: List<RepoFile>,
    val selectedFile: String? = null
)

data class ResolvedVariantsSelection(
    val variants: List<ModelVariant>,
    val preselectedVariant: ModelVariant?
)

object HuggingFaceModelResolver {
    private const val HF_HOST = "huggingface.co"

    private val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val SHARD_PATTERN = Regex("""^(.*?)[-_.](\d+)[-_.]of[-_.](\d+)\.gguf$""", RegexOption.IGNORE_CASE)
    private val NEXT_LINK_REGEX = Regex("""<([^>]+)>;\s*rel="next"""", RegexOption.IGNORE_CASE)

    /**
     * Parses structured Hugging Face input with full support for:
     * - Direct repository IDs: "owner/repo"
     * - Web homepages: "https://huggingface.co/owner/repo"
     * - Tree URLs: "https://huggingface.co/owner/repo/tree/branch/subpath"
     * - Blob / Resolve URLs: "https://huggingface.co/owner/repo/blob/revision/path/model.gguf"
     * - File shorthand: "owner/repo/path/model.gguf"
     */
    fun parseInput(input: String): ParsedHfInput {
        val clean = input.trim()
        if (clean.isBlank()) return ParsedHfInput(repoId = "")

        val urlInput = if (clean.startsWith("$HF_HOST/", ignoreCase = true)) "https://$clean" else clean
        val url = urlInput.toHttpUrlOrNull()
        if (url != null && url.host.equals(HF_HOST, ignoreCase = true)) {
            val segments = url.pathSegments.filter { it.isNotBlank() }
            if (segments.size < 2) return ParsedHfInput(repoId = "")

            val owner = segments[0]
            val repo = segments[1]
            val repoId = "$owner/$repo"

            if (segments.size == 2) {
                return ParsedHfInput(repoId = repoId, kind = HfInputKind.REPOSITORY)
            }

            val marker = segments[2].lowercase(Locale.ROOT)
            if (marker == "tree" || marker == "blob" || marker == "resolve") {
                val revision = if (segments.size >= 4) segments[3] else null
                val remainingPath = if (segments.size >= 5) {
                    segments.drop(4).joinToString("/")
                } else null

                val kind = when {
                    marker == "blob" || marker == "resolve" -> HfInputKind.FILE
                    !remainingPath.isNullOrBlank() -> HfInputKind.FOLDER
                    else -> HfInputKind.REPOSITORY
                }
                return ParsedHfInput(
                    repoId = repoId,
                    requestedRevision = revision,
                    path = remainingPath,
                    kind = kind
                )
            }

            // URL with path directly after repo
            val path = segments.drop(2).joinToString("/")
            val isFile = path.endsWith(".gguf", ignoreCase = true)
            return ParsedHfInput(
                repoId = repoId,
                requestedRevision = null,
                path = path,
                kind = if (isFile) HfInputKind.FILE else HfInputKind.FOLDER
            )
        }

        // Shorthand handling: e.g. "owner/repo" or "owner/repo/file.gguf"
        val stripped = clean.removePrefix("https://").removePrefix("http://").removePrefix("$HF_HOST/")
        val segments = stripped.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) {
            return ParsedHfInput(repoId = clean)
        }

        val repoId = "${segments[0]}/${segments[1]}"
        if (segments.size == 2) {
            return ParsedHfInput(repoId = repoId, kind = HfInputKind.REPOSITORY)
        }

        val path = segments.drop(2).joinToString("/")
        val isFile = path.endsWith(".gguf", ignoreCase = true)
        return ParsedHfInput(
            repoId = repoId,
            requestedRevision = null,
            path = path,
            kind = if (isFile) HfInputKind.FILE else HfInputKind.FOLDER
        )
    }

    /**
     * Backward-compatible parse helper.
     */
    fun parseRepoAndPath(input: String): Pair<String, String?> {
        val parsed = parseInput(input)
        return Pair(parsed.repoId, parsed.path)
    }

    /**
     * Queries the Hugging Face API to resolve the repository's immutable commit SHA
     * and recursive file tree with pagination.
     */
    suspend fun resolveRepository(
        input: String,
        token: String? = null,
        revision: String? = null
    ): Result<ResolvedRepoInfo> = withContext(Dispatchers.IO) {
        val parsed = parseInput(input)
        val repoId = parsed.repoId
        if (repoId.isBlank() || !repoId.contains('/')) {
            return@withContext Result.failure(IllegalArgumentException("Invalid Hugging Face repository identifier: '$input'"))
        }

        val effectiveRevision = revision?.ifBlank { null } ?: parsed.requestedRevision ?: "main"
        val client = ModelDownloadUrlPolicy.configureClient(baseHttpClient.newBuilder()) { token }.build()

        // 1. Resolve commit SHA via /api/models/{repoId}/revision/{encodedRevision}
        val modelApiUrl = HttpUrl.Builder()
            .scheme("https")
            .host(HF_HOST)
            .addPathSegment("api")
            .addPathSegment("models")
            .apply {
                repoId.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) }
            }
            .addPathSegment("revision")
            .addPathSegment(effectiveRevision)
            .build()
        val modelRequest = Request.Builder()
            .url(modelApiUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "Solus/1.0 Android")
            .build()

        val modelResponse = try {
            client.newCall(modelRequest).execute()
        } catch (e: IOException) {
            return@withContext Result.failure(IOException("Failed to connect to Hugging Face API: ${e.message}", e))
        }

        val modelCode = modelResponse.code
        val modelBody = modelResponse.body.string()
        modelResponse.close()

        if (modelCode == 401 || modelCode == 403) {
            return@withContext Result.failure(
                IOException(
                    "Hugging Face repository '$repoId' requires authentication or access permission (HTTP $modelCode). " +
                        "Please configure your Hugging Face token in Settings (⚙️)."
                )
            )
        }
        if (modelCode == 404) {
            return@withContext Result.failure(
                IOException(
                    "Hugging Face repository '$repoId' (revision '$effectiveRevision') was not found or is inaccessible (HTTP 404). " +
                        "If this is a private or gated repository, please configure your Hugging Face token in Settings (⚙️)."
                )
            )
        }
        if (modelCode !in 200..299) {
            return@withContext Result.failure(
                IOException("Hugging Face API returned error HTTP $modelCode: ${modelBody.take(200)}")
            )
        }

        val json = try {
            JSONObject(modelBody)
        } catch (e: Exception) {
            return@withContext Result.failure(IOException("Failed to parse repository metadata from Hugging Face: ${e.message}", e))
        }

        val commitSha = json.optString("sha", "").trim()
        if (commitSha.isBlank()) {
            return@withContext Result.failure(
                IOException("Failed to resolve immutable commit SHA for revision '$effectiveRevision' in repository '$repoId'.")
            )
        }

        // 2. Fetch recursive file tree with pagination
        val allFiles = mutableListOf<RepoFile>()
        val initialTreeUrl = HttpUrl.Builder()
            .scheme("https")
            .host(HF_HOST)
            .addPathSegment("api")
            .addPathSegment("models")
            .apply {
                repoId.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) }
            }
            .addPathSegment("tree")
            .addPathSegment(commitSha)
            .addQueryParameter("recursive", "true")
            .build()
            .toString()
        var nextUrl: String? = initialTreeUrl
        var treeSucceeded = false

        while (!nextUrl.isNullOrBlank()) {
            coroutineContext.ensureActive()
            val treeReq = Request.Builder()
                .url(nextUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "Solus/1.0 Android")
                .build()

            val treeResp = try {
                client.newCall(treeReq).execute()
            } catch (e: IOException) {
                return@withContext Result.failure(IOException("Failed to fetch repository tree from Hugging Face: ${e.message}", e))
            }

            if (!treeResp.isSuccessful) {
                val errCode = treeResp.code
                val errBody = treeResp.body.string().take(200)
                treeResp.close()
                return@withContext Result.failure(
                    IOException("Hugging Face tree API returned error HTTP $errCode: $errBody")
                )
            }

            treeSucceeded = true
            val treeBody = treeResp.body.string()
            val linkHeader = treeResp.header("Link")
            treeResp.close()

            try {
                val array = JSONArray(treeBody)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val type = item.optString("type", "file")
                    if (type != "directory") {
                        val path = item.optString("path", "")
                        val size = item.optLong("size", 0L)
                        val lfsObj = item.optJSONObject("lfs")
                        val lfsOid = lfsObj?.optString("oid")?.ifBlank { null }
                        val lfsSha = lfsObj?.optString("sha256")?.ifBlank { null }
                        val candidate = (lfsOid ?: lfsSha)?.trim()?.lowercase(Locale.ROOT)
                        val sha256 = if (candidate != null && candidate.length == 64 && candidate.all { c -> c in "0123456789abcdef" }) candidate else null
                        if (path.isNotBlank()) {
                            allFiles.add(RepoFile(path = path, sizeBytes = size, sha256 = sha256))
                        }
                    }
                }
            } catch (e: Exception) {
                return@withContext Result.failure(IOException("Failed to parse file tree from Hugging Face: ${e.message}", e))
            }

            nextUrl = if (!linkHeader.isNullOrBlank()) {
                NEXT_LINK_REGEX.find(linkHeader)?.groupValues?.get(1)
            } else null
        }

        // Fallback to siblings from model API response if tree endpoint was unavailable
        if (!treeSucceeded || allFiles.isEmpty()) {
            val siblings = json.optJSONArray("siblings")
            if (siblings != null) {
                for (i in 0 until siblings.length()) {
                    val sib = siblings.getJSONObject(i)
                    val rfilename = sib.optString("rfilename", "")
                    if (rfilename.isNotBlank()) {
                        allFiles.add(RepoFile(path = rfilename))
                    }
                }
            }
        }

        Result.success(
            ResolvedRepoInfo(
                repoId = repoId,
                requestedRevision = effectiveRevision,
                commitSha = commitSha,
                files = allFiles,
                selectedFile = parsed.path
            )
        )
    }

    /**
     * Groups files from a repository into downloadable model variants (single GGUF models or split shard sets),
     * enforcing directory isolation and complete 1..N shard index sequences.
     */
    fun groupVariants(repoInfo: ResolvedRepoInfo): List<ModelVariant> {
        val ggufFiles = repoInfo.files.filter { it.isGguf }
        val projectors = ggufFiles.filter { it.isProjector }
        val nonProjectors = ggufFiles.filter { !it.isProjector }

        val variants = mutableListOf<ModelVariant>()

        // Group split shards by repository directory, base name, and declared total
        val shardGroups = mutableMapOf<String, MutableList<Pair<Int, RepoFile>>>()
        val shardDeclaredTotals = mutableMapOf<String, Int>()
        val shardBaseNames = mutableMapOf<String, String>()
        val singleFiles = mutableListOf<RepoFile>()

        for (file in nonProjectors) {
            val match = SHARD_PATTERN.find(file.fileName)
            if (match != null) {
                val base = match.groupValues[1]
                val idx = match.groupValues[2].toIntOrNull() ?: 1
                val total = match.groupValues[3].toIntOrNull() ?: 1
                val dir = file.directory
                val groupKey = if (dir.isNotBlank()) "$dir/$base-$total" else "$base-$total"

                shardGroups.computeIfAbsent(groupKey) { mutableListOf() }.add(Pair(idx, file))
                shardDeclaredTotals[groupKey] = total
                shardBaseNames[groupKey] = base
            } else {
                singleFiles.add(file)
            }
        }

        // Validate and build split variants: require complete 1..N indices
        for ((groupKey, parts) in shardGroups) {
            val declaredTotal = shardDeclaredTotals[groupKey] ?: parts.size
            val presentIndices = parts.map { it.first }.toSet()
            val expectedIndices = (1..declaredTotal).toSet()

            // Only group if complete and unique indices 1..N are satisfied
            if (presentIndices == expectedIndices && parts.size == declaredTotal) {
                val sorted = parts.sortedBy { it.first }.map { it.second }
                val baseName = shardBaseNames[groupKey] ?: groupKey.substringBeforeLast('-')
                val matchingProj = findMatchingProjector(baseName, projectors)
                variants.add(
                    ModelVariant(
                        name = baseName,
                        mainFiles = sorted,
                        availableProjectors = projectors,
                        projectorFile = matchingProj,
                        isSplit = true,
                        totalSizeBytes = sorted.sumOf { it.sizeBytes } + (matchingProj?.sizeBytes ?: 0L)
                    )
                )
            }
        }

        // Build single file variants
        for (file in singleFiles) {
            val name = file.fileName.substringBeforeLast('.')
            val matchingProj = findMatchingProjector(name, projectors)
            variants.add(
                ModelVariant(
                    name = name,
                    mainFiles = listOf(file),
                    availableProjectors = projectors,
                    projectorFile = matchingProj,
                    isSplit = false,
                    totalSizeBytes = file.sizeBytes + (matchingProj?.sizeBytes ?: 0L)
                )
            )
        }

        // Build single file variants for non-GGUF supported formats (.task and .litertlm)
        val nonGgufSupportedFiles = repoInfo.files.filter { it.isLiteRt || it.isTask }
        for (file in nonGgufSupportedFiles) {
            val name = file.fileName.substringBeforeLast('.')
            variants.add(
                ModelVariant(
                    name = name,
                    mainFiles = listOf(file),
                    availableProjectors = emptyList(),
                    projectorFile = null,
                    isSplit = false,
                    totalSizeBytes = file.sizeBytes
                )
            )
        }

        return variants
    }

    /**
     * Filters and preselects variants according to the parsed Hugging Face input.
     *
     * Rules:
     * - Match decoded repository-relative paths exactly and case-sensitively.
     * - No double URL-decoding (parsed.path is already decoded).
     * - Bounded folder matching by directory path and '/'.
     * - For a shard link, select the complete variant containing that exact shard.
     * - For repository and folder links, require explicit variant selection (preselected = null).
     */
    fun selectVariants(
        parsed: ParsedHfInput,
        allVariants: List<ModelVariant>
    ): ResolvedVariantsSelection {
        if (allVariants.isEmpty()) {
            throw NoSuchElementException("No GGUF model files found in repository ${parsed.repoId}.")
        }

        return when (parsed.kind) {
            HfInputKind.FOLDER -> {
                val folderPrefix = parsed.path?.trim('/')
                val filtered = if (!folderPrefix.isNullOrBlank()) {
                    allVariants.filter { v ->
                        v.mainFiles.any { file ->
                            file.directory == folderPrefix || file.directory.startsWith("$folderPrefix/")
                        }
                    }
                } else {
                    allVariants
                }

                if (filtered.isEmpty()) {
                    throw NoSuchElementException("No GGUF model files found in folder '${parsed.path}' in repository ${parsed.repoId}.")
                }

                ResolvedVariantsSelection(variants = filtered, preselectedVariant = null)
            }

            HfInputKind.FILE -> {
                val reqFile = parsed.path?.trim('/')
                if (reqFile.isNullOrBlank()) {
                    ResolvedVariantsSelection(variants = allVariants, preselectedVariant = null)
                } else {
                    val matched = allVariants.firstOrNull { v ->
                        v.mainFiles.any { it.path == reqFile }
                    } ?: throw NoSuchElementException("Requested file '$reqFile' was not found or is not a valid GGUF model in ${parsed.repoId}.")

                    ResolvedVariantsSelection(variants = allVariants, preselectedVariant = matched)
                }
            }

            HfInputKind.REPOSITORY -> {
                ResolvedVariantsSelection(variants = allVariants, preselectedVariant = null)
            }
        }
    }

    /**
     * Finds a companion projector ONLY when a clear repository-declared naming match exists.
     * No fallback to a random projector.
     */
    private fun findMatchingProjector(modelName: String, projectors: List<RepoFile>): RepoFile? {
        if (projectors.isEmpty()) return null
        val lowerModel = modelName.lowercase(Locale.ROOT)

        // Exact match with mmproj or projector naming pattern
        return projectors.firstOrNull { proj ->
            val lowerProj = proj.fileName.lowercase(Locale.ROOT)
            val projWithoutExt = lowerProj.substringBeforeLast('.')
            projWithoutExt.equals("mmproj-$lowerModel", ignoreCase = true) ||
                projWithoutExt.equals("$lowerModel-mmproj", ignoreCase = true) ||
                (lowerModel.contains("vision") && projWithoutExt.contains("vision") && projWithoutExt.contains(lowerModel.take(6)))
        }
    }

    /**
     * Constructs a validated [ModelDownloadSpec] for downloading a complete multi-artifact bundle
     * with immutable commit SHA resolution.
     */
    fun createDownloadSpec(
        repoInfo: ResolvedRepoInfo,
        variant: ModelVariant,
        modelId: String,
        selectedProjector: RepoFile? = null
    ): ModelDownloadSpec {
        val artifactSpecs = mutableListOf<DownloadArtifactSpec>()
        val commitSha = repoInfo.commitSha.ifBlank { "main" }
        val effectiveProjector = selectedProjector

        variant.mainFiles.forEachIndexed { idx, file ->
            val downloadUrl = HttpUrl.Builder()
                .scheme("https")
                .host(HF_HOST)
                .apply {
                    repoInfo.repoId.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) }
                }
                .addPathSegment("resolve")
                .addPathSegment(commitSha)
                .apply {
                    file.path.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) }
                }
                .addQueryParameter("download", "true")
                .build()
                .toString()
            artifactSpecs.add(
                DownloadArtifactSpec(
                    downloadUrl = downloadUrl,
                    fileName = file.fileName,
                    role = if (variant.isSplit) ArtifactRole.Shard else ArtifactRole.Main,
                    shardIndex = if (variant.isSplit) idx else -1,
                    totalShards = if (variant.isSplit) variant.mainFiles.size else -1,
                    expectedSizeBytes = file.sizeBytes,
                    expectedSha256 = file.sha256
                )
            )
        }

        if (effectiveProjector != null) {
            val downloadUrl = HttpUrl.Builder()
                .scheme("https")
                .host(HF_HOST)
                .apply {
                    repoInfo.repoId.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) }
                }
                .addPathSegment("resolve")
                .addPathSegment(commitSha)
                .apply {
                    effectiveProjector.path.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) }
                }
                .addQueryParameter("download", "true")
                .build()
                .toString()
            artifactSpecs.add(
                DownloadArtifactSpec(
                    downloadUrl = downloadUrl,
                    fileName = effectiveProjector.fileName,
                    role = ArtifactRole.Projector,
                    expectedSizeBytes = effectiveProjector.sizeBytes,
                    expectedSha256 = effectiveProjector.sha256
                )
            )
        }

        val isLiteRt = variant.mainFiles.firstOrNull()?.isLiteRt == true
        val isTask = variant.mainFiles.firstOrNull()?.isTask == true
        val isMultimodal = effectiveProjector != null
        val isAudio = false
        val capabilities = ModelCapabilities(
            supportsText = true,
            supportsVision = isMultimodal,
            supportsAudio = isAudio,
            thinking = com.shounak.localmeshai.models.ThinkingCapability.Unknown
        )

        val format = when {
            isTask -> ModelFormat.MediaPipeTask
            isLiteRt -> ModelFormat.LiteRtLm
            else -> ModelFormat.GGUF
        }

        return ModelDownloadSpec(
            modelId = modelId,
            repository = repoInfo.repoId,
            revision = commitSha,
            artifacts = artifactSpecs,
            packageType = ModelPackage.SingleFile,
            capabilities = capabilities,
            format = format
        )
    }
}
