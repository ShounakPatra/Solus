package com.shounak.localmeshai.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.shounak.localmeshai.services.RepositoryFileDownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class RepositoryFileDownloadState(val description: String, val localPath: String? = null)

internal object RepositoryFileDownloads {
    private val mutableStates = MutableStateFlow<Map<String, RepositoryFileDownloadState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    fun update(key: String, state: RepositoryFileDownloadState) { mutableStates.update { it + (key to state) } }
    fun interruptPending(reason: String = "Interrupted; select again to retry") {
        mutableStates.update { states -> states.mapValues { (_, state) ->
            if (state.description == "Queued" || state.description.startsWith("Downloading"))
                RepositoryFileDownloadState(reason) else state
        } }
    }
    fun root(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "repository-files")
    fun queue(context: Context) = File(context.filesDir, "repository-file-queue").apply { mkdirs() }
    fun restoreCompleted(context: Context, repo: ResolvedRepoInfo, files: List<RepoFile>) {
        val transfer = RepositoryFileTransfer(root(context), okhttp3.OkHttpClient())
        files.forEach { file ->
            val target = runCatching { transfer.existingTarget(repo.repoId, repo.commitSha, file) }.getOrNull()
            if (target?.isFile == true && (file.sizeBytes <= 0 || target.length() == file.sizeBytes)) {
                update(RepositoryFileTransfer.key(repo.repoId, repo.commitSha, file.path), RepositoryFileDownloadState("Downloaded (files only)", target.absolutePath))
            }
        }
    }
    fun interruptQueued(context: Context, reason: String) {
        interruptPending(reason)
        queue(context).listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
            runCatching {
                val batch = JSONObject(file.readText())
                val files = batch.getJSONArray("files")
                for (i in 0 until files.length()) {
                    val key = RepositoryFileTransfer.key(batch.getString("repo"), batch.getString("revision"), files.getJSONObject(i).getString("path"))
                    if (mutableStates.value[key]?.localPath == null) update(key, RepositoryFileDownloadState(reason))
                }
            }
        }
    }
    fun resumePending(context: Context) {
        if (queue(context).listFiles().orEmpty().none { it.extension == "json" }) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, RepositoryFileDownloadService::class.java)) }
    }
    fun cancel(context: Context) {
        context.startService(Intent(context, RepositoryFileDownloadService::class.java).setAction(RepositoryFileDownloadService.ACTION_CANCEL))
    }
    @Synchronized
    fun enqueue(context: Context, repo: ResolvedRepoInfo, files: List<RepoFile>) {
        require(files.isNotEmpty())
        val transfer = RepositoryFileTransfer(root(context), okhttp3.OkHttpClient())
        files.forEach { transfer.target(repo.repoId, repo.commitSha, it) }
        val queuedFiles = queue(context).listFiles().orEmpty().filter { it.extension == "json" }.flatMap { manifest ->
            runCatching {
                val batch = JSONObject(manifest.readText())
                if (batch.optString("repo") != repo.repoId || batch.optString("revision") != repo.commitSha) emptyList()
                else {
                    val entries = batch.getJSONArray("files")
                    (0 until entries.length()).map(entries::getJSONObject)
                }
            }.getOrDefault(emptyList())
        }
        val selected = files.distinctBy { it.path }.filter { file ->
            val state = mutableStates.value[RepositoryFileTransfer.key(repo.repoId, repo.commitSha, file.path)]
            val pending = state == null || state.description == "Queued" || state.description.startsWith("Downloading")
            !pending || queuedFiles.none { it.optString("path") == file.path &&
                it.optLong("size") == file.sizeBytes && it.optString("sha256") == (file.sha256 ?: "") }
        }
        if (selected.isEmpty()) {
            resumePending(context)
            return
        }
        val id = java.util.UUID.randomUUID().toString()
        val manifest = File(queue(context), "$id.json")
        val json = JSONObject().put("repo", repo.repoId).put("revision", repo.commitSha).put("files", JSONArray().apply {
            selected.forEach { put(JSONObject().put("path", it.path).put("size", it.sizeBytes).put("sha256", it.sha256)) }
        })
        // Never replace a manifest that the foreground service is already processing.
        val pending = File(manifest.parentFile, "$id.tmp")
        pending.writeText(json.toString())
        check(pending.renameTo(manifest)) { "Could not queue repository files" }
        selected.forEach { update(RepositoryFileTransfer.key(repo.repoId, repo.commitSha, it.path), RepositoryFileDownloadState("Queued")) }
        try { ContextCompat.startForegroundService(context, Intent(context, RepositoryFileDownloadService::class.java)) }
        catch (failure: Exception) {
            selected.forEach { update(RepositoryFileTransfer.key(repo.repoId, repo.commitSha, it.path), RepositoryFileDownloadState("Could not start download")) }
            throw failure
        }
    }
}
