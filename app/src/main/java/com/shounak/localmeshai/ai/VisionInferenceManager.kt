@file:Suppress("DEPRECATION")
package com.shounak.localmeshai.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.AudioModelOptions
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class VisionLabel(
    val label: String,
    val score: Float
)

class VisionInferenceManager(private val context: Context) {
    private var classifier: Interpreter? = null
    private var mediaPipeInference: LlmInference? = null
    private var liteRtEngine: Engine? = null
    private var liteRtConversation: Conversation? = null
    private var liteRtCacheDir: File? = null
    private var runtime = RuntimeKind.None
    @Volatile private var activeMediaPipeSession: LlmInferenceSession? = null
    @Volatile private var activeMediaPipeFuture: Future<String>? = null
    @Volatile private var activeMediaPipeCallbackDone: CountDownLatch? = null
    @Volatile private var stopRequested = false
    /**
     * Set to true by [close] before it destroys any native resource.
     * [streamMediaPipeVision]'s finally block checks this flag before
     * calling session.close() — if [close] has already claimed and closed
     * the session, the finally block skips it, preventing a double-free
     * native crash (SIGSEGV/SIGABRT) when the user taps Stop then
     * immediately switches tabs or loads a new model.
     */
    @Volatile private var isBeingClosed = false
    @Volatile private var modelSupportsAudioInput = false
    private var inputWidth = 224
    private var inputHeight = 224
    private var outputSize = 1001

    fun initialize(
        modelPath: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        supportsAudioInput: Boolean = false,
        allowUnsafeOverride: Boolean = false
    ) {
        val file = File(modelPath)
        if (!file.exists()) {
            throw IllegalArgumentException("Image model file not found.")
        }
        if (file.extension.equals("task", ignoreCase = true) && !ModelDownloader.isLikelyTaskBundle(file)) {
            throw IllegalArgumentException("Selected .task file does not look like a MediaPipe or LiteRT task bundle. Download a verified Android multimodal .task model from the Models tab.")
        }

        close()
        val audioInputAvailable = supportsAudioInput && (
            file.extension.equals("litertlm", ignoreCase = true) ||
                file.containsAudioModelAssets()
            )
        modelSupportsAudioInput = audioInputAvailable
        isBeingClosed = false   // Reset after close() so initialize() starts clean
        when {
            file.extension.equals("litertlm", ignoreCase = true) -> {
                val effectiveId = modelId.ifBlank { file.nameWithoutExtension }
                val inferenceBackend = if (allowUnsafeOverride) {
                    InferenceBackend.LITERT_GPU
                } else {
                    DeviceUtils.selectBackendForModelFile(context, file.name)
                }
                val canRetryOnCpu = DeviceUtils.canUseGemma4LiteRtCpuFallback(
                    context = context,
                    modelId = effectiveId,
                    modelName = modelName.ifBlank { file.nameWithoutExtension },
                    modelSize = modelSize,
                    fileName = file.name,
                    isMultimodalLiteRt = true
                )
                if (InitCrashGuard.isModelBlocked(context, effectiveId) && canRetryOnCpu) {
                    InitCrashGuard.unblockModel(context, effectiveId)
                }
                if (InitCrashGuard.isModelBlocked(context, effectiveId)) {
                    throw IllegalStateException(InitCrashGuard.blockedModelMessage())
                }
                if (!allowUnsafeOverride) {
                    val (allowed, reason) = DeviceUtils.canInitializeLiteRtLm(
                        context = context,
                        modelId = effectiveId,
                        modelName = modelName.ifBlank { file.nameWithoutExtension },
                        modelSize = modelSize,
                        isVision = true,
                        fileName = file.name,
                        backendLabel = "LiteRT-LM multimodal",
                        supportsAudioInput = audioInputAvailable
                    )
                    if (!allowed) {
                        throw IllegalStateException(reason)
                    }
                }
                initializeLiteRtLm(
                    modelPath = modelPath,
                    modelId = effectiveId,
                    inferenceBackend = inferenceBackend
                )
            }
            file.extension.equals("task", ignoreCase = true) -> initializeMediaPipeVision(modelPath, audioInputAvailable)
            else -> initializeClassifier(file)
        }
    }

