package com.shounak.localmeshai.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks model initialization attempts so we can detect native crashes (SIGABRT)
 * from LiteRT-LM's Engine.initialize() on incompatible native backends.
 *
 * Flow:
 * 1. Before calling Engine.initialize(), call markInitStarted(modelId)
 * 2. After successful init, call markInitCompleted()
 * 3. On next app launch, call checkAndRecoverCrash():
 *    - If a model ID is still "in progress", the previous init crashed the process
 *    - A crash message is saved for display on next launch
 *    - The model is NOT permanently blocked — user can try again
 */
object InitCrashGuard {
    private const val PREFS_NAME = "init_crash_guard"
    private const val KEY_INIT_IN_PROGRESS = "init_in_progress_model_id"
    private const val KEY_LAST_CRASH_MESSAGE = "last_crash_message"
    private const val KEY_BLOCKED_MODEL_IDS = "blocked_model_ids"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Call BEFORE attempting native model init. */
    fun markInitStarted(context: Context, modelId: String) {
        prefs(context).edit()
            .putString(KEY_INIT_IN_PROGRESS, modelId)
            .commit() // Use commit() (synchronous) so the flag is saved before native code runs
    }

    /** Call AFTER native model init succeeds. */
    fun markInitCompleted(context: Context) {
        prefs(context).edit()
            .remove(KEY_INIT_IN_PROGRESS)
            .commit()
    }

    /**
     * Call on app launch. If a model ID is still "in progress",
     * the previous init crashed the process. Save a message for the user.
     * Returns the crashed model ID (or null if no crash happened).
     */
    fun checkAndRecoverCrash(context: Context): String? {
        val crashedModelId = prefs(context).getString(KEY_INIT_IN_PROGRESS, null)
        if (crashedModelId != null) {
            blockModel(context, crashedModelId)
            prefs(context).edit()
                .remove(KEY_INIT_IN_PROGRESS)
                .putString(
                    KEY_LAST_CRASH_MESSAGE,
                    "The model \"$crashedModelId\" crashed during native initialization. " +
                    "It has been blocked on ${DeviceUtils.currentDeviceChipLabel()} so selecting it will not crash the app again. " +
                    "Use the recommended MediaPipe .task model instead."
                )
                .commit()
        }
        return crashedModelId
    }

    /** Returns and clears the last crash message for display. */
    fun consumeLastCrashMessage(context: Context): String? {
        val msg = prefs(context).getString(KEY_LAST_CRASH_MESSAGE, null)
        if (msg != null) {
            prefs(context).edit().remove(KEY_LAST_CRASH_MESSAGE).apply()
        }
        return msg
    }

    fun isModelBlocked(context: Context, modelId: String): Boolean {
        if (modelId.isBlank()) return false
        return prefs(context).getStringSet(KEY_BLOCKED_MODEL_IDS, emptySet()).orEmpty().contains(modelId)
    }

    fun blockedModelMessage(): String {
        return "This model was blocked after it crashed during native initialization on ${DeviceUtils.currentDeviceChipLabel()}. Use a MediaPipe .task model on this device instead."
    }

    fun unblockModel(context: Context, modelId: String) {
        if (modelId.isBlank()) return
        val blocked = prefs(context)
            .getStringSet(KEY_BLOCKED_MODEL_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        if (blocked.remove(modelId)) {
            prefs(context).edit()
                .putStringSet(KEY_BLOCKED_MODEL_IDS, blocked)
                .commit()
        }
    }

    private fun blockModel(context: Context, modelId: String) {
        if (modelId.isBlank()) return
        val blocked = prefs(context)
            .getStringSet(KEY_BLOCKED_MODEL_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(modelId) }
        prefs(context).edit()
            .putStringSet(KEY_BLOCKED_MODEL_IDS, blocked)
            .commit()
    }
}
