package com.shounak.localmeshai.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Device-local crash records survive retries; only complete runtime initialization clears them. */
object InitCrashGuard {
    private const val PREFS_NAME = "init_crash_guard"
    private const val KEY_LAST_CRASH_MESSAGE = "last_crash_message"
    private val authorizedRetries = mutableSetOf<String>()
    private val _recoveredModels = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val recoveredModels = _recoveredModels.asSharedFlow()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun state(context: Context) = InitCrashState(prefs(context), authorizedRetries)

    @Synchronized fun authorizeRetry(context: Context, modelId: String) = state(context).authorizeRetry(modelId)
    @Synchronized fun isRetryAuthorized(context: Context, modelId: String) = state(context).isRetryAuthorized(modelId)
    @Synchronized fun hasSuccessfulRetry(context: Context, modelId: String) = state(context).hasSuccessfulRetry(modelId)
    /** [backend] is [BACKEND_GPU] or [BACKEND_CPU] when the caller knows it; blank means unknown and is treated like GPU. */
    @Synchronized fun markInitStarted(context: Context, modelId: String, backend: String = "") = state(context).markStarted(modelId, backend)
    @Synchronized fun markInitFailed(context: Context, modelId: String) = state(context).markFailed(modelId)
    @Synchronized fun markInitSucceeded(context: Context, modelId: String) {
        if (state(context).markSucceeded(modelId)) _recoveredModels.tryEmit(modelId)
    }
    @Synchronized fun isModelBlocked(context: Context, modelId: String) = state(context).isBlocked(modelId)
    /** True when [modelId] is blocked and its recorded crash was (or, for pre-backend markers, may have been) a GPU attempt. */
    @Synchronized fun blockedCrashMayHaveUsedGpu(context: Context, modelId: String) = state(context).blockedCrashMayHaveUsedGpu(modelId)
    const val BACKEND_GPU = "GPU"
    const val BACKEND_CPU = "CPU"
    @Synchronized fun checkAndRecoverCrash(context: Context): String? {
        val id = state(context).recoverCrash() ?: return null
        val displayId = id.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(128)
        prefs(context).edit().putString(KEY_LAST_CRASH_MESSAGE,
            "The model $displayId crashed during native initialization. It is blocked on this device. You can explicitly choose Try Anyway to retry; successful initialization clears the block.").commit()
        return id
    }
    @Synchronized fun consumeLastCrashMessage(context: Context): String? {
        val value = prefs(context).getString(KEY_LAST_CRASH_MESSAGE, null)
        if (value != null) prefs(context).edit().remove(KEY_LAST_CRASH_MESSAGE).apply()
        return value
    }
    fun blockedModelMessage(): String {
        val chip = runCatching { DeviceUtils.currentDeviceChipLabel() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "this device"
        return "This model previously crashed during native initialization on $chip. Try Anyway retries initialization; a successful retry clears the device block."
    }
}

/** The same persisted state machine is used by native initialization and host regression tests. */
internal class InitCrashState(private val prefs: SharedPreferences, private val authorized: MutableSet<String> = mutableSetOf()) {
    private companion object { const val IN_PROGRESS_BACKEND = "init_in_progress_backend" }
    private fun blocked() = prefs.getStringSet("blocked_model_ids", emptySet()).orEmpty().toMutableSet()
    private fun verified() = prefs.getStringSet("successful_retry_model_ids", emptySet()).orEmpty().toMutableSet()
    /** Blocked ids whose crash marker proved a CPU-only attempt; absent ids (including legacy blocks) are treated as possible GPU crashes. */
    private fun nonGpuCrashes() = prefs.getStringSet("non_gpu_crash_model_ids", emptySet()).orEmpty().toMutableSet()
    private fun legacyId(id: String) = id.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(128)
    fun isBlocked(id: String) = id.isNotBlank() && blocked().let { id in it || legacyId(id) in it }
    fun hasSuccessfulRetry(id: String) = !isBlocked(id) && id in verified()
    fun isRetryAuthorized(id: String) = id in authorized
    fun blockedCrashMayHaveUsedGpu(id: String) = isBlocked(id) && id !in nonGpuCrashes()
    fun authorizeRetry(id: String) { require(isBlocked(id)); authorized.add(id) }
    fun markStarted(id: String, backend: String = "") {
        require(id.isNotBlank())
        check(!isBlocked(id) || authorized.remove(id)) { "Choose Try Anyway before retrying a crashed model" }
        check(prefs.edit().putString("init_in_progress_model_id", id).putString(IN_PROGRESS_BACKEND, backend).commit()) {
            "Could not record model initialization"
        }
    }
    fun markFailed(id: String) {
        authorized.remove(id)
        if (prefs.getString("init_in_progress_model_id", null) == id) {
            prefs.edit().remove("init_in_progress_model_id").remove(IN_PROGRESS_BACKEND).commit() // A failed write leaves a conservative crash marker; cleanup must still run.
        }
    }
    fun markSucceeded(id: String): Boolean {
        if (prefs.getString("init_in_progress_model_id", null) != id) return false
        val wasBlocked = isBlocked(id)
        val blocked = blocked().apply { remove(id); remove(legacyId(id)) }
        val verified = verified().apply { if (wasBlocked) add(id) }
        val nonGpu = nonGpuCrashes().apply { remove(id) }
        check(prefs.edit().remove("init_in_progress_model_id").remove(IN_PROGRESS_BACKEND).putStringSet("blocked_model_ids", blocked)
            .putStringSet("successful_retry_model_ids", verified).putStringSet("non_gpu_crash_model_ids", nonGpu).commit()) {
            "Could not persist successful initialization"
        }
        authorized.remove(id)
        return wasBlocked
    }
    fun recoverCrash(): String? {
        val id = prefs.getString("init_in_progress_model_id", null) ?: return null
        // Only an explicit CPU marker clears the GPU suspicion; blank (not recorded) and pre-upgrade markers stay conservative.
        val backend = prefs.getString(IN_PROGRESS_BACKEND, null)
        val blocked = blocked().apply { add(id) }
        val verified = verified().apply { remove(id) }
        val nonGpu = nonGpuCrashes().apply { if (backend == InitCrashGuard.BACKEND_CPU) add(id) else remove(id) }
        check(prefs.edit().remove("init_in_progress_model_id").remove(IN_PROGRESS_BACKEND).putStringSet("blocked_model_ids", blocked)
            .putStringSet("successful_retry_model_ids", verified).putStringSet("non_gpu_crash_model_ids", nonGpu).commit()) {
            "Could not record initialization crash"
        }
        authorized.remove(id)
        return id
    }
}