    /**
     * Streaming ask. Emits partial text to [onUpdate] as the model produces it.
     * The returned String is the final, accumulated response.
     */
    suspend fun askStreaming(
        bitmap: Bitmap?,
        question: String,
        audioFile: File? = null,
        audioBytes: ByteArray? = null,
        onUpdate: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        stopRequested = false
        val prompt = question.trim().ifBlank { DEFAULT_IMAGE_PROMPT }
        when (runtime) {
            RuntimeKind.LiteRtLm -> streamLiteRtLmVision(bitmap, prompt, audioFile, audioBytes, onUpdate)
            RuntimeKind.MediaPipeVision -> streamMediaPipeVision(bitmap, prompt, audioFile, audioBytes, onUpdate)
            RuntimeKind.Classifier -> {
                val result = bitmap?.let { classify(it).formatForQuestion(prompt) }
                    ?: "Select an image for this classifier model."
                onUpdate(result)
                result
            }
            RuntimeKind.None -> "Choose and initialise a multimodal model first."
        }
    }

    suspend fun classify(bitmap: Bitmap): List<VisionLabel> = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) return@withContext emptyList()
        val model = classifier ?: return@withContext emptyList()
        val input = bitmap.toInputBuffer(inputWidth, inputHeight)
        val output = Array(1) { FloatArray(outputSize) }
        model.run(input, output)
        output.first()
            .mapIndexed { index, score -> VisionLabel("Class $index", score) }
            .sortedByDescending { it.score }
            .take(5)
    }

    fun close() {
        val wasMediaPipeVision = runtime == RuntimeKind.MediaPipeVision
        isBeingClosed = true
        cancelGeneration()
        // Claim the active session atomically before destroying the parent
        // LlmInference object. This prevents streamMediaPipeVision's finally
        // block from calling session.close() AFTER mediaPipeInference.close()
        // destroys the underlying native context — which is the root cause of
        // the SIGSEGV when the user taps Stop.
        val sessionToClose = activeMediaPipeSession
        val callbackDone = activeMediaPipeCallbackDone
        activeMediaPipeSession = null    // null first so finally block skips it
        activeMediaPipeCallbackDone = null
        if (!wasMediaPipeVision && sessionToClose != null && waitForCallbackToReturn(callbackDone)) {
            runCatching { sessionToClose.close() }
        } else if (wasMediaPipeVision && sessionToClose != null) {
            Log.w(TAG, "Skipping MediaPipe vision session.close(); native close is unstable on this device")
        }
        runCatching { classifier?.close() }
        classifier = null
        if (wasMediaPipeVision) {
            Log.w(TAG, "Skipping MediaPipe vision inference.close(); native close is unstable on this device")
        } else {
            runCatching { mediaPipeInference?.close() }
        }
        mediaPipeInference = null
        runCatching { liteRtConversation?.close() }
        liteRtConversation = null
        runCatching { liteRtEngine?.close() }
        liteRtEngine = null
        LiteRtRuntimeCache.clear(context, liteRtCacheDir)
        liteRtCacheDir = null
        runtime = RuntimeKind.None
        isBeingClosed = false
    }

    fun cancelGeneration() {
        stopRequested = true
        runCatching { liteRtConversation?.cancelProcess() }
        val session = activeMediaPipeSession
        if (session != null) {
            // Use MediaPipe's native cancellation path. Cancelling the Future
            // directly makes future.get() return before MediaPipe releases its
            // single inference lock, which caused the next request to fail with
            // "Previous invocation still processing" and could crash on close.
            runCatching { session.cancelGenerateResponseAsync() }
        } else {
            runCatching { activeMediaPipeFuture?.cancel(true) }
            activeMediaPipeFuture = null
        }
        // Do NOT close activeMediaPipeSession here — the streaming method's
        // finally block owns session lifecycle. Closing it from two threads
        // simultaneously causes a native crash.
    }

    fun resetConversation() {
        if (runtime != RuntimeKind.LiteRtLm) return
        val engine = liteRtEngine ?: return
        runCatching { liteRtConversation?.close() }.onFailure { t ->
            Log.w(TAG, "LiteRT-LM vision conversation close during reset failed", t)
        }
        liteRtConversation = engine.createConversation()
        stopRequested = false
    }

    private fun initializeLiteRtLm(
        modelPath: String,
        modelId: String,
        inferenceBackend: InferenceBackend
    ) {
        InitCrashGuard.markInitStarted(context, modelId)
        var engine: Engine? = null
        try {
            val backend = when (inferenceBackend) {
                InferenceBackend.LITERT_GPU -> Backend.GPU()
                InferenceBackend.LITERT_CPU,
                InferenceBackend.MEDIAPIPE -> Backend.CPU()
            }
            val runtimeCacheDir = LiteRtRuntimeCache.prepare(context, modelId, inferenceBackend)
            liteRtCacheDir = runtimeCacheDir
            engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = backend,
                    maxNumTokens = MAX_RESPONSE_TOKENS,
                    cacheDir = runtimeCacheDir.absolutePath
                )
            )
            engine.initialize()
            InitCrashGuard.markInitCompleted(context)
            liteRtEngine = engine
            liteRtConversation = engine.createConversation()
            runtime = RuntimeKind.LiteRtLm
            Log.i(TAG, "LiteRT-LM vision engine ready on $backend: $modelPath")
        } catch (t: Throwable) {
            InitCrashGuard.markInitCompleted(context)
            runCatching { engine?.close() }
            LiteRtRuntimeCache.clear(context, liteRtCacheDir)
            liteRtCacheDir = null
            throw t
        }
    }

    private fun initializeMediaPipeVision(modelPath: String, supportsAudioInput: Boolean) {
        val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_RESPONSE_TOKENS)
            .setMaxTopK(40)
            .setMaxNumImages(1)
            .setPreferredBackend(LlmInference.Backend.CPU)
        if (supportsAudioInput) {
            optionsBuilder.setAudioModelOptions(
                AudioModelOptions.builder()
                    .setMaxAudioSequenceLength(300)
                    .build()
            )
        }
        val options = optionsBuilder.build()
        mediaPipeInference = LlmInference.createFromOptions(context, options)
        runtime = RuntimeKind.MediaPipeVision
    }

    private fun initializeClassifier(file: File) {
        val options = Interpreter.Options().setNumThreads(4)
        classifier = Interpreter(file, options)
        classifier?.let { model ->
            val inputShape = model.getInputTensor(0).shape()
            if (inputShape.size >= 4) {
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            }
            val outputShape = model.getOutputTensor(0).shape()
            outputSize = outputShape.lastOrNull() ?: outputSize
        }
        runtime = RuntimeKind.Classifier
    }

    private suspend fun streamLiteRtLmVision(
        bitmap: Bitmap?,
        prompt: String,
        audioFile: File?,
        audioBytes: ByteArray?,
        onUpdate: (String) -> Unit
    ): String {
        if (bitmap?.isRecycled == true) return "Error: Image was recycled before analysis."
        val hasAudioBytes = audioBytes?.isNotEmpty() == true
        val hasAudioInput = audioFile != null || hasAudioBytes
        if (hasAudioInput && !modelSupportsAudioInput) return "This model does not support audio input."
        val conversation = liteRtConversation ?: return "Image model is not initialised."
        val imageFile = bitmap?.writeToCacheFile()
        val response = StringBuilder()
        try {
            val audioContent: Content? = when {
                hasAudioBytes -> Content.AudioBytes(audioBytes!!)
                audioFile != null -> Content.AudioFile(audioFile.absolutePath)
                else -> null
            }
            val imageContent: Content? = imageFile?.let { Content.ImageFile(it.absolutePath) }
            val textContent = Content.Text(prompt)
            val message = when {
                imageContent != null && audioContent != null -> Message.of(imageContent, audioContent, textContent)
                imageContent != null -> Message.of(imageContent, textContent)
                audioContent != null -> Message.of(audioContent, textContent)
                else -> Message.of(textContent)
            }
            conversation.sendMessageAsync(
                message
            ).collect { message ->
                if (stopRequested) {
                    // Throwing here breaks out of the Flow collector cleanly.
                    throw kotlinx.coroutines.CancellationException("Stop requested")
                }
                val chunk = message.toString()
                // Duplicate-partial handling: MediaPipe / LiteRT-LM sometimes
                // delivers a chunk that already contains the previous accumulation
                // as a prefix. Detect that and replace instead of append.
                val current = response.toString()
                if (current.isNotEmpty() && chunk.startsWith(current)) {
                    response.clear()
                    response.append(chunk)
                } else {
                    response.append(chunk)
                }
                runCatching { onUpdate(ModelOutputSanitizer.clean(response.toString())) }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected when the user stops generation — coroutine was cancelled
            // or stopRequested flag was set.
            Log.i(TAG, "LiteRT-LM vision stream cancelled")
        } catch (t: Throwable) {
            // Catch everything including native crashes that propagate as Error
            // so the caller never sees an unhandled exception.
            Log.w(TAG, "LiteRT-LM vision stream error", t)
        } finally {
            runCatching { imageFile?.delete() }
            runCatching { audioFile?.delete() }
        }
        if (stopRequested) return ModelOutputSanitizer.clean(response.toString()).ifBlank { "Stopped." }
        return ModelOutputSanitizer.clean(response.toString()).ifBlank { "No answer generated." }
    }

    private fun streamMediaPipeVision(
        bitmap: Bitmap?,
        prompt: String,
        audioFile: File?,
        audioBytes: ByteArray?,
        onUpdate: (String) -> Unit
    ): String {
        if (bitmap?.isRecycled == true) return "Error: Image was recycled before analysis."
        val hasAudioBytes = audioBytes?.isNotEmpty() == true
        val hasAudioInput = audioFile != null || hasAudioBytes
        if (hasAudioInput && !modelSupportsAudioInput) return "This model does not support audio input."
        val inference = mediaPipeInference ?: return "Image model is not initialised."
        val mpImage = try {
            bitmap?.let { BitmapImageBuilder(it).build() }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build MPImage", t)
            return "Failed to process image."
        }
        val mediaPipeAudioBytes = try {
            if (hasAudioBytes) audioBytes else audioFile?.readBytes()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read audio attachment", t)
            return "Failed to read audio."
        }
        return try {
            val graphOptions = GraphOptions.builder()
                .setIncludeTokenCostCalculator(false)
                .setEnableVisionModality(mpImage != null)
                .setEnableAudioModality(mediaPipeAudioBytes != null)
                .build()
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTopP(0.95f)
                .setTemperature(0.7f)
                .setRandomSeed(0)
                .setGraphOptions(graphOptions)
                .build()
            val session = try {
                LlmInferenceSession.createFromOptions(inference, sessionOptions)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create vision session", t)
                return "Failed to create vision session."
            }
            activeMediaPipeSession = session
            try {
                if (stopRequested) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    return "Stopped."
                }
                if (mpImage != null) {
                    session.addImage(mpImage)
                    if (stopRequested) {
                        runCatching { session.cancelGenerateResponseAsync() }
                        return "Stopped."
                    }
                }
                if (mediaPipeAudioBytes != null) {
                    session.addAudio(mediaPipeAudioBytes)
                    if (stopRequested) {
                        runCatching { session.cancelGenerateResponseAsync() }
                        return "Stopped."
                    }
                }
                session.addQueryChunk(prompt)
                if (stopRequested) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    return "Stopped."
                }
                val accumulated = StringBuilder()
                val callbackDone = CountDownLatch(1)
                activeMediaPipeCallbackDone = callbackDone
                val future = session.generateResponseAsync(
                    ProgressListener<String> { partial, done ->
                        try {
                            if (stopRequested) return@ProgressListener
                            if (partial.isNullOrEmpty()) return@ProgressListener
                            val current = accumulated.toString()
                            if (current.isNotEmpty() && partial.startsWith(current)) {
                                accumulated.clear()
                                accumulated.append(partial)
                            } else {
                                accumulated.append(partial)
                            }
                            runCatching { onUpdate(ModelOutputSanitizer.clean(accumulated.toString())) }
                        } finally {
                            if (done) {
                                callbackDone.countDown()
                            }
                        }
                    }
                )
                activeMediaPipeFuture = future
                // If stop was already requested before we call future.get(), return early
                // so we don't block on a future that may never complete cleanly.
                if (stopRequested) {
                    runCatching { session.cancelGenerateResponseAsync() }
                }
                val finalResponse = try {
                    future.get()
                } catch (_: FutureCancellationException) {
                    Log.i(TAG, "Vision generation stopped (future cancelled)")
                    null
                } catch (_: InterruptedException) {
                    Log.i(TAG, "Vision generation interrupted")
                    Thread.currentThread().interrupt()
                    null
                } catch (t: Throwable) {
                    if (stopRequested) {
                        Log.i(TAG, "Vision stream error after stop", t)
                        null
                    } else {
                        // Don't rethrow — return the accumulated text or error.
                        Log.e(TAG, "Vision generateResponseAsync failed", t)
                        null
                    }
                } finally {
                    activeMediaPipeFuture = null
                }
                val accumulatedText = accumulated.toString()
                if (stopRequested || finalResponse == null) {
                    return ModelOutputSanitizer.clean(accumulatedText).ifBlank { "Stopped." }
                }
                if (finalResponse.isNotBlank() && finalResponse != accumulatedText) {
                    runCatching { onUpdate(ModelOutputSanitizer.clean(finalResponse)) }
                    ModelOutputSanitizer.clean(finalResponse)
                } else {
                    ModelOutputSanitizer.clean(accumulatedText.ifBlank { finalResponse })
                }
            } finally {
                // Close only the session created by this invocation. If close()
                // already claimed it, or a future invocation owns a newer
                // session, this block leaves that object alone.
                if (activeMediaPipeSession === session) {
                    val callbackDone = activeMediaPipeCallbackDone
                    activeMediaPipeCallbackDone = null
                    activeMediaPipeSession = null
                    if (!isBeingClosed) {
                        if (waitForCallbackToReturn(callbackDone)) {
                            runCatching { session.close() }.onFailure { t ->
                                Log.w(TAG, "Session close error (safe to ignore after stop)", t)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Catch-all for any native crash that propagates as an Error
            // (e.g. UnsatisfiedLinkError, InternalError from JNI).
            Log.e(TAG, "Vision stream unexpected error", t)
            "Image analysis failed."
        } finally {
            runCatching { mpImage?.close() }.onFailure { t ->
                Log.w(TAG, "MPImage close error (safe to ignore)", t)
            }
            runCatching { audioFile?.delete() }
        }
    }

    private fun waitForCallbackToReturn(callbackDone: CountDownLatch?): Boolean {
        if (callbackDone == null || callbackDone.count == 0L) return true
        val completed = runCatching {
            callbackDone.await(2, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!completed) {
            Log.w(TAG, "Timed out waiting for MediaPipe vision callback; leaving session for native cleanup")
        }
        return completed
    }

    private fun File.containsAudioModelAssets(): Boolean {
        val audioMarkers = listOf("AUDIO_ENCODER", "AUDIO_ADAPTER", "AUDIO_EMBEDDER", "AUDIO")
        return when {
            isDirectory -> walkTopDown()
                .take(80)
                .any { file -> audioMarkers.any { marker -> file.name.contains(marker, ignoreCase = true) } }
            else -> runCatching {
                java.util.zip.ZipFile(this).use { zip ->
                    zip.entries().asSequence().any { entry ->
                        audioMarkers.any { marker -> entry.name.contains(marker, ignoreCase = true) }
                    }
                }
            }.getOrElse {
                containsAnyAsciiMarker(audioMarkers)
            }
        }
    }

    private fun File.containsAnyAsciiMarker(markers: List<String>, scanBytes: Int = 256 * 1024): Boolean {
        if (!isFile || length() <= 0L) return false
        val markerBytes = markers.map { it.uppercase(java.util.Locale.US).toByteArray(Charsets.US_ASCII) }
        return runCatching {
            inputStream().use { input ->
                val buffer = ByteArray(minOf(length(), scanBytes.toLong()).toInt())
                val read = input.read(buffer)
                read > 0 && markerBytes.any { marker -> buffer.indexOf(marker, read) >= 0 }
            }
        }.getOrDefault(false)
    }

    private fun ByteArray.indexOf(needle: ByteArray, length: Int): Int {
        if (needle.isEmpty() || length < needle.size) return -1
        val maxStart = length - needle.size
        for (start in 0..maxStart) {
            var matches = true
            for (index in needle.indices) {
                val candidate = this[start + index].toInt().let { byte ->
                    if (byte in 97..122) byte - 32 else byte
                }.toByte()
                if (candidate != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private fun Bitmap.writeToCacheFile(): File {
        val dir = File(context.cacheDir, "vision_questions").apply { mkdirs() }
        pruneVisionQuestionCache(dir)
        val file = File(dir, "vision-question-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { output ->
            compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        return file
    }

    private fun pruneVisionQuestionCache(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("vision-question-") } ?: return
        files
            .filter { now - it.lastModified() > 6L * 60L * 60L * 1000L }
            .forEach { runCatching { it.delete() } }
        files
            .sortedByDescending { it.lastModified() }
            .drop(8)
            .forEach { runCatching { it.delete() } }
    }

    private fun List<VisionLabel>.formatForQuestion(question: String): String {
        if (isEmpty()) return "I could not read labels from this image."
        val labels = joinToString(separator = ", ") { label ->
            "${label.label} ${String.format(java.util.Locale.US, "%.1f", label.score * 100)}%"
        }
        return "This selected model is an image-label classifier, so it cannot answer '$question' freely. Top labels: $labels."
    }

    private fun Bitmap.toInputBuffer(width: Int, height: Int): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(this, width, height, true)
        val buffer = ByteBuffer.allocateDirect(4 * width * height * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        pixels.forEach { pixel ->
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private enum class RuntimeKind {
        None,
        Classifier,
        MediaPipeVision,
        LiteRtLm
    }

    private companion object {
        const val TAG = "VisionInferenceManager"
        const val DEFAULT_IMAGE_PROMPT = "Describe the image"
        const val MAX_RESPONSE_TOKENS = 1024
    }
}
