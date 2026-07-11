package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelStatus

internal object DownloadRestorationPolicy {
    fun afterProcessRestart(status: ModelStatus, downloadedBytes: Long): ModelStatus = when {
        status == ModelStatus.Downloading -> ModelStatus.Paused
        status == ModelStatus.Failed && downloadedBytes > 0L -> ModelStatus.Paused
        else -> status
    }

    fun afterFailure(downloadedBytes: Long): ModelStatus =
        if (downloadedBytes > 0L) ModelStatus.Paused else ModelStatus.Failed
}
