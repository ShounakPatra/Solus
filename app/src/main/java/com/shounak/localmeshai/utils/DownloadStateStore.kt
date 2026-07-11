package com.shounak.localmeshai.utils

import android.content.Context
import android.content.SharedPreferences
import com.shounak.localmeshai.models.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

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
    private const val PREFS_NAME = "model_download_state"
    private const val KEY_PREFIX = "snapshot_"
    private const val PERSIST_INTERVAL_MS = 1_000L
    private const val PERSIST_BYTE_STEP = 4L * 1024L * 1024L

    private val _snapshots = MutableStateFlow<Map<String, DownloadSnapshot>>(emptyMap())
    val snapshots = _snapshots.asStateFlow()
    private var preferences: SharedPreferences? = null
    private val lastPersisted = mutableMapOf<String, DownloadSnapshot>()
    private val lastPersistedAt = mutableMapOf<String, Long>()

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences = prefs
        val restored = buildMap {
            prefs.all.forEach { (key, value) ->
                if (!key.startsWith(KEY_PREFIX)) return@forEach
                val json = value as? String ?: return@forEach
                runCatching { decode(json) }.getOrNull()?.let { snapshot ->
                    put(snapshot.modelId, snapshot)
                    lastPersisted[snapshot.modelId] = snapshot
                }
            }
        }
        if (restored.isNotEmpty()) {
            _snapshots.value = _snapshots.value + restored
        }
    }

    @Synchronized
    fun update(snapshot: DownloadSnapshot) {
        _snapshots.value = _snapshots.value + (snapshot.modelId to snapshot)
        persist(snapshot)
    }

    fun get(modelId: String): DownloadSnapshot? = _snapshots.value[modelId]

    private fun persist(snapshot: DownloadSnapshot) {
        val prefs = preferences ?: return
        val key = KEY_PREFIX + snapshot.modelId
        if (snapshot.status == ModelStatus.NotDownloaded) {
            lastPersisted.remove(snapshot.modelId)
            lastPersistedAt.remove(snapshot.modelId)
            prefs.edit().remove(key).apply()
            return
        }

        val previous = lastPersisted[snapshot.modelId]
        val now = System.currentTimeMillis()
        val shouldPersist = previous == null ||
            snapshot.status != previous.status ||
            snapshot.totalBytes != previous.totalBytes ||
            snapshot.localPath != previous.localPath ||
            snapshot.errorMessage != previous.errorMessage ||
            kotlin.math.abs(snapshot.downloadedBytes - previous.downloadedBytes) >= PERSIST_BYTE_STEP ||
            now - (lastPersistedAt[snapshot.modelId] ?: 0L) >= PERSIST_INTERVAL_MS
        if (!shouldPersist) return

        lastPersisted[snapshot.modelId] = snapshot
        lastPersistedAt[snapshot.modelId] = now
        prefs.edit().putString(key, encode(snapshot)).apply()
    }

    private fun encode(snapshot: DownloadSnapshot): String = JSONObject().apply {
        put("modelId", snapshot.modelId)
        put("status", snapshot.status.name)
        put("progress", snapshot.progress.toDouble())
        put("downloadedBytes", snapshot.downloadedBytes)
        put("totalBytes", snapshot.totalBytes)
        put("bytesPerSecond", snapshot.bytesPerSecond)
        put("localPath", snapshot.localPath ?: JSONObject.NULL)
        put("errorMessage", snapshot.errorMessage ?: JSONObject.NULL)
    }.toString()

    private fun decode(value: String): DownloadSnapshot {
        val json = JSONObject(value)
        val downloadedBytes = json.optLong("downloadedBytes", 0L)
        val restoredStatus = ModelStatus.valueOf(json.getString("status"))
        val normalizedStatus = DownloadRestorationPolicy.afterProcessRestart(restoredStatus, downloadedBytes)
        return DownloadSnapshot(
            modelId = json.getString("modelId"),
            status = normalizedStatus,
            progress = json.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
            downloadedBytes = downloadedBytes,
            totalBytes = json.optLong("totalBytes", -1L),
            bytesPerSecond = json.optLong("bytesPerSecond", 0L),
            localPath = if (json.isNull("localPath")) null else json.optString("localPath"),
            errorMessage = when {
                restoredStatus == ModelStatus.Downloading -> "Download interrupted. Tap Resume to continue."
                json.isNull("errorMessage") -> null
                else -> json.optString("errorMessage")
            }
        )
    }
}
