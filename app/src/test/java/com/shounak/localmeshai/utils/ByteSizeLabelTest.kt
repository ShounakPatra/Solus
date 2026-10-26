package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteSizeLabelTest {
    @Test fun downloadSizesUseWholeMebibytes() {
        assertEquals("86 MiB", ByteSizeLabel.wholeMib(90_000_000L))
        assertEquals("1 MiB", ByteSizeLabel.wholeMib(1_048_576L))
        assertEquals("0 MiB", ByteSizeLabel.wholeMib(-5L))
    }
}
