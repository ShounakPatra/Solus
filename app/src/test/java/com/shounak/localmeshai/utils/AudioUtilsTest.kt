package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioUtilsTest {

    @Test
    fun createWavFromPcm16_hasValidHeaderAndSize() {
        val sampleRate = 16_000
        val channels = 1
        val numSamples = 1600 // 100ms
        val pcm = ByteArray(numSamples * 2) { 0 }

        val wav = AudioUtils.createWavFromPcm16(pcm, sampleRate, channels)

        assertEquals(44 + pcm.size, wav.size)

        // RIFF header
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])

        // WAVE header
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])

        // fmt chunk
        assertEquals('f'.code.toByte(), wav[12])
        assertEquals('m'.code.toByte(), wav[13])
        assertEquals('t'.code.toByte(), wav[14])
        assertEquals(' '.code.toByte(), wav[15])

        // Sample rate (offset 24, 4 bytes little endian)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(sampleRate, buffer.getInt(24))

        // Channels (offset 22, 2 bytes)
        assertEquals(channels.toShort(), buffer.getShort(22))

        // Bits per sample (offset 34, 2 bytes)
        assertEquals(16.toShort(), buffer.getShort(34))

        // data chunk
        assertEquals('d'.code.toByte(), wav[36])
        assertEquals('a'.code.toByte(), wav[37])
        assertEquals('t'.code.toByte(), wav[38])
        assertEquals('a'.code.toByte(), wav[39])
        assertEquals(pcm.size, buffer.getInt(40))
    }

    @Test
    fun pcm16RmsLevel_computesCorrectLevels() {
        // Silence
        val silence = ByteArray(1600) { 0 }
        val silenceLevel = AudioUtils.pcm16RmsLevel(silence, silence.size)
        assertEquals(0f, silenceLevel, 0.001f)

        // Half volume sine/constant wave
        val midBuffer = ByteArray(1600)
        val midBb = ByteBuffer.wrap(midBuffer).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 800) {
            midBb.putShort(16384.toShort())
        }
        val midLevel = AudioUtils.pcm16RmsLevel(midBuffer, midBuffer.size)
        assertTrue("Mid level should be between 0.3 and 1.0, got $midLevel", midLevel in 0.3f..1.0f)

        // Full scale wave
        val maxBuffer = ByteArray(1600)
        val maxBb = ByteBuffer.wrap(maxBuffer).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 800) {
            maxBb.putShort(32767.toShort())
        }
        val maxLevel = AudioUtils.pcm16RmsLevel(maxBuffer, maxBuffer.size)
        assertEquals(1.0f, maxLevel, 0.001f)
    }

    @Test
    fun gemmaModels_supportAudioInCatalog() {
        val gemma4E2B = ModelCatalog.defaultModels.firstOrNull { it.id == "gemma4_e2b_litertlm" }
        val gemma4E4B = ModelCatalog.defaultModels.firstOrNull { it.id == "gemma4_e4b_litertlm" }
        val gemma3nE2B = ModelCatalog.defaultModels.firstOrNull { it.id == "gemma3n_e2b_vision" }
        val gemma3nE4B = ModelCatalog.defaultModels.firstOrNull { it.id == "gemma3n_e4b_vision" }

        assertTrue("Gemma 4 E2B must support audio input", gemma4E2B?.supportsAudioInput == true)
        assertTrue("Gemma 4 E4B must support audio input", gemma4E4B?.supportsAudioInput == true)
        assertTrue("Gemma 3n E2B must support audio input", gemma3nE2B?.supportsAudioInput == true)
        assertTrue("Gemma 3n E4B must support audio input", gemma3nE4B?.supportsAudioInput == true)
    }
}
