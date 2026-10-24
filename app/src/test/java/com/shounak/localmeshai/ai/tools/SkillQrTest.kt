package com.shounak.localmeshai.ai.tools

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test

class SkillQrTest {
    @Test fun generatedQrRoundTripsUnicodeTextThroughARealDecoder() {
        val content = "https://example.com/你好?x=🌍"
        val matrix = SkillArtwork.qr(content)
        val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) -16777216 else -1 }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        assertEquals(content, MultiFormatReader().decode(bitmap).text)
    }
    @Test fun qrLimitsPreventOversizedBitmapAndPayloadAllocations() {
        assertThrows(IllegalArgumentException::class.java) { SkillArtwork.qr("x", 100000) }
        assertThrows(IllegalArgumentException::class.java) { SkillArtwork.qr("x".repeat(1501)) }
    }
}
