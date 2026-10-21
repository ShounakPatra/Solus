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
    @Synchronized fun markInitStarted(context: Context, modelId: String) = state(context).markStarted(modelId)
    @Synchronized fun markInitFailed(context: Context, modelId: String) = state(context).markFailed(modelId)
    @Synchronized fun markInitSucceeded(context: Context, modelId: String) {
        if (state(context).markSucceeded(modelId)) _recoveredModels.tryEmit(modelId)
    }
    @Synchronized fun isModelBlocked(context: Context, modelId: String) = state(context).isBlocked(modelId)
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
    private fun blocked() = prefs.getStringSet("blocked_model_ids", emptySet()).orEmpty().toMutableSet()
    private fun verified() = prefs.getStringSet("successful_retry_model_ids", emptySet()).orEmpty().toMutableSet()
    private fun legacyId(id: String) = id.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(128)
    fun isBlocked(id: String) = id.isNotBlank() && blocked().let { id in it || legacyId(id) in it }
    fun hasSuccessfulRetry(id: String) = !isBlocked(id) && id in verified()
    fun isRetryAuthorized(id: String) = id in authorized
    fun authorizeRetry(id: String) { require(isBlocked(id)); authorized.add(id) }
    fun markStarted(id: String) {
        require(id.isNotBlank())
        check(!isBlocked(id) || authorized.remove(id)) { "Choose Try Anyway before retrying a crashed model" }
        check(prefs.edit().putString("init_in_progress_model_id", id).commit()) { "Could not record model initialization" }
    }
    fun markFailed(id: String) {
        authorized.remove(id)
        if (prefs.getString("init_in_progress_model_id", null) == id) {
            prefs.edit().remove("init_in_progress_model_id").commit() // A failed write leaves a conservative crash marker; cleanup must still run.
        }
    }
    fun markSucceeded(id: String): Boolean {
        if (prefs.getString("init_in_progress_model_id", null) != id) return false
        val wasBlocked = isBlocked(id)
        val blocked = blocked().apply { remove(id); remove(legacyId(id)) }
        val verified = verified().apply { if (wasBlocked) add(id) }
        check(prefs.edit().remove("init_in_progress_model_id").putStringSet("blocked_model_ids", blocked)
            .putStringSet("successful_retry_model_ids", verified).commit()) { "Could not persist successful initialization" }
        authorized.remove(id)
        return wasBlocked
    }
    fun recoverCrash(): String? {
        val id = prefs.getString("init_in_progress_model_id", null) ?: return null
        val blocked = blocked().apply { add(id) }
        val verified = verified().apply { remove(id) }
        check(prefs.edit().remove("init_in_progress_model_id").putStringSet("blocked_model_ids", blocked)
            .putStringSet("successful_retry_model_ids", verified).commit()) { "Could not record initialization crash" }
        authorized.remove(id)
        return id
    }
}
