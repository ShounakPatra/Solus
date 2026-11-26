package com.shounak.localmeshai

import kotlinx.coroutines.launch
import android.app.Application
import android.content.ComponentCallbacks2
import com.shounak.localmeshai.utils.CacheCleanupCoordinator
import com.shounak.localmeshai.utils.CleanupReason

class LocalMeshAIApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        com.shounak.localmeshai.utils.CrashReportManager.install(this)
        com.shounak.localmeshai.utils.AbandonedModelFiles.initialize()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.shounak.localmeshai.ai.tools.ToolReminders.reschedule(applicationContext) }
                .onFailure { android.util.Log.w("ToolReminders", "Could not recover reminders", it) }
        }
        // Ensure update notification channel exists (lightweight framework channel creation, no disk I/O)
        com.shounak.localmeshai.utils.AppUpdateManager.ensureNotificationChannel(this)

        // Eager application-scoped composition root for singletons (no disk reads on main thread)
        com.shounak.localmeshai.data.StartupCoordinator.getInstance(this)
        com.shounak.localmeshai.data.ChatSessionRepository.getInstance(this)
        com.shounak.localmeshai.utils.ModelUsageStore.get(this)

        // Attach process-level lifecycle observer for background/reopen cleanup without rotation churn
        val coordinator = CacheCleanupCoordinator.getInstance(this)
        coordinator.attachProcessLifecycle()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            com.shounak.localmeshai.ai.tts.TinyVocaManager.releaseIfInitialized("onTrimMemory($level)")
            CacheCleanupCoordinator.getInstance(this).requestCleanup(CleanupReason.BackgroundClose)
        }
        // Keep the active T2I ONNX session loaded across app switches/UI_HIDDEN so it only auto-unloads
        // on the user-configured autoUnloadMinutes timer, releasing early only under critical kernel pressure.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            com.shounak.localmeshai.ai.image.OnnxImageGenerator.releaseIfInitialized()
        }
    }
}
