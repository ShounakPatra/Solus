package com.shounak.localmeshai

import android.app.Application
import android.content.ComponentCallbacks2
import com.shounak.localmeshai.utils.CacheCleanupCoordinator
import com.shounak.localmeshai.utils.CleanupReason

class LocalMeshAIApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Ensure update notification channel exists (lightweight framework channel creation, no disk I/O)
        com.shounak.localmeshai.utils.AppUpdateManager.ensureNotificationChannel(this)

        // Eager application-scoped composition root for singletons (no disk reads on main thread)
        com.shounak.localmeshai.data.StartupCoordinator.getInstance(this)
        com.shounak.localmeshai.data.ChatSessionRepository.getInstance(this)

        // Attach process-level lifecycle observer for background/reopen cleanup without rotation churn
        val coordinator = CacheCleanupCoordinator.getInstance(this)
        coordinator.attachProcessLifecycle()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            CacheCleanupCoordinator.getInstance(this).requestCleanup(CleanupReason.BackgroundClose)
        }
    }
}
