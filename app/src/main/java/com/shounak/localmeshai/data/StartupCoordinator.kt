package com.shounak.localmeshai.data

import android.content.Context
import com.shounak.localmeshai.utils.CacheCleanupCoordinator
import com.shounak.localmeshai.utils.CleanupReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class StoreLoadState {
    data object Loading : StoreLoadState()
    data object Ready : StoreLoadState()
    data class RecoveryRequired(val reason: String, val file: File) : StoreLoadState()
    data class Error(val cause: Throwable) : StoreLoadState()
}

class StartupCoordinator private constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _chatStoreState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    val chatStoreState: StateFlow<StoreLoadState> = _chatStoreState.asStateFlow()

    private val _visionStoreState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    val visionStoreState: StateFlow<StoreLoadState> = _visionStoreState.asStateFlow()

    private val _customModelStoreState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    val customModelStoreState: StateFlow<StoreLoadState> = _customModelStoreState.asStateFlow()

    private val _memoryStoreState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    val memoryStoreState: StateFlow<StoreLoadState> = _memoryStoreState.asStateFlow()

    private val _ragStoreState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    val ragStoreState: StateFlow<StoreLoadState> = _ragStoreState.asStateFlow()

    private var firstFrameRendered = false
    private val deferredTasksLock = Any()
    private val crashGuardDeferred = CompletableDeferred<String?>()

    init {
        scope.launch {
            val crashedModel = runCatching {
                com.shounak.localmeshai.utils.InitCrashGuard.checkAndRecoverCrash(context)
            }.getOrNull()
            if (crashedModel != null) {
                runCatching {
                    com.shounak.localmeshai.utils.CrashReportManager.recordNativeCrash(
                        context,
                        crashedModel,
                        "Model '$crashedModel' crashed during native initialization on ${com.shounak.localmeshai.utils.DeviceUtils.currentDeviceChipLabel()}."
                    )
                }
            }
            crashGuardDeferred.complete(crashedModel)
        }
    }

    suspend fun awaitCrashGuardChecked(): String? = crashGuardDeferred.await()

    fun updateChatStoreState(state: StoreLoadState) {
        _chatStoreState.value = state
    }

    fun updateVisionStoreState(state: StoreLoadState) {
        _visionStoreState.value = state
    }

    fun updateCustomModelStoreState(state: StoreLoadState) {
        _customModelStoreState.value = state
    }

    fun updateMemoryStoreState(state: StoreLoadState) {
        _memoryStoreState.value = state
    }

    fun updateRagStoreState(state: StoreLoadState) {
        _ragStoreState.value = state
    }

    val isReady: Boolean
        get() = (_chatStoreState.value !is StoreLoadState.Loading) && (_visionStoreState.value !is StoreLoadState.Loading)

    fun onFirstFrameRendered() {
        synchronized(deferredTasksLock) {
            if (firstFrameRendered) return
            firstFrameRendered = true
        }
        scope.launch {
            runDeferredStartupWork()
        }
    }

    suspend fun runDeferredStartupWork() {
        // 1. Trigger coalesced cache cleanup via CacheCleanupCoordinator
        try {
            CacheCleanupCoordinator.getInstance(context).requestCleanup(CleanupReason.Reopen)
        } catch (_: Throwable) {}

        // 2. Schedule background update check if enabled
        try {
            val settings = com.shounak.localmeshai.utils.AppSettings.getInstance(context).settings.value
            if (settings.autoCheckUpdates) {
                com.shounak.localmeshai.utils.AppUpdateManager.scheduleBackgroundUpdateCheck(context)
            }
        } catch (_: Throwable) {}
    }

    companion object {
        @Volatile
        private var instance: StartupCoordinator? = null

        fun getInstance(context: Context): StartupCoordinator {
            return instance ?: synchronized(this) {
                instance ?: StartupCoordinator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
