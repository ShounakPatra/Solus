package com.shounak.localmeshai.ai

import org.junit.Assert.*
import org.junit.Test

class PortableMediaContractsTest {
    @Test fun quantizationHandlesSignedAndUnsignedZeroPointsAndSaturation() {
        assertEquals(128, PortableTensorMath.quantize(0f,0.1f,128,true))
        assertEquals(255, PortableTensorMath.quantize(100f,0.1f,128,true))
        assertEquals(-128, PortableTensorMath.quantize(-100f,0.1f,0,false))
        assertEquals(1.25f, PortableTensorMath.dequantize(133,0.25f,128),0f)
    }
    @Test fun invalidQuantizationIsRejected() {
        for(scale in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            try { PortableTensorMath.quantize(0f,scale,0,true); fail("Accepted invalid scale") } catch (_:IllegalArgumentException) {}
        }
    }
    @Test fun ctcCollapsesRepeatsButKeepsLettersSeparatedByBlank() {
        val tokens = listOf(1,1,0,1,2,2,0,3,3,0)
        val scores = FloatArray(tokens.size*4) { i -> if(i%4==tokens[i/4]) 10f else -10f }
        val text = PortableTensorMath.ctc(scores,longArrayOf(1,tokens.size.toLong(),4), mapOf(0 to "<pad>",1 to "l",2 to "|",3 to "a"),0,"|")
        assertEquals("ll a",text)
    }
    @Test fun ctcRejectsInvalidShapesAndNonFiniteScores() {
        try { PortableTensorMath.ctc(FloatArray(3),longArrayOf(1,1,4),emptyMap(),0,"|"); fail() } catch (_:IllegalArgumentException) {}
        try { PortableTensorMath.ctc(FloatArray(4) { Float.NaN },longArrayOf(1,1,4),emptyMap(),0,"|"); fail() } catch (_:IllegalStateException) {}
    }
}
