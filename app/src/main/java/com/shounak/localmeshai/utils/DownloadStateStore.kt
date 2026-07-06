package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadSnapshot(
    val modelId: String,
    val status: ModelStatus,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val bytesPerSecond: Long = 0L,
    val localPath: String? = null,
    val errorMessage: String? = null
)

object DownloadStateStore {
    private val _snapshots = MutableStateFlow<Map<String, DownloadSnapshot>>(emptyMap())
    val snapshots = _snapshots.asStateFlow()

    fun update(snapshot: DownloadSnapshot) {
        _snapshots.value = _snapshots.value + (snapshot.modelId to snapshot)
    }

    fun get(modelId: String): DownloadSnapshot? = _snapshots.value[modelId]
}
