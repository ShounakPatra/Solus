package com.shounak.localmeshai.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.math.sqrt

data class DecodedAudio(
    val wavBytes: ByteArray,
    val cacheUri: Uri,
    val cacheFile: File,
    val fileName: String,
    val durationMs: Long,
    val label: String
)

object AudioUtils {
    private const val TAG = "AudioUtils"
    const val TARGET_SAMPLE_RATE = 16_000
    const val TARGET_CHANNELS = 1 // Mono
    const val BITS_PER_SAMPLE = 16

    internal fun validateImportedAudioDuration(durationMs: Long, limitMs: Long) {
        require(durationMs in 1..limitMs) { "This audio exceeds the ${limitMs / 1000}-second limit or has an invalid duration. Trim it before importing; it has not been attached or truncated." }
    }

    suspend fun decodeAudioToPcm16Wav(
        context: Context,
        uri: Uri,
        maxDurationMs: Long = 30_000L,
        rejectLongInput: Boolean = false
    ): DecodedAudio = withContext(Dispatchers.IO) {
        if (rejectLongInput) {
            val metadata = android.media.MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, uri)
                val duration = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: error("Could not determine audio duration. Choose a clip of 30 seconds or less.")
                validateImportedAudioDuration(duration, maxDurationMs)
            } finally { metadata.release() }
        }
        val fileName = getDisplayName(context, uri).ifBlank { "audio_attachment.wav" }
        val rawPcm = decodeToRawPcm(context, uri, maxDurationMs)
        val wavBytes = createWavFromPcm16(rawPcm.pcmBytes, TARGET_SAMPLE_RATE, TARGET_CHANNELS)

        val cacheDir = File(context.cacheDir, "multimodal_inputs").apply { mkdirs() }
        pruneInferenceCache(cacheDir)
        val safeName = fileName.substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24).ifBlank { "audio" }
        val targetFile = File(cacheDir, "audio-$safeName-${System.currentTimeMillis()}.wav")
        FileOutputStream(targetFile).use { it.write(wavBytes) }

        val durationSec = (rawPcm.durationMs / 1000.0).roundToLong().coerceAtLeast(1)
        val label = "$fileName (${durationSec}s)"
        DecodedAudio(
            wavBytes = wavBytes,
            cacheUri = targetFile.toUri(),
            cacheFile = targetFile,
            fileName = fileName,
            durationMs = rawPcm.durationMs,
            label = label
        )
    }

    fun pcm16BytesToFloatPcm(pcmBytes: ByteArray): FloatArray {
        val sampleCount = pcmBytes.size / 2
        val floats = FloatArray(sampleCount)
        var byteIdx = 0
        for (i in 0 until sampleCount) {
            val low = pcmBytes[byteIdx].toInt() and 0xFF
            val high = pcmBytes[byteIdx + 1].toInt()
            val sample = (high shl 8) or low
            floats[i] = (sample / 32768.0f).coerceIn(-1.0f, 1.0f)
            byteIdx += 2
        }
        return floats
    }

    fun parseWavOrPcmToFloatPcm(
        audioBytes: ByteArray,
        targetSampleRate: Int = TARGET_SAMPLE_RATE
    ): FloatArray {
        if (audioBytes.isEmpty()) return FloatArray(0)

        var srcSampleRate = TARGET_SAMPLE_RATE
        var srcChannels = 1
        var bitsPerSample = 16
        var pcmOffset = 0
        var pcmLength = audioBytes.size
        var isFloatPcm = false

        // Check for RIFF WAVE header
        if (audioBytes.size >= 12 &&
            audioBytes[0] == 'R'.code.toByte() && audioBytes[1] == 'I'.code.toByte() &&
            audioBytes[2] == 'F'.code.toByte() && audioBytes[3] == 'F'.code.toByte() &&
            audioBytes[8] == 'W'.code.toByte() && audioBytes[9] == 'A'.code.toByte() &&
            audioBytes[10] == 'V'.code.toByte() && audioBytes[11] == 'E'.code.toByte()
        ) {
            var offset = 12
            var foundData = false
            var formatCode = 1
            while (offset + 8 <= audioBytes.size) {
                val chunkId = String(audioBytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(audioBytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                offset += 8

                if (chunkId == "fmt " && offset + 16 <= audioBytes.size) {
                    val bb = ByteBuffer.wrap(audioBytes, offset, 16).order(ByteOrder.LITTLE_ENDIAN)
                    formatCode = bb.short.toInt() and 0xFFFF
                    srcChannels = bb.short.toInt() and 0xFFFF
                    srcSampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                    isFloatPcm = (formatCode == 3)
                    if (formatCode != 1 && formatCode != 3) {
                        Log.e(TAG, "Unsupported WAV audio format code $formatCode: only PCM (1) and IEEE float (3) are supported.")
                        return FloatArray(0)
                    }
                } else if (chunkId == "data") {
                    pcmOffset = offset
                    pcmLength = minOf(chunkSize.toInt(), audioBytes.size - offset)
                    foundData = true
                    break
                }
                val paddedSize = if ((chunkSize and 1L) != 0L) chunkSize + 1L else chunkSize
                if (paddedSize <= 0 || offset + paddedSize > audioBytes.size) {
                    break
                }
                offset += paddedSize.toInt()
            }
            if (!foundData) {
                Log.e(TAG, "Invalid WAV audio: missing required 'data' chunk.")
                return FloatArray(0)
            }
        }

        if (pcmLength <= 0) return FloatArray(0)
        if (srcChannels <= 0) srcChannels = 1
        if (srcSampleRate <= 0) srcSampleRate = TARGET_SAMPLE_RATE

        val effectiveTargetRate = if (targetSampleRate > 0) targetSampleRate else TARGET_SAMPLE_RATE

        // Decode to mono floats at srcSampleRate
        val monoFloats: FloatArray
        if (isFloatPcm && bitsPerSample == 32) {
            val bytesPerFrame = srcChannels * 4
            val frames = pcmLength / bytesPerFrame
            monoFloats = FloatArray(frames)
            val bb = ByteBuffer.wrap(audioBytes, pcmOffset, pcmLength).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                var sum = 0.0f
                for (ch in 0 until srcChannels) {
                    sum += bb.float
                }
                monoFloats[i] = (sum / srcChannels).coerceIn(-1.0f, 1.0f)
            }
        } else if (bitsPerSample == 32) {
            val bytesPerFrame = srcChannels * 4
            val frames = pcmLength / bytesPerFrame
            monoFloats = FloatArray(frames)
            val bb = ByteBuffer.wrap(audioBytes, pcmOffset, pcmLength).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                var sum = 0.0
                for (ch in 0 until srcChannels) {
                    sum += bb.int.toDouble()
                }
                monoFloats[i] = ((sum / srcChannels) / 2147483648.0).toFloat().coerceIn(-1.0f, 1.0f)
            }
        } else if (bitsPerSample == 24) {
            val bytesPerFrame = srcChannels * 3
            val frames = pcmLength / bytesPerFrame
            monoFloats = FloatArray(frames)
            var idx = pcmOffset
            for (i in 0 until frames) {
                var sum = 0.0
                for (ch in 0 until srcChannels) {
                    val b0 = audioBytes[idx].toInt() and 0xFF
                    val b1 = audioBytes[idx + 1].toInt() and 0xFF
                    val b2 = audioBytes[idx + 2].toInt()
                    val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                    sum += sample24.toDouble()
                    idx += 3
                }
                monoFloats[i] = ((sum / srcChannels) / 8388608.0).toFloat().coerceIn(-1.0f, 1.0f)
            }
        } else if (bitsPerSample == 8) {
            val bytesPerFrame = srcChannels
            val frames = pcmLength / bytesPerFrame
            monoFloats = FloatArray(frames)
            var idx = pcmOffset
            for (i in 0 until frames) {
                var sum = 0.0
                for (ch in 0 until srcChannels) {
                    val u8 = audioBytes[idx].toInt() and 0xFF
                    sum += (u8 - 128).toDouble()
                    idx++
                }
                monoFloats[i] = ((sum / srcChannels) / 128.0).toFloat().coerceIn(-1.0f, 1.0f)
            }
        } else {
            // Default: 16-bit signed PCM
            val bytesPerFrame = srcChannels * 2
            val frames = pcmLength / bytesPerFrame
            monoFloats = FloatArray(frames)
            val bb = ByteBuffer.wrap(audioBytes, pcmOffset, pcmLength).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                var sum = 0
                for (ch in 0 until srcChannels) {
                    sum += bb.short.toInt()
                }
                monoFloats[i] = ((sum / srcChannels) / 32768.0f).coerceIn(-1.0f, 1.0f)
            }
        }

        if (srcSampleRate == effectiveTargetRate || monoFloats.isEmpty()) {
            return monoFloats
        }

        val ratio = effectiveTargetRate.toDouble() / srcSampleRate.toDouble()
        val targetFrames = (monoFloats.size * ratio).toInt()
        if (targetFrames <= 0) return FloatArray(0)
        val resampled = FloatArray(targetFrames)
        for (i in 0 until targetFrames) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()
            val s0 = monoFloats.getOrElse(srcIdx) { 0f }
            val s1 = monoFloats.getOrElse(srcIdx + 1) { s0 }
            resampled[i] = (s0 + frac * (s1 - s0)).coerceIn(-1.0f, 1.0f)
        }
        return resampled
    }

    suspend fun decodeAudioToFloatPcm(
        context: Context,
        uri: Uri,
        maxDurationMs: Long = 30_000L,
        targetSampleRate: Int = TARGET_SAMPLE_RATE
    ): FloatArray = withContext(Dispatchers.IO) {
        val rawPcm = decodeToRawPcm(context, uri, maxDurationMs, targetSampleRate)
        pcm16BytesToFloatPcm(rawPcm.pcmBytes)
    }

    private class PcmResult(val pcmBytes: ByteArray, val durationMs: Long)

    private fun decodeToRawPcm(
        context: Context,
        uri: Uri,
        maxDurationMs: Long,
        targetSampleRate: Int = TARGET_SAMPLE_RATE
    ): PcmResult {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                throw IllegalArgumentException("No audio track found in file.")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Missing audio MIME type.")
            val inputSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val inputChannelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            val codec = MediaCodec.createDecoderByType(mime)
            val pcmOutStream = ByteArrayOutputStream()

            var decodedSampleRate = inputSampleRate
            var decodedChannels = inputChannelCount

            try {
                codec.configure(audioFormat, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var isEos = false
                var totalPcmBytes = 0

                val timeoutUs = 10_000L
                while (!isEos) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    var outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    while (outIndex >= 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuffer.get(chunk)
                            pcmOutStream.write(chunk)
                            totalPcmBytes += chunk.size
                        }
                        codec.releaseOutputBuffer(outIndex, false)

                        // Check if we already exceeded duration limit plus a small safety margin
                        val approxSeconds = totalPcmBytes / (decodedSampleRate * decodedChannels * 2.0)
                        if (approxSeconds * 1000 >= maxDurationMs + 2000) {
                            isEos = true
                            break
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEos = true
                            break
                        }
                        outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }

                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            decodedSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            decodedChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }

            val rawDecodedBytes = pcmOutStream.toByteArray()
            if (rawDecodedBytes.isEmpty()) {
                throw IllegalStateException("Decoded 0 audio bytes from source file.")
            }

            val effectiveRate = if (targetSampleRate > 0) targetSampleRate else TARGET_SAMPLE_RATE
            val monoPcm = convertToMonoPcm(
                inputPcm = rawDecodedBytes,
                srcSampleRate = decodedSampleRate,
                srcChannels = decodedChannels,
                maxDurationMs = maxDurationMs,
                targetSampleRate = effectiveRate
            )
            val durationMs = (monoPcm.size / (effectiveRate * 2.0) * 1000.0).roundToLong()
            return PcmResult(monoPcm, durationMs)
        } finally {
            extractor.release()
        }
    }

    private fun convertToMonoPcm(
        inputPcm: ByteArray,
        srcSampleRate: Int,
        srcChannels: Int,
        maxDurationMs: Long,
        targetSampleRate: Int = TARGET_SAMPLE_RATE
    ): ByteArray {
        val bytesPerSample = 2
        val srcFrameSize = srcChannels * bytesPerSample
        val totalSrcFrames = inputPcm.size / srcFrameSize
        if (totalSrcFrames == 0) return ByteArray(0)

        // Step 1: Downmix to 16-bit Mono ShortArray
        val monoShorts = ShortArray(totalSrcFrames)
        val byteBuffer = ByteBuffer.wrap(inputPcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSrcFrames) {
            var sum = 0
            for (ch in 0 until srcChannels) {
                sum += byteBuffer.short.toInt()
            }
            monoShorts[i] = (sum / srcChannels).coerceIn(-32768, 32767).toShort()
        }

        // Step 2: Resample to targetSampleRate if needed
        val effectiveTargetRate = if (targetSampleRate > 0) targetSampleRate else TARGET_SAMPLE_RATE
        val resampledShorts: ShortArray = if (srcSampleRate == effectiveTargetRate) {
            monoShorts
        } else {
            val ratio = effectiveTargetRate.toDouble() / srcSampleRate.toDouble()
            val targetFrames = (totalSrcFrames * ratio).toInt()
            val out = ShortArray(targetFrames)
            for (i in 0 until targetFrames) {
                val srcPos = i / ratio
                val srcIdx = srcPos.toInt()
                val frac = srcPos - srcIdx
                val s0 = monoShorts.getOrElse(srcIdx) { 0 }.toInt()
                val s1 = monoShorts.getOrElse(srcIdx + 1) { s0.toShort() }.toInt()
                val interpolated = (s0 + frac * (s1 - s0)).toInt()
                out[i] = interpolated.coerceIn(-32768, 32767).toShort()
            }
            out
        }

        // Step 3: Cap at maxDurationMs
        val maxFrames = (effectiveTargetRate * (maxDurationMs / 1000.0)).toInt()
        val finalFrames = if (resampledShorts.size > maxFrames) maxFrames else resampledShorts.size

        // Step 4: Convert back to little-endian ByteArray
        val output = ByteArray(finalFrames * 2)
        val outBuffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until finalFrames) {
            outBuffer.putShort(resampledShorts[i])
        }
        return output
    }

    fun createWavFromPcm16(
        pcmData: ByteArray,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        channels: Int = TARGET_CHANNELS
    ): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * BITS_PER_SAMPLE / 8).toLong()
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * BITS_PER_SAMPLE / 8).toByte() // block align
        header[33] = 0
        header[34] = BITS_PER_SAMPLE.toByte() // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val wavBytes = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wavBytes, 0, 44)
        System.arraycopy(pcmData, 0, wavBytes, 44, pcmData.size)
        return wavBytes
    }

    fun pcm16RmsLevel(buffer: ByteArray, read: Int): Float {
        if (read <= 1) return 0f
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < read) {
            val low = buffer[index].toInt() and 0xFF
            val high = buffer[index + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample / 32768.0
            sum += normalized * normalized
            count++
            index += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) * 3.4).toFloat().coerceIn(0f, 1f)
    }

    fun getDisplayName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = runCatching {
                context.contentResolver.query(uri, null, null, null, null)
            }.getOrNull()
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "audio"
    }

    fun pruneInferenceCache(dir: File, now: Long = System.currentTimeMillis()) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val expiry = 24L * 60L * 60L * 1000L
        files
            .filter { now - it.lastModified() > expiry }
            .forEach { runCatching { ActiveInputFiles.deleteIfUnreferenced(it) } }
    }

    fun getRecorderErrorMessage(errorType: PcmRecorderErrorType): String = when (errorType) {
        PcmRecorderErrorType.PERMISSION_DENIED -> "Microphone permission is required to record audio."
        PcmRecorderErrorType.HARDWARE_UNAVAILABLE -> "Microphone hardware is busy or unavailable."
        PcmRecorderErrorType.FORMAT_UNSUPPORTED -> "Audio recording format is not supported on this device."
        PcmRecorderErrorType.INITIALIZATION_FAILED -> "Failed to initialize microphone."
        PcmRecorderErrorType.RECORDING_TOO_SHORT -> "Recording was too short. Please speak longer."
        PcmRecorderErrorType.SILENCE_OR_ZERO_ONLY -> "No voice detected (recording was silent)."
        PcmRecorderErrorType.RECORDING_FAILED -> "Audio recording failed."
    }
}

object ImageValidationUtils {
    const val MAX_IMAGE_DIMENSION = 2048
    const val MAX_IMAGE_FILE_BYTES = 25L * 1024L * 1024L

    fun calculateDownscaledDimensions(
        width: Int,
        height: Int,
        maxDimension: Int = MAX_IMAGE_DIMENSION
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid image dimensions: ${width}x${height}" }
        val maxEdge = maxOf(width, height)
        if (maxEdge <= maxDimension) {
            return Pair(width, height)
        }
        val scale = maxDimension.toFloat() / maxEdge
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        return Pair(targetW, targetH)
    }

    fun isSupportedImageExtension(extension: String): Boolean {
        val ext = extension.lowercase(Locale.US).trimStart('.')
        return ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
    }
}
