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

    suspend fun decodeAudioToPcm16Wav(
        context: Context,
        uri: Uri,
        maxDurationMs: Long = 30_000L
    ): DecodedAudio = withContext(Dispatchers.IO) {
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

    private class PcmResult(val pcmBytes: ByteArray, val durationMs: Long)

    private fun decodeToRawPcm(context: Context, uri: Uri, maxDurationMs: Long): PcmResult {
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

            val mono16kPcm = convertToMono16kHz(
                inputPcm = rawDecodedBytes,
                srcSampleRate = decodedSampleRate,
                srcChannels = decodedChannels,
                maxDurationMs = maxDurationMs
            )
            val durationMs = (mono16kPcm.size / (TARGET_SAMPLE_RATE * 2.0) * 1000.0).roundToLong()
            return PcmResult(mono16kPcm, durationMs)
        } finally {
            extractor.release()
        }
    }

    private fun convertToMono16kHz(
        inputPcm: ByteArray,
        srcSampleRate: Int,
        srcChannels: Int,
        maxDurationMs: Long
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

        // Step 2: Resample to 16,000 Hz if needed
        val resampledShorts: ShortArray = if (srcSampleRate == TARGET_SAMPLE_RATE) {
            monoShorts
        } else {
            val ratio = TARGET_SAMPLE_RATE.toDouble() / srcSampleRate.toDouble()
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
        val maxFrames = (TARGET_SAMPLE_RATE * (maxDurationMs / 1000.0)).toInt()
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

    fun pruneInferenceCache(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files
            .filter { now - it.lastModified() > 2L * 60L * 60L * 1000L }
            .forEach { runCatching { it.delete() } }
        files
            .sortedByDescending { it.lastModified() }
            .drop(8)
            .forEach { runCatching { it.delete() } }
    }
}
