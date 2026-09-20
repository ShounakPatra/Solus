package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelStatus

internal object DownloadRestorationPolicy {
    fun afterProcessRestart(status: ModelStatus, downloadedBytes: Long): ModelStatus = when {
        status == ModelStatus.Downloading -> ModelStatus.Paused
        else -> status
    }

    fun afterFailure(downloadedBytes: Long): ModelStatus = ModelStatus.Failed
}

