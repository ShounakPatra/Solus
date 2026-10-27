package com.shounak.localmeshai.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.shounak.localmeshai.features.FeatureTestPreferences
import org.junit.Assert.*
import org.junit.Test

class InitCrashGuardTest {
    private fun crashedPrefs(vararg ids: String) = FeatureTestPreferences(mapOf("blocked_model_ids" to ids.toSet()))
    private fun denied(block: () -> Unit) { try { block(); fail("Expected retry to be blocked") } catch (_: IllegalStateException) { } }

    @Test fun explicitAuthorizationDoesNotEraseBlockAndAllowsOnlyOneAttempt() {
        val prefs = crashedPrefs("m")
        val state = InitCrashState(prefs)
        denied { state.markStarted("m") }
        state.authorizeRetry("m")
        assertTrue(state.isBlocked("m")); assertFalse(state.hasSuccessfulRetry("m"))
        state.markStarted("m")
        assertFalse(state.isRetryAuthorized("m")); assertTrue(state.isBlocked("m"))
        state.markFailed("m")
        assertTrue(state.isBlocked("m")); assertFalse(state.hasSuccessfulRetry("m"))
        denied { state.markStarted("m") }
        assertNull(prefs.getString("init_in_progress_model_id", null))
    }
    @Test fun fullSuccessPersistsRecoveryAcrossRestartAndOnlyForThatModel() {
        val prefs = crashedPrefs("first", "second")
        val state = InitCrashState(prefs)
        state.authorizeRetry("first"); state.markStarted("first")
        assertTrue(state.markSucceeded("first"))
        val restarted = InitCrashState(prefs)
        assertFalse(restarted.isBlocked("first")); assertTrue(restarted.hasSuccessfulRetry("first"))
        assertTrue(restarted.isBlocked("second")); assertFalse(restarted.hasSuccessfulRetry("second"))
        assertNull(restarted.recoverCrash())
    }
    @Test fun retryAuthorizationDoesNotSurviveProcessRestart() {
        val prefs = crashedPrefs("m")
        InitCrashState(prefs).authorizeRetry("m")
        val restarted = InitCrashState(prefs)
        assertFalse(restarted.isRetryAuthorized("m")); assertTrue(restarted.isBlocked("m"))
        denied { restarted.markStarted("m") }
    }
    @Test fun abortDuringRetriedInitializationLeavesModelBlockedOnRestart() {
        val prefs = crashedPrefs("m")
        val state = InitCrashState(prefs)
        state.authorizeRetry("m"); state.markStarted("m")
        // Simulate a process dying before either success or caught-failure cleanup.
        val restarted = InitCrashState(prefs)
        assertEquals("m", restarted.recoverCrash())
        assertTrue(restarted.isBlocked("m")); assertFalse(restarted.hasSuccessfulRetry("m"))
        assertNull(restarted.recoverCrash())
        denied { restarted.markStarted("m") }
    }
    @Test fun laterNativeCrashRevokesSuccessfulDeviceException() {
        val prefs = crashedPrefs("m")
        val state = InitCrashState(prefs)
        state.authorizeRetry("m"); state.markStarted("m"); state.markSucceeded("m")
        assertTrue(state.hasSuccessfulRetry("m"))
        state.markStarted("m")
        val restarted = InitCrashState(prefs)
        assertEquals("m", restarted.recoverCrash())
        assertFalse(restarted.hasSuccessfulRetry("m")); assertTrue(restarted.isBlocked("m"))
    }
    @Test fun successForWrongModelOrBeforeAttemptCannotClearCrashRecord() {
        val prefs = crashedPrefs("a", "b")
        val state = InitCrashState(prefs)
        assertFalse(state.markSucceeded("a"))
        state.authorizeRetry("a"); state.markStarted("a")
        assertFalse(state.markSucceeded("b"))
        assertTrue(state.isBlocked("a")); assertTrue(state.isBlocked("b"))
        assertEquals("a", prefs.getString("init_in_progress_model_id", null))
        state.markFailed("b")
        assertEquals("a", prefs.getString("init_in_progress_model_id", null))
    }
    @Test fun ordinaryInitializationDoesNotCreateAnUnsafeDeviceException() {
        val prefs = FeatureTestPreferences()
        val state = InitCrashState(prefs)
        state.markStarted("m"); assertFalse(state.markSucceeded("m"))
        assertFalse(state.hasSuccessfulRetry("m")); assertFalse(state.isBlocked("m"))
    }
    @Test fun legacySanitizedCrashIdsCanBeRetriedButNewIdsStayDistinct() {
        val prefs = crashedPrefs("custom_model")
        val state = InitCrashState(prefs)
        assertTrue(state.isBlocked("custom/model"))
        state.authorizeRetry("custom/model"); state.markStarted("custom/model"); state.markSucceeded("custom/model")
        assertFalse(state.isBlocked("custom/model")); assertTrue(state.hasSuccessfulRetry("custom/model"))
        state.markStarted("new/id")
        val restarted = InitCrashState(prefs); restarted.recoverCrash()
        assertTrue(restarted.isBlocked("new/id")); assertFalse(restarted.isBlocked("new_id"))
    }
    @Test fun failedTrackingWritePreventsNativeAttempt() {
        val base = crashedPrefs("m")
        val broken = object : SharedPreferences by base {
            override fun edit(): SharedPreferences.Editor {
                val editor = base.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun commit() = false
                }
            }
        }
        val state = InitCrashState(broken)
        state.authorizeRetry("m")
        denied { state.markStarted("m") }
        assertTrue(state.isBlocked("m")); assertFalse(state.hasSuccessfulRetry("m"))
    }
    @Test fun productionFacadeUsesPersistentRecoveryState() {
        val prefs = crashedPrefs("facade-model")
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }
        assertTrue(InitCrashGuard.isModelBlocked(context, "facade-model"))
        InitCrashGuard.authorizeRetry(context, "facade-model")
        InitCrashGuard.markInitStarted(context, "facade-model")
        assertFalse(InitCrashGuard.isRetryAuthorized(context, "facade-model"))
        InitCrashGuard.markInitSucceeded(context, "facade-model")
        assertFalse(InitCrashGuard.isModelBlocked(context, "facade-model"))
        assertTrue(InitCrashGuard.hasSuccessfulRetry(context, "facade-model"))
    }
    @Test fun crashRecordsWhetherTheAttemptUsedTheGpu() {
        val prefs = FeatureTestPreferences()
        InitCrashState(prefs).markStarted("gpu-model", InitCrashGuard.BACKEND_GPU)
        val afterGpuCrash = InitCrashState(prefs)
        assertEquals("gpu-model", afterGpuCrash.recoverCrash())
        assertTrue(afterGpuCrash.blockedCrashMayHaveUsedGpu("gpu-model"))

        afterGpuCrash.markStarted("cpu-model", InitCrashGuard.BACKEND_CPU)
        val afterCpuCrash = InitCrashState(prefs)
        assertEquals("cpu-model", afterCpuCrash.recoverCrash())
        assertTrue(afterCpuCrash.isBlocked("cpu-model"))
        assertFalse(afterCpuCrash.blockedCrashMayHaveUsedGpu("cpu-model"))
        assertTrue(afterCpuCrash.blockedCrashMayHaveUsedGpu("gpu-model"))
        assertFalse("An unblocked model has no crash to attribute", afterCpuCrash.blockedCrashMayHaveUsedGpu("other"))
        assertFalse(prefs.contains("init_in_progress_backend"))
    }
    @Test fun legacyCrashMarkersWithoutBackendStayConservativelyGpu() {
        val legacyBlock = InitCrashState(crashedPrefs("old"))
        assertTrue(legacyBlock.blockedCrashMayHaveUsedGpu("old"))
        val prefs = FeatureTestPreferences(mapOf("init_in_progress_model_id" to "legacy-marker"))
        val restarted = InitCrashState(prefs)
        assertEquals("legacy-marker", restarted.recoverCrash())
        assertTrue(restarted.blockedCrashMayHaveUsedGpu("legacy-marker"))
        restarted.markStarted("unknown-backend")
        val afterUnknown = InitCrashState(prefs)
        assertEquals("unknown-backend", afterUnknown.recoverCrash())
        assertTrue(afterUnknown.blockedCrashMayHaveUsedGpu("unknown-backend"))
    }
    @Test fun successfulRetryClearsTheRecordedCrashBackend() {
        val prefs = FeatureTestPreferences()
        InitCrashState(prefs).markStarted("m", InitCrashGuard.BACKEND_CPU)
        val state = InitCrashState(prefs)
        state.recoverCrash()
        state.authorizeRetry("m"); state.markStarted("m", InitCrashGuard.BACKEND_CPU); state.markSucceeded("m")
        assertFalse(state.blockedCrashMayHaveUsedGpu("m"))
        // A later GPU crash of the same id is attributed to the GPU again.
        state.markStarted("m", InitCrashGuard.BACKEND_GPU)
        val restarted = InitCrashState(prefs)
        restarted.recoverCrash()
        assertTrue(restarted.blockedCrashMayHaveUsedGpu("m"))
    }
}
