package com.shounak.localmeshai.utils

import android.media.AudioRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmRecorderTest {

    private class FakeAudioRecordSource(
        private val samplesToProvide: List<ByteArray>,
        override var state: Int = AudioRecord.STATE_INITIALIZED,
        override var recordingState: Int = AudioRecord.RECORDSTATE_STOPPED
    ) : AudioRecordSource {
        private var sampleIndex = 0
        private var chunkOffset = 0
        var releaseCalled = false

        override fun startRecording() {
            recordingState = AudioRecord.RECORDSTATE_RECORDING
        }

        override fun stop() {
            recordingState = AudioRecord.RECORDSTATE_STOPPED
        }

        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            if (recordingState != AudioRecord.RECORDSTATE_RECORDING || sampleIndex >= samplesToProvide.size) {
                return 0
            }
            val chunk = samplesToProvide[sampleIndex]
            val remainingInChunk = chunk.size - chunkOffset
            val bytesToCopy = minOf(remainingInChunk, sizeInBytes)
            System.arraycopy(chunk, chunkOffset, audioData, offsetInBytes, bytesToCopy)
            chunkOffset += bytesToCopy
            if (chunkOffset >= chunk.size) {
                sampleIndex++
                chunkOffset = 0
            }
            return bytesToCopy
        }

        override fun release() {
            releaseCalled = true
        }
    }

    private fun generateNonZeroPcm(bytesCount: Int): ByteArray {
        val pcm = ByteArray(bytesCount)
        for (i in 0 until bytesCount step 2) {
            val sample = (1000 + (i % 2000)).toShort()
            pcm[i] = (sample.toInt() and 0xFF).toByte()
            pcm[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    @Test
    fun testRecorderStateTransitions_IdleToRecordingToStopped() = runBlocking {
        val pcmChunk = generateNonZeroPcm(6400) // 200ms at 16kHz 16-bit mono
        val fakeSource = FakeAudioRecordSource(listOf(pcmChunk, pcmChunk))

        val recorder = PcmRecorder(
            permissionChecker = { true },
            audioRecordFactory = { _, _, _, _, _ -> fakeSource },
            minBufferSizeProvider = { _, _, _ -> 1600 },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        assertEquals(PcmRecorderState.IDLE, recorder.state)

        var observedStateDuringRecord: PcmRecorderState? = null

        val result = recorder.record(
            maxDurationMs = 1000L,
            onLevel = { _, _, _ ->
                observedStateDuringRecord = recorder.state
            }
        )

        assertEquals(PcmRecorderState.STOPPED, recorder.state)
        assertEquals(PcmRecorderState.RECORDING, observedStateDuringRecord)
        assertTrue(result is PcmRecordingResult.Success)
        assertTrue(fakeSource.releaseCalled)
    }

    @Test
    fun testNegativeRead_FailsExplicitly() = runBlocking {
        val fakeSource = object : AudioRecordSource {
            override var state: Int = AudioRecord.STATE_INITIALIZED
            override var recordingState: Int = AudioRecord.RECORDSTATE_RECORDING
            var released = false

            override fun startRecording() {}
            override fun stop() {}
            override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                return AudioRecord.ERROR_INVALID_OPERATION
            }
            override fun release() {
                released = true
            }
        }

        val recorder = PcmRecorder(
            permissionChecker = { true },
            audioRecordFactory = { _, _, _, _, _ -> fakeSource },
            minBufferSizeProvider = { _, _, _ -> 1600 },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val result = recorder.record(maxDurationMs = 1000L)
        assertTrue(result is PcmRecordingResult.Failure)
        val failure = result as PcmRecordingResult.Failure
        assertEquals(PcmRecorderErrorType.RECORDING_FAILED, failure.errorType)
        assertTrue(failure.message.contains("hardware error code"))
        assertEquals(PcmRecorderState.ERROR, recorder.state)
        assertTrue(fakeSource.released)
    }

    @Test
    fun testConsecutiveZeroReads_BoundedAndTerminates() = runBlocking {
        // Source returns 0 indefinitely
        val fakeSource = object : AudioRecordSource {
            override var state: Int = AudioRecord.STATE_INITIALIZED
            override var recordingState: Int = AudioRecord.RECORDSTATE_RECORDING
            var callCount = 0

            override fun startRecording() {}
            override fun stop() {}
            override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                callCount++
                return 0
            }
            override fun release() {}
        }

        val recorder = PcmRecorder(
            permissionChecker = { true },
            audioRecordFactory = { _, _, _, _, _ -> fakeSource },
            minBufferSizeProvider = { _, _, _ -> 1600 },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        // Must terminate within bounded calls, not hang
        val result = recorder.record(maxDurationMs = 5000L)
        assertTrue(result is PcmRecordingResult.Failure)
        assertTrue(fakeSource.callCount in 30..35)
    }

    @Test
    fun testShortRecording_DiscardedWithExplicitError() = runBlocking {
        // Provide only 50ms of audio (1600 bytes at 16kHz 16-bit mono)
        val shortPcm = generateNonZeroPcm(1600)
        val fakeSource = FakeAudioRecordSource(listOf(shortPcm))

        var readCount = 0
        val recorder = PcmRecorder(
            minDurationMs = 200L,
            permissionChecker = { true },
            audioRecordFactory = { _, _, _, _, _ -> fakeSource },
            minBufferSizeProvider = { _, _, _ -> 1600 },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val result = recorder.record(
            maxDurationMs = 1000L,
            isStopRequested = {
                readCount++
                readCount > 1
            }
        )

        assertTrue(result is PcmRecordingResult.Failure)
        val failure = result as PcmRecordingResult.Failure
        assertEquals(PcmRecorderErrorType.RECORDING_TOO_SHORT, failure.errorType)
        assertTrue(failure.message.contains("too short"))
    }

    @Test
    fun testAllZeroAudio_DetectedAndRejected() = runBlocking {
        // 500ms of all-zero audio (16000 bytes)
        val zeroPcm = ByteArray(16000) { 0 }
        val fakeSource = FakeAudioRecordSource(listOf(zeroPcm))

        val recorder = PcmRecorder(
            permissionChecker = { true },
            audioRecordFactory = { _, _, _, _, _ -> fakeSource },
            minBufferSizeProvider = { _, _, _ -> 1600 },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val result = recorder.record(maxDurationMs = 1000L)

        assertTrue(result is PcmRecordingResult.Failure)
        val failure = result as PcmRecordingResult.Failure
        assertEquals(PcmRecorderErrorType.SILENCE_OR_ZERO_ONLY, failure.errorType)
        assertTrue(failure.message.contains("silence or zeros"))
    }

    @Test
    fun testPermissionDenied_FailsImmediately() = runBlocking {
        val recorder = PcmRecorder(
            permissionChecker = { false },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val result = recorder.record()
        assertTrue(result is PcmRecordingResult.Failure)
        val failure = result as PcmRecordingResult.Failure
        assertEquals(PcmRecorderErrorType.PERMISSION_DENIED, failure.errorType)
        assertEquals(PcmRecorderState.ERROR, recorder.state)
    }

    @Test
    fun testFormatUnsupported_FailsImmediately() = runBlocking {
        val recorder = PcmRecorder(
            permissionChecker = { true },
            minBufferSizeProvider = { _, _, _ -> AudioRecord.ERROR_BAD_VALUE },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val result = recorder.record()
        assertTrue(result is PcmRecordingResult.Failure)
        val failure = result as PcmRecordingResult.Failure
        assertEquals(PcmRecorderErrorType.FORMAT_UNSUPPORTED, failure.errorType)
        assertEquals(PcmRecorderState.ERROR, recorder.state)
    }

    @Test
    fun testGuaranteedCleanupOnStartRecordingHardwareException() = runBlocking {
        var released = false
        val failingSource = object : AudioRecordSource {
            override var state: Int = AudioRecord.STATE_INITIALIZED
            override var recordingState: Int = AudioRecord.RECORDSTATE_STOPPED
            override fun startRecording() {
                throw IllegalStateException("AudioRecord hardware unavailable")
            }
            override fun stop() {}
            override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = 0
            override fun release() {
                released = true
            }
        }

        val recorder = PcmRecorder(
            permissionChecker = { true },
            audioRecordFactory = { _, _, _, _, _ -> failingSource },
            minBufferSizeProvider = { _, _, _ -> 1600 },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val result = recorder.record()
        assertTrue(result is PcmRecordingResult.Failure)
        assertTrue("Resource must be released on startRecording failure", released)
        assertEquals(PcmRecorderState.STOPPED, recorder.state)
    }

    @Test
    fun testGuaranteedCleanupOnNotRecordingState() = runBlocking {
        var released = false
        val failingSource = object : AudioRecordSource {
            override var state: Int = AudioRecord.STATE_INITIALIZED
            override var recordingState: Int = AudioRecord.RECORDSTATE_STOPPED // Not RECORDSTATE_RECORDING
            override fun startRecording() {
                // Do not transition to recording
            }
            override fun stop() {}
            override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = 0
            override fun release() {
                released = true
            }
        }

        val recorder = PcmRecorder(
            permissionChecker = { true },
            audioRecordFactory = { _, _, _, _, _ -> failingSource },
            minBufferSizeProvider = { _, _, _ -> 1600 },
            uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val result = recorder.record()
        assertTrue(result is PcmRecordingResult.Failure)
        assertTrue("Resource must be released when hardware is not in recording state", released)
        assertEquals(PcmRecorderState.STOPPED, recorder.state)
    }

    @Test
    fun testWavHeaderByteCountAccuracy() {
        val pcmLength = 16000
        val pcmData = generateNonZeroPcm(pcmLength)
        val sampleRate = 16000
        val channels = 1

        val wavBytes = AudioUtils.createWavFromPcm16(pcmData, sampleRate, channels)

        // Total size must be exactly 44-byte header + PCM payload
        assertEquals(44 + pcmLength, wavBytes.size)

        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        assertEquals('R'.code.toByte(), buffer.get(0))
        assertEquals('I'.code.toByte(), buffer.get(1))
        assertEquals('F'.code.toByte(), buffer.get(2))
        assertEquals('F'.code.toByte(), buffer.get(3))

        // Total data length (file length - 8)
        val totalDataLen = buffer.getInt(4)
        assertEquals(pcmLength + 36, totalDataLen)

        // WAVEfmt
        assertEquals('W'.code.toByte(), buffer.get(8))
        assertEquals('A'.code.toByte(), buffer.get(9))
        assertEquals('V'.code.toByte(), buffer.get(10))
        assertEquals('E'.code.toByte(), buffer.get(11))

        // Sample rate
        val recordedSampleRate = buffer.getInt(24)
        assertEquals(sampleRate, recordedSampleRate)

        // data chunk size
        assertEquals('d'.code.toByte(), buffer.get(36))
        assertEquals('a'.code.toByte(), buffer.get(37))
        assertEquals('t'.code.toByte(), buffer.get(38))
        assertEquals('a'.code.toByte(), buffer.get(39))

        val dataChunkSize = buffer.getInt(40)
        assertEquals(pcmLength, dataChunkSize)
    }

    @Test
    fun testImageDownscaling_PreservesAspectRatio() {
        // Landscape downscale
        val (w1, h1) = ImageValidationUtils.calculateDownscaledDimensions(4000, 2000, 2048)
        assertEquals(2048, w1)
        assertEquals(1024, h1)

        // Portrait downscale
        val (w2, h2) = ImageValidationUtils.calculateDownscaledDimensions(1500, 3000, 2048)
        assertEquals(1024, w2)
        assertEquals(2048, h2)

        // Already within budget
        val (w3, h3) = ImageValidationUtils.calculateDownscaledDimensions(800, 600, 2048)
        assertEquals(800, w3)
        assertEquals(600, h3)

        // Square downscale
        val (w4, h4) = ImageValidationUtils.calculateDownscaledDimensions(3000, 3000, 2048)
        assertEquals(2048, w4)
        assertEquals(2048, h4)
    }

    @Test
    fun testCorruptImageOrInvalidFormat_Rejected() {
        assertFalse(ImageValidationUtils.isSupportedImageExtension("exe"))
        assertFalse(ImageValidationUtils.isSupportedImageExtension("pdf"))
        assertFalse(ImageValidationUtils.isSupportedImageExtension("txt"))
        assertTrue(ImageValidationUtils.isSupportedImageExtension("jpg"))
        assertTrue(ImageValidationUtils.isSupportedImageExtension(".png"))
        assertTrue(ImageValidationUtils.isSupportedImageExtension("webp"))

        assertThrows(IllegalArgumentException::class.java) {
            ImageValidationUtils.calculateDownscaledDimensions(-1, 500)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageValidationUtils.calculateDownscaledDimensions(500, 0)
        }
    }
}
