package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorClassifierTest {

    @Test
    fun nullOrBlankReturnsNull() {
        assertNull(ErrorClassifier.classify(null))
        assertNull(ErrorClassifier.classify(""))
        assertNull(ErrorClassifier.classify("   "))
    }

    @Test
    fun classifiesOfflineErrors() {
        val result = ErrorClassifier.classify("No internet connection. Please connect to Wi-Fi or mobile data.")
        assertNotNull(result)
        assertEquals(ErrorCategory.NoInternet, result?.category)
        assertTrue(result?.isRetryAction == true)

        val dnsResult = ErrorClassifier.classify("java.net.UnknownHostException: Unable to resolve host \"huggingface.co\"")
        assertNotNull(dnsResult)
        assertEquals(ErrorCategory.NoInternet, dnsResult?.category)
    }

    @Test
    fun classifiesTimeoutErrors() {
        val result = ErrorClassifier.classify("Connection timed out while downloading. Tap Resume to continue.")
        assertNotNull(result)
        assertEquals(ErrorCategory.ConnectionTimeout, result?.category)
        assertTrue(result?.isRetryAction == true)
    }

    @Test
    fun classifiesAuthAndTokenErrors() {
        val result = ErrorClassifier.classify("Hugging Face denied access. Open Settings (⚙️) → Hugging Face Access Token to enter your read token.")
        assertNotNull(result)
        assertEquals(ErrorCategory.AuthRequired, result?.category)
        assertTrue(result?.isOpenSettingsAction == true)

        val forbiddenResult = ErrorClassifier.classify("Server denied the model download (HTTP 403).")
        assertNotNull(forbiddenResult)
        assertEquals(ErrorCategory.AuthRequired, forbiddenResult?.category)
    }

    @Test
    fun classifiesNotFoundErrors() {
        val result = ErrorClassifier.classify("Model file was not found at the configured URL (HTTP 404).")
        assertNotNull(result)
        assertEquals(ErrorCategory.NotFound, result?.category)
    }

    @Test
    fun classifiesRateLimitErrors() {
        val result = ErrorClassifier.classify("Download rate limit exceeded (HTTP 429). Please wait before trying again.")
        assertNotNull(result)
        assertEquals(ErrorCategory.RateLimited, result?.category)
    }

    @Test
    fun classifiesServerErrors() {
        val result = ErrorClassifier.classify("Remote model host server error (HTTP 503). Please try again later.")
        assertNotNull(result)
        assertEquals(ErrorCategory.ServerError, result?.category)
    }

    @Test
    fun classifiesLowStorageErrors() {
        val result = ErrorClassifier.classify("ZIP archive uncompressed size exceeds maximum safety limit (16 GB).")
        assertNotNull(result)
        assertEquals(ErrorCategory.StorageFull, result?.category)

        val diskResult = ErrorClassifier.classify("write failed: ENOSPC (No space left on device)")
        assertNotNull(diskResult)
        assertEquals(ErrorCategory.StorageFull, diskResult?.category)
    }

    @Test
    fun classifiesChecksumFailure() {
        val result = ErrorClassifier.classify("SHA-256 checksum verification failed. Expected: abc, Actual: xyz")
        assertNotNull(result)
        assertEquals(ErrorCategory.ChecksumCorrupted, result?.category)
        assertTrue(result?.isRetryAction == true)
    }

    @Test
    fun classifiesInvalidFormatErrors() {
        val result = ErrorClassifier.classify("Model host returned a web page instead of a model file.")
        assertNotNull(result)
        assertEquals(ErrorCategory.InvalidFormat, result?.category)
    }

    @Test
    fun classifiesNativeCrashErrors() {
        val result = ErrorClassifier.classify("The model \"qwen25\" crashed during native initialization. It has been blocked on Snapdragon 8 Gen 3.")
        assertNotNull(result)
        assertEquals(ErrorCategory.NativeCrash, result?.category)
        assertTrue(result?.isSendCrashReportAction == true)
    }

    @Test
    fun classifiesHardwareIncompatibleErrors() {
        val result = ErrorClassifier.classify("Blocked on MediaTek Dimensity 9300. Use a MediaPipe model instead.")
        assertNotNull(result)
        assertEquals(ErrorCategory.HardwareIncompatible, result?.category)
    }

    @Test
    fun classifiesGeneralFallbackErrors() {
        val result = ErrorClassifier.classify("Unexpected random error occurred in worker")
        assertNotNull(result)
        assertEquals(ErrorCategory.General, result?.category)
    }
}
