package com.shounak.localmeshai.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shounak.localmeshai.MainActivity
import com.shounak.localmeshai.R
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.ModelDownloadUrlPolicy
import com.shounak.localmeshai.utils.RepoFile
import com.shounak.localmeshai.utils.RepositoryFileDownloads
import com.shounak.localmeshai.utils.RepositoryFileDownloadState
import com.shounak.localmeshai.utils.RepositoryFileTransfer
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class RepositoryDownloadNotificationLifecycle {
    private var preserveCompletion = false

    fun foregroundStarted() { preserveCompletion = false }
    fun completionPosted() { preserveCompletion = true }
    fun shouldRemoveOnDestroy(): Boolean = !preserveCompletion
}

/** Durable files-only queue; it never registers these downloads as runnable models. */
class RepositoryFileDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var drainJob: Job? = null
    private var wakeRequested = false
    private var latestStartId = 0
    private val notificationLock = Any()
    @Volatile private var notificationAllowed = false
    @Volatile private var activeCall: Call? = null
    private lateinit var notifications: NotificationManager
    private val notificationLifecycle = RepositoryDownloadNotificationLifecycle()
    private var failures = 0
    private var completed = 0
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Repository file downloads", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            wakeRequested = false
            drainJob?.cancel()
            activeCall?.cancel()
            RepositoryFileDownloads.queue(this).listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() }
            RepositoryFileDownloads.interruptPending()
            removeNotification()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            val notification = notification("Starting repository downloads", null)
            synchronized(notificationLock) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else startForeground(NOTIFICATION_ID, notification)
                notificationLifecycle.foregroundStarted()
                notificationAllowed = true
            }
        } catch (failure: Exception) {
            RepositoryFileDownloads.interruptQueued(this, "Could not start foreground download; reopen Solus to resume")
            wakeRequested = false
            drainJob?.cancel()
            activeCall?.cancel()
            removeNotification()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startDrain()
        return START_NOT_STICKY
    }

    private fun removeNotification() = synchronized(notificationLock) {
        notificationAllowed = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTIFICATION_ID)
    }

    private fun pending() = RepositoryFileDownloads.queue(this).listFiles()?.filter { it.extension == "json" }.orEmpty()

    private fun startDrain() {
        if (drainJob?.isActive == true) { wakeRequested = true; return }
        wakeRequested = false
        val previous = drainJob
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val thisJob = currentCoroutineContext()[Job]
            try {
                // A cancelled transfer still owns its partial file until it has unwound.
                previous?.join()
                ensureActive()
                val client = ModelDownloadUrlPolicy.configureClient(OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)) {
                    AppSettings.getInstance(applicationContext).settings.value.huggingFaceToken.ifBlank { null }
                }.build()
                val transfer = RepositoryFileTransfer(RepositoryFileDownloads.root(applicationContext), client)
                while (true) {
                    ensureActive()
                    val manifest = pending().firstOrNull() ?: break
                    try {
                        val json = JSONObject(manifest.readText())
                        val repo = json.getString("repo")
                        val revision = json.getString("revision")
                        val files = json.getJSONArray("files")
                        for (index in 0 until files.length()) {
                            ensureActive()
                            val entry = files.getJSONObject(index)
                            val file = RepoFile(entry.getString("path"), entry.optLong("size"), entry.optString("sha256").takeIf { it.length == 64 })
                            val key = RepositoryFileTransfer.key(repo, revision, file.path)
                            try {
                                RepositoryFileDownloads.update(key, RepositoryFileDownloadState("Downloading"))
                                val saved = transfer.download(repo, revision, file, checkActive = { ensureActive() },
                                    onCall = { activeCall = it }, onProgress = { bytes, total ->
                                        ensureActive()
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - lastNotificationAt >= 500) {
                                            lastNotificationAt = now
                                            val percent = if (total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
                                            RepositoryFileDownloads.update(key, RepositoryFileDownloadState(if (percent == null) "Downloading" else "Downloading $percent%"))
                                            synchronized(notificationLock) {
                                                ensureActive()
                                                if (notificationAllowed) notifications.notify(NOTIFICATION_ID, notification(file.path, percent))
                                            }
                                        }
                                    })
                                ensureActive()
                                completed++
                                RepositoryFileDownloads.update(key, RepositoryFileDownloadState("Downloaded (files only)", saved.absolutePath))
                            } catch (cancelled: CancellationException) {
                                RepositoryFileDownloads.update(key, RepositoryFileDownloadState("Interrupted; select again to retry"))
                                throw cancelled
                            } catch (failure: Exception) {
                                if (!isActive) RepositoryFileDownloads.update(key, RepositoryFileDownloadState("Interrupted; select again to retry"))
                                ensureActive()
                                failures++
                                RepositoryFileDownloads.update(key, RepositoryFileDownloadState("Download failed: ${failure.message}"))
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { failures++; android.util.Log.w("RepositoryFiles", "Invalid repository download manifest", failure) }
                    manifest.delete()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (drainJob !== thisJob) return@withContext
                    drainJob = null
                    if (scope.isActive && wakeRequested) startDrain()
                    else {
                        synchronized(notificationLock) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            if (scope.isActive && notificationAllowed) {
                                notifications.notify(NOTIFICATION_ID, notification("$completed files downloaded; $failures failed", null, finished = true))
                                notificationLifecycle.completionPosted()
                            }
                            notificationAllowed = false
                        }
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }
        drainJob = next
        next.start()
    }

    private fun notification(text: String, percent: Int?, finished: Boolean = false): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, RepositoryFileDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Repository files").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(!finished).setAutoCancel(finished)
            .apply { if (!finished) { setProgress(100, percent ?: 0, percent == null); addAction(0, "Cancel", cancel) } }.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { activeCall?.cancel(); scope.cancel(); RepositoryFileDownloads.interruptPending(); removeNotification(); stopSelfResult(latestStartId) }
    override fun onDestroy() {
        activeCall?.cancel()
        scope.cancel()
        RepositoryFileDownloads.interruptPending()
        if (notificationLifecycle.shouldRemoveOnDestroy()) removeNotification()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "repository_files"
        private const val NOTIFICATION_ID = 17031
        const val ACTION_CANCEL = "com.shounak.localmeshai.CANCEL_REPOSITORY_FILES"
    }
}
