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
    fun interruptPending() {
        mutableStates.update { states -> states.mapValues { (_, state) ->
            if (state.description == "Queued" || state.description.startsWith("Downloading"))
                RepositoryFileDownloadState("Interrupted; select again to retry") else state
        } }
    }
    fun root(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "repository-files")
    fun queue(context: Context) = File(context.filesDir, "repository-file-queue").apply { mkdirs() }
    fun restoreCompleted(context: Context, repo: ResolvedRepoInfo, files: List<RepoFile>) {
        val transfer = RepositoryFileTransfer(root(context), okhttp3.OkHttpClient())
        files.forEach { file ->
            val target = runCatching { transfer.target(repo.repoId, repo.commitSha, file) }.getOrNull()
            if (target?.isFile == true && (file.sizeBytes <= 0 || target.length() == file.sizeBytes)) {
                update(RepositoryFileTransfer.key(repo.repoId, repo.commitSha, file.path), RepositoryFileDownloadState("Downloaded (files only)", target.absolutePath))
            }
        }
    }
    @Synchronized
    fun enqueue(context: Context, repo: ResolvedRepoInfo, files: List<RepoFile>) {
        require(files.isNotEmpty())
        val transfer = RepositoryFileTransfer(root(context), okhttp3.OkHttpClient())
        files.forEach { transfer.target(repo.repoId, repo.commitSha, it) }
        val id = RepositoryFileTransfer.key(repo.repoId, repo.commitSha, files.map { it.path }.sorted().joinToString(";"))
        val manifest = File(queue(context), "$id.json")
        val json = JSONObject().put("repo", repo.repoId).put("revision", repo.commitSha).put("files", JSONArray().apply {
            files.distinctBy { it.path }.forEach { put(JSONObject().put("path", it.path).put("size", it.sizeBytes).put("sha256", it.sha256)) }
        })
        // Never replace a manifest that the foreground service is already processing.
        val newlyQueued = !manifest.exists()
        if (newlyQueued) {
            val pending = File(manifest.parentFile, "$id.tmp")
            pending.writeText(json.toString())
            check(pending.renameTo(manifest)) { "Could not queue repository files" }
        }
        files.forEach { update(RepositoryFileTransfer.key(repo.repoId, repo.commitSha, it.path), RepositoryFileDownloadState("Queued")) }
        try { ContextCompat.startForegroundService(context, Intent(context, RepositoryFileDownloadService::class.java)) }
        catch (failure: Exception) {
            if (newlyQueued) manifest.delete()
            files.forEach { update(RepositoryFileTransfer.key(repo.repoId, repo.commitSha, it.path), RepositoryFileDownloadState("Could not start download")) }
            throw failure
        }
    }
}
