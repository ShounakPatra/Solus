package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelFormat
import org.junit.Assert.*
import org.junit.Test

class ContextAndAudioAdmissionTest {
    @Test fun safetyContextUsesRuntimeLimits() {
        assertEquals(4096, DeviceUtils.effectiveContextTokens(ModelFormat.GGUF, 7.3, 131072))
        assertEquals(1536, DeviceUtils.effectiveContextTokens(ModelFormat.GGUF, 4.0, 131072, modelParamsB=.5f))
        assertEquals(8192, DeviceUtils.effectiveContextTokens(ModelFormat.LiteRtLm, 8.0, 131072))
        assertEquals(512, DeviceUtils.effectiveContextTokens(ModelFormat.GGUF, 8.0, 512))
    }
    @Test fun audioAtLimitAccepted() { AudioUtils.validateImportedAudioDuration(30000,30000) }
    @Test(expected=IllegalArgumentException::class) fun longerAudioMustNotSilentlyTruncate() {
        AudioUtils.validateImportedAudioDuration(60000,30000)
    }
}
