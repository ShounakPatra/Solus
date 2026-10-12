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
import com.shounak.localmeshai.utils.DownloadProgress
import com.shounak.localmeshai.utils.DownloadResult
import com.shounak.localmeshai.utils.DownloadSnapshot
import com.shounak.localmeshai.utils.DownloadStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var controller: DownloadController
    private lateinit var notificationManager: NotificationManager
    private val downloadStartTimes = mutableMapOf<String, Long>()

    private val downloadEventListener = object : DownloadEventListener {
        override fun onDownloadStarted(request: DownloadRequest, initialSnapshot: DownloadSnapshot) {
            promoteToForegroundImmediately(request)
            notifyProgress(request, initialSnapshot)
        }

        override fun onDownloadProgress(
            request: DownloadRequest,
            progress: DownloadProgress,
            snapshot: DownloadSnapshot
        ) {
            notifyProgress(request, snapshot)
        }

        override fun onDownloadCompleted(
            request: DownloadRequest,
            result: DownloadResult,
            snapshot: DownloadSnapshot
        ) {
            notificationManager.cancel(request.modelId.notificationId())
            notifyEvent(request, "Download complete ✓", "${request.name} is ready to use.")
        }

        override fun onDownloadPaused(request: DownloadRequest, snapshot: DownloadSnapshot) {
            notificationManager.notify(
                request.modelId.notificationId(),
                buildProgressNotification(
                    request = request,
                    title = request.name,
                    text = "Download paused",
                    progress = ((snapshot.progress) * 100).toInt(),
                    indeterminate = false,
                    ongoing = false,
                    showActions = true,
                    pausedActions = true
                )
            )
        }

        override fun onDownloadCancelled(request: DownloadRequest) {
            notificationManager.cancel(request.modelId.notificationId())
        }

        override fun onDownloadFailed(
            request: DownloadRequest,
            error: Throwable,
            snapshot: DownloadSnapshot
        ) {
            val isResumable = snapshot.downloadedBytes > 0L
            if (isResumable) {
                notificationManager.notify(
                    request.modelId.notificationId(),
                    buildProgressNotification(
                        request = request,
                        title = request.name,
                        text = "Download interrupted — tap Retry to continue",
                        progress = (snapshot.progress * 100f).toInt(),
                        indeterminate = snapshot.totalBytes <= 0L,
                        ongoing = false,
                        showActions = true,
                        pausedActions = true
                    )
                )
            } else {
                notificationManager.cancel(request.modelId.notificationId())
                notifyEvent(request, "Download failed ✗", "${request.name}: ${error.message ?: "Download failed"}")
            }
        }

        override fun onAllDownloadsFinished() {
            if (controller.hasActiveOrPendingOperations()) {
                return
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        DownloadStateStore.initialize(applicationContext)
        controller = DownloadController.getInstance(applicationContext)
        controller.eventListener = downloadEventListener
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val request = intent.toDownloadRequest()
                if (request != null) {
                    promoteToForegroundImmediately(request)
                    controller.start(request)
                }
            }
            ACTION_PAUSE -> serviceScope.launch {
                controller.pause(intent.getStringExtra(EXTRA_MODEL_ID).orEmpty())
            }
            ACTION_CANCEL -> serviceScope.launch {
                controller.cancel(intent.getStringExtra(EXTRA_MODEL_ID).orEmpty())
            }
            ACTION_RESUME -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID).orEmpty()
                val request = controller.getRequest(modelId) ?: intent.toDownloadRequest()
                if (request != null) {
                    promoteToForegroundImmediately(request)
                    controller.resume(request)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        controller.pauseAllRetainingStaging()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.pauseAllRetainingStaging()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        controller.eventListener = null
        controller.pauseAllRetainingStaging()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForegroundImmediately(request: DownloadRequest) {
        val snapshot = DownloadStateStore.get(request.modelId)
        val downloadedBytes = snapshot?.downloadedBytes ?: 0L
        val initialText = if (downloadedBytes > 0L) {
            "Resuming from ${formatBytes(downloadedBytes)}"
        } else {
            "Starting download…"
        }
        val initialNotification = buildProgressNotification(
            request = request,
            title = request.name,
            text = initialText,
            progress = ((snapshot?.progress ?: 0f) * 100f).toInt(),
            indeterminate = (snapshot?.totalBytes ?: -1L) <= 0L,
            ongoing = true,
            showActions = true
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                request.modelId.notificationId(),
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                request.modelId.notificationId(),
                initialNotification
            )
        }
    }

    private fun notifyProgress(request: DownloadRequest, snapshot: DownloadSnapshot) {
        val percent = (snapshot.progress * 100).toInt().coerceIn(0, 100)
        if (percent >= 100 && snapshot.totalBytes > 0L) {
            return
        }
        val startTime = downloadStartTimes.getOrPut(request.modelId) { System.currentTimeMillis() }
        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000L).coerceAtLeast(0L)
        val elapsedMin = elapsedSec / 60L
        val elapsedSeconds = elapsedSec % 60L
        val elapsedStr = if (elapsedMin > 0L) {
            String.format(java.util.Locale.US, "%dm %ds", elapsedMin, elapsedSeconds)
        } else {
            String.format(java.util.Locale.US, "%ds", elapsedSeconds)
        }

        val etaStr = if (snapshot.bytesPerSecond > 0L && snapshot.totalBytes > snapshot.downloadedBytes) {
            val remainingBytes = snapshot.totalBytes - snapshot.downloadedBytes
            val etaSec = (remainingBytes / snapshot.bytesPerSecond).coerceAtLeast(1L)
            val etaMin = etaSec / 60L
            val etaSeconds = etaSec % 60L
            if (etaMin > 0L) {
                String.format(java.util.Locale.US, "%dm %ds", etaMin, etaSeconds)
            } else {
                String.format(java.util.Locale.US, "%ds", etaSeconds)
            }
        } else ""

        val speed = "${formatBytes(snapshot.bytesPerSecond)}/s"
        val hasTotalBytes = snapshot.totalBytes > 0L
        val content = if (hasTotalBytes) {
            val etaPart = if (etaStr.isNotBlank()) " • ETA $etaStr" else ""
            "$percent% • ${formatBytes(snapshot.downloadedBytes)} / ${formatBytes(snapshot.totalBytes)} • $speed • Elapsed $elapsedStr$etaPart"
        } else {
            "${formatBytes(snapshot.downloadedBytes)} downloaded • $speed • Elapsed $elapsedStr"
        }
        notificationManager.notify(
            request.modelId.notificationId(),
            buildProgressNotification(
                request = request,
                title = request.name,
                text = content,
                progress = percent,
                indeterminate = !hasTotalBytes,
                ongoing = true,
                showActions = true
            )
        )
    }

    private fun notifyEvent(request: DownloadRequest, title: String, text: String) {
        val notification = NotificationCompat.Builder(this, EVENTS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent(request.modelId.eventNotificationId()))
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(request.modelId.eventNotificationId(), notification)
    }

    private fun buildProgressNotification(
        request: DownloadRequest,
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
            .setContentIntent(openAppPendingIntent(request.modelId.notificationId()))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
        if (showActions) {
            if (pausedActions) {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    serviceActionPendingIntent(request, ACTION_RESUME)
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    serviceActionPendingIntent(request, ACTION_PAUSE)
                )
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                serviceActionPendingIntent(request, ACTION_CANCEL)
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

    private fun serviceActionPendingIntent(request: DownloadRequest, action: String): PendingIntent {
        val intent = Intent(this, ModelDownloadService::class.java)
            .setAction(action)
            .putExtra(EXTRA_MODEL_ID, request.modelId)
            .putExtra(EXTRA_NAME, request.name)
            .putExtra(EXTRA_URL, request.url)
            .putExtra(EXTRA_FILE_NAME, request.fileName)
            .putExtra(EXTRA_PACKAGE_TYPE, request.packageType.name)
            .putExtra(EXTRA_TOKEN, request.bearerToken)
            .putExtra(EXTRA_SHA256, request.sha256)
        return PendingIntent.getService(
            this,
            (request.modelId + action).hashCode().let { if (it == Int.MIN_VALUE) 3 else kotlin.math.abs(it) },
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

    private fun Intent.toDownloadRequest(): DownloadRequest? {
        val modelId = getStringExtra(EXTRA_MODEL_ID) ?: return null
        val url = getStringExtra(EXTRA_URL) ?: return null
        val fileName = getStringExtra(EXTRA_FILE_NAME) ?: return null
        val packageTypeName = getStringExtra(EXTRA_PACKAGE_TYPE) ?: ModelPackage.SingleFile.name

        return DownloadRequest(
            modelId = modelId,
            name = getStringExtra(EXTRA_NAME) ?: modelId,
            url = url,
            fileName = fileName,
            packageType = ModelPackage.valueOf(packageTypeName),
            bearerToken = getStringExtra(EXTRA_TOKEN)?.ifBlank { null },
            sha256 = getStringExtra(EXTRA_SHA256)?.ifBlank { null }
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
        private const val EXTRA_SHA256 = "sha256"

        fun start(
            context: Context,
            modelId: String,
            name: String,
            url: String,
            fileName: String,
            packageType: ModelPackage,
            token: String?,
            sha256: String? = null
        ) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_FILE_NAME, fileName)
                .putExtra(EXTRA_PACKAGE_TYPE, packageType.name)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_SHA256, sha256)
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
            token: String?,
            sha256: String? = null
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
                    .putExtra(EXTRA_SHA256, sha256)
            )
        }

        fun resume(
            context: Context,
            modelId: String,
            name: String,
            url: String,
            fileName: String,
            packageType: ModelPackage,
            token: String?,
            sha256: String? = null
        ) {
            start(context, modelId, name, url, fileName, packageType, token, sha256)
        }
    }
}
