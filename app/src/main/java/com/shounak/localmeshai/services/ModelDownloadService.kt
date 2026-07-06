package com.shounak.localmeshai.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shounak.localmeshai.MainActivity
import com.shounak.localmeshai.R
import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.utils.DownloadSnapshot
import com.shounak.localmeshai.utils.DownloadStateStore
import com.shounak.localmeshai.utils.ModelDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val specs = mutableMapOf<String, DownloadSpec>()
    private val cancelledModelIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private lateinit var downloader: ModelDownloader
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        downloader = ModelDownloader(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.toDownloadSpec()?.let { startDownload(it) }
            ACTION_PAUSE -> pauseDownload(
                modelId = intent.getStringExtra(EXTRA_MODEL_ID).orEmpty(),
                fallbackSpec = intent.toDownloadSpec()
            )
            ACTION_CANCEL -> cancelDownload(
                modelId = intent.getStringExtra(EXTRA_MODEL_ID).orEmpty(),
                fallbackSpec = intent.toDownloadSpec()
            )
            ACTION_RESUME -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID).orEmpty()
                val spec = specs[modelId] ?: intent.toDownloadSpec()
                spec?.let { startDownload(it) }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDownload(spec: DownloadSpec) {
        if (jobs[spec.modelId]?.isActive == true) return

        specs[spec.modelId] = spec
        cancelledModelIds.remove(spec.modelId)
        val previousSnapshot = DownloadStateStore.get(spec.modelId)
        val partialBytes = maxOf(previousSnapshot?.downloadedBytes ?: 0L, downloader.getPartialDownloadBytes(spec.modelId))
        val knownTotalBytes = previousSnapshot?.totalBytes ?: -1L
        val initialProgress = when {
            knownTotalBytes > 0L && partialBytes > 0L -> (partialBytes.toFloat() / knownTotalBytes.toFloat()).coerceIn(0f, 1f)
            previousSnapshot != null -> previousSnapshot.progress
            else -> 0f
        }
        val initialText = if (partialBytes > 0L) {
            "Resuming from ${formatBytes(partialBytes)}"
        } else {
            "Starting download…"
        }
        // Show an indeterminate bar only at the very start before total size is known
        val initialNotification = buildProgressNotification(
            spec = spec,
            title = spec.name,
            text = initialText,
            progress = (initialProgress * 100f).toInt(),
            indeterminate = knownTotalBytes <= 0L,
            ongoing = true,
            showActions = true
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                spec.modelId.notificationId(),
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                spec.modelId.notificationId(),
                initialNotification
            )
        }
        DownloadStateStore.update(
            DownloadSnapshot(
                modelId = spec.modelId,
                status = ModelStatus.Downloading,
                progress = initialProgress,
                downloadedBytes = partialBytes,
                totalBytes = knownTotalBytes
            )
        )

        jobs[spec.modelId] = serviceScope.launch {
            try {
                val result = downloader.downloadModel(
                    url = spec.url,
                    modelId = spec.modelId,
                    fileName = spec.fileName,
                    packageType = spec.packageType,
                    bearerToken = spec.bearerToken
                ) { progress ->
                    val snapshot = DownloadSnapshot(
                        modelId = spec.modelId,
                        status = ModelStatus.Downloading,
                        progress = progress.progress,
                        downloadedBytes = progress.downloadedBytes,
                        totalBytes = progress.totalBytes,
                        bytesPerSecond = progress.bytesPerSecond
                    )
                    DownloadStateStore.update(snapshot)
                    notifyProgress(spec, snapshot)
                }

                val snapshot = DownloadSnapshot(
                    modelId = spec.modelId,
                    status = ModelStatus.Available,
                    progress = 1f,
                    downloadedBytes = result.bytesDownloaded,
                    localPath = result.localPath
                )
                DownloadStateStore.update(snapshot)

                // Remove progress notification
                notificationManager.cancel(spec.modelId.notificationId())

                // ── Notification 2: "Download complete" ──
                notifyEvent(spec, "Download complete ✓", "${spec.name} is ready to use.")
            } catch (cancelled: CancellationException) {
                if (cancelledModelIds.remove(spec.modelId)) {
                    downloader.deleteDownload(spec.modelId, spec.fileName, spec.packageType)
                    specs.remove(spec.modelId)
                    DownloadStateStore.update(
                        DownloadSnapshot(
                            modelId = spec.modelId,
                            status = ModelStatus.NotDownloaded,
                            progress = 0f,
                            downloadedBytes = 0L,
                            totalBytes = -1L
                        )
                    )
                    notificationManager.cancel(spec.modelId.notificationId())
                    return@launch
                }
                val snapshot = DownloadStateStore.get(spec.modelId)
                DownloadStateStore.update(
                    DownloadSnapshot(
                        modelId = spec.modelId,
                        status = ModelStatus.Paused,
                        progress = snapshot?.progress ?: 0f,
                        downloadedBytes = snapshot?.downloadedBytes ?: 0L,
                        totalBytes = snapshot?.totalBytes ?: -1L
                    )
                )
                notificationManager.notify(
                    spec.modelId.notificationId(),
                    buildProgressNotification(
                        spec = spec,
                        title = spec.name,
                        text = "Download paused",
                        progress = ((snapshot?.progress ?: 0f) * 100).toInt(),
                        indeterminate = false,
                        ongoing = false,
                        showActions = true,
                        pausedActions = true
                    )
                )
            } catch (exception: Exception) {
                DownloadStateStore.update(
                    DownloadSnapshot(
                        modelId = spec.modelId,
                        status = ModelStatus.Failed,
                        errorMessage = exception.message ?: "Download failed"
                    )
                )
                // Remove progress notification
                notificationManager.cancel(spec.modelId.notificationId())
                // Post high-priority failure notification (same channel as success)
                notifyEvent(spec, "Download failed ✗", "${spec.name}: ${exception.message ?: "Download failed"}")
            } finally {
                jobs.remove(spec.modelId)
                if (jobs.isEmpty()) {
                    val finalStatus = DownloadStateStore.get(spec.modelId)?.status
                    stopForeground(
                        if (finalStatus == ModelStatus.Paused) {
                            STOP_FOREGROUND_DETACH
                        } else {
                            STOP_FOREGROUND_REMOVE
                        }
                    )
                    stopSelf()
                }
            }
        }
    }

    private fun pauseDownload(modelId: String, fallbackSpec: DownloadSpec? = null) {
        val activeJob = jobs[modelId]
        val snapshot = DownloadStateStore.get(modelId)
        if (activeJob != null) {
            // Service is alive — cancelling the job triggers the CancellationException
            // handler in startDownload which will post the paused notification itself.
            DownloadStateStore.update(
                DownloadSnapshot(
                    modelId = modelId,
                    status = ModelStatus.Paused,
                    progress = snapshot?.progress ?: 0f,
                    downloadedBytes = snapshot?.downloadedBytes ?: 0L,
                    totalBytes = snapshot?.totalBytes ?: -1L
                )
            )
            activeJob.cancel()
        } else {
            // Service was restarted (Android killed it) — the job is gone but the
            // notification with the Pause button is still showing. Update state and
            // post a fresh paused notification so the user gets a Resume button.
            val spec = specs[modelId] ?: fallbackSpec ?: return
            DownloadStateStore.update(
                DownloadSnapshot(
                    modelId = modelId,
                    status = ModelStatus.Paused,
                    progress = snapshot?.progress ?: 0f,
                    downloadedBytes = snapshot?.downloadedBytes ?: 0L,
                    totalBytes = snapshot?.totalBytes ?: -1L
                )
            )
            specs[modelId] = spec
            notificationManager.notify(
                modelId.notificationId(),
                buildProgressNotification(
                    spec = spec,
                    title = spec.name,
                    text = "Download paused",
                    progress = ((snapshot?.progress ?: 0f) * 100).toInt(),
                    indeterminate = false,
                    ongoing = false,
                    showActions = true,
                    pausedActions = true
                )
            )
            // Stop the service since there's nothing left to do.
            stopSelf()
        }
    }

    private fun cancelDownload(modelId: String, fallbackSpec: DownloadSpec? = null) {
        if (modelId.isBlank()) return
        val spec = specs[modelId] ?: fallbackSpec
        cancelledModelIds.add(modelId)
        jobs[modelId]?.cancel()
        if (jobs[modelId] == null) {
            spec?.let { downloader.deleteDownload(it.modelId, it.fileName, it.packageType) }
            specs.remove(modelId)
            cancelledModelIds.remove(modelId)
            DownloadStateStore.update(
                DownloadSnapshot(
                    modelId = modelId,
                    status = ModelStatus.NotDownloaded,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = -1L
                )
            )
        }
        notificationManager.cancel(modelId.notificationId())
    }

    private fun notifyProgress(spec: DownloadSpec, snapshot: DownloadSnapshot) {
        val percent = (snapshot.progress * 100).toInt().coerceIn(0, 100)
        if (percent >= 100 && snapshot.totalBytes > 0L) {
            return
        }
        val speed = "${formatBytes(snapshot.bytesPerSecond)}/s"
        val hasTotalBytes = snapshot.totalBytes > 0L
        val content = if (hasTotalBytes) {
            "$percent% — ${formatBytes(snapshot.downloadedBytes)} of ${formatBytes(snapshot.totalBytes)} — $speed"
        } else {
            "${formatBytes(snapshot.downloadedBytes)} downloaded — $speed"
        }
        // Use a determinate bar once total size is known; stay indeterminate otherwise
        notificationManager.notify(
            spec.modelId.notificationId(),
            buildProgressNotification(
                spec = spec,
                title = spec.name,
                text = content,
                progress = percent,
                indeterminate = !hasTotalBytes,
                ongoing = true,
                showActions = true
            )
        )
    }

    /** Posts a one-shot event notification on the high-priority events channel. */
    private fun notifyEvent(spec: DownloadSpec, title: String, text: String) {
        val notification = NotificationCompat.Builder(this, EVENTS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent(spec.modelId.eventNotificationId()))
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // Use a separate ID space so it doesn't collide with the progress notification.
        notificationManager.notify(spec.modelId.eventNotificationId(), notification)
    }

    private fun buildProgressNotification(
        spec: DownloadSpec,
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
        showActions: Boolean,
        pausedActions: Boolean = false
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent(spec.modelId.notificationId()))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
        if (showActions) {
            if (pausedActions) {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    serviceActionPendingIntent(spec, ACTION_RESUME)
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    serviceActionPendingIntent(spec, ACTION_PAUSE)
                )
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                serviceActionPendingIntent(spec, ACTION_CANCEL)
            )
        }
        return builder.build()
    }

    private fun openAppPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceActionPendingIntent(spec: DownloadSpec, action: String): PendingIntent {
        val intent = Intent(this, ModelDownloadService::class.java)
            .setAction(action)
            .putExtra(EXTRA_MODEL_ID, spec.modelId)
            .putExtra(EXTRA_NAME, spec.name)
            .putExtra(EXTRA_URL, spec.url)
            .putExtra(EXTRA_FILE_NAME, spec.fileName)
            .putExtra(EXTRA_PACKAGE_TYPE, spec.packageType.name)
            .putExtra(EXTRA_TOKEN, spec.bearerToken)
        return PendingIntent.getService(
            this,
            (spec.modelId + action).hashCode().let { if (it == Int.MIN_VALUE) 3 else kotlin.math.abs(it) },
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    "Model download progress",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    EVENTS_CHANNEL_ID,
                    "Model download events",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifies when a model download starts or finishes."
                }
            )
        }
    }

    private fun Intent.toDownloadSpec(): DownloadSpec? {
        val modelId = getStringExtra(EXTRA_MODEL_ID) ?: return null
        val url = getStringExtra(EXTRA_URL) ?: return null
        val fileName = getStringExtra(EXTRA_FILE_NAME) ?: return null
        val packageTypeName = getStringExtra(EXTRA_PACKAGE_TYPE) ?: ModelPackage.SingleFile.name

        return DownloadSpec(
            modelId = modelId,
            name = getStringExtra(EXTRA_NAME) ?: modelId,
            url = url,
            fileName = fileName,
            packageType = ModelPackage.valueOf(packageTypeName),
            bearerToken = getStringExtra(EXTRA_TOKEN)?.ifBlank { null }
        )
    }

    private fun String.notificationId(): Int = hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }
    private fun String.eventNotificationId(): Int = (hashCode() xor 0x4D444C).let { if (it == Int.MIN_VALUE) 2 else kotlin.math.abs(it) }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "--"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) "${value.toInt()} ${units[index]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }

    private data class DownloadSpec(
        val modelId: String,
        val name: String,
        val url: String,
        val fileName: String,
        val packageType: ModelPackage,
        val bearerToken: String?
    )

    companion object {
        private const val PROGRESS_CHANNEL_ID = "model_downloads"
        private const val EVENTS_CHANNEL_ID = "model_download_events_high"
        private const val ACTION_START = "com.shounak.localmeshai.action.START_DOWNLOAD"
        private const val ACTION_PAUSE = "com.shounak.localmeshai.action.PAUSE_DOWNLOAD"
        private const val ACTION_CANCEL = "com.shounak.localmeshai.action.CANCEL_DOWNLOAD"
        private const val ACTION_RESUME = "com.shounak.localmeshai.action.RESUME_DOWNLOAD"
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_PACKAGE_TYPE = "package_type"
        private const val EXTRA_TOKEN = "token"

        fun start(context: Context, modelId: String, name: String, url: String, fileName: String, packageType: ModelPackage, token: String?) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_FILE_NAME, fileName)
                .putExtra(EXTRA_PACKAGE_TYPE, packageType.name)
                .putExtra(EXTRA_TOKEN, token)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, modelId: String) {
            context.startService(
                Intent(context, ModelDownloadService::class.java)
                    .setAction(ACTION_PAUSE)
                    .putExtra(EXTRA_MODEL_ID, modelId)
            )
        }

        fun cancel(
            context: Context,
            modelId: String,
            name: String,
            url: String,
            fileName: String,
            packageType: ModelPackage,
            token: String?
        ) {
            context.startService(
                Intent(context, ModelDownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_MODEL_ID, modelId)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_FILE_NAME, fileName)
                    .putExtra(EXTRA_PACKAGE_TYPE, packageType.name)
                    .putExtra(EXTRA_TOKEN, token)
            )
        }

        fun resume(context: Context, modelId: String, name: String, url: String, fileName: String, packageType: ModelPackage, token: String?) {
            start(context, modelId, name, url, fileName, packageType, token)
        }
    }
}
