package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreviewDecoderTest {
    @Test fun previewsBoundLargeAndPanoramicImagesWithoutUpscalingSmallImages() {
        assertEquals(1, ImagePreviewDecoder.sampleSize(640, 480))
        assertEquals(4, ImagePreviewDecoder.sampleSize(4032, 3024))
        assertEquals(16, ImagePreviewDecoder.sampleSize(12000, 500))
        assertEquals(4, ImagePreviewDecoder.sampleSize(3024, 4032))
    }
}
