package com.shounak.localmeshai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.shounak.localmeshai.models.*
import com.shounak.localmeshai.utils.AudioUtils
import kotlinx.coroutines.*
import java.io.File

/** Synthetic inputs measure pipeline latency, never accuracy. Uses the production media manager. */
internal object MediaBenchmarkRunner {
    suspend fun run(context: Context, model: ModelInfo, progress: (BenchmarkProgress) -> Unit, useAudio: Boolean = false): BenchmarkResult =
        BenchmarkAdmission.run(model.id) { withContext(Dispatchers.IO) { coroutineScope {
            val task = model.bundle?.capabilities?.task ?: model.downloadSpec?.capabilities?.task
            val imageGeneration = task == ModelTask.ImageGeneration
            val audioOnly = useAudio || task == ModelTask.SpeechRecognition || (!model.supportsVision && model.supportsAudio)
            val bitmap = if (!imageGeneration && !audioOnly) Bitmap.createBitmap(224,224,Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
            } else null
            val wav = if (audioOnly) File.createTempFile("bench_audio_", ".wav", context.cacheDir).apply {
                val pcm = java.nio.ByteBuffer.allocate(16_000 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                repeat(16_000) { pcm.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 16_000) * 2000).toInt().toShort()) }
                writeBytes(AudioUtils.createWavFromPcm16(pcm.array(),16000,1))
            } else null
            val engine = VisionInferenceManager(context)
            val stopWatcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { engine.cancelGeneration() } }
            try {
                progress(BenchmarkProgress("Loading media pipeline",0,4))
                val start = System.nanoTime()
                engine.initialize(model.localPath ?: model.bundle?.mainArtifacts?.firstOrNull()?.source?.location ?: error("Missing model path"),
                    modelId=model.id,modelName=model.name,modelSize=model.size,supportsAudioInput=model.supportsAudio,
                    bundle=model.bundle)
                val loadMs=(System.nanoTime()-start)/1e6
                val samples=mutableListOf<Double>()
                repeat(4) { index ->
                    currentCoroutineContext().ensureActive()
                    progress(BenchmarkProgress(if(index==0) "Cold task" else "Warm task",index,4,
                        "Synthetic input; latency only, not accuracy"))
                    engine.resetConversation()
                    val begin=System.nanoTime()
                    val result = engine.askStreaming(bitmap, if(imageGeneration) "A blue square on a white background" else if(audioOnly) "Transcribe this audio" else "Describe this image",
                        audioFile=wav,audioBytes=wav?.readBytes(),onUpdate={})
                    currentCoroutineContext().ensureActive()
                    val emptySpeech = audioOnly && result.emptyReason == EmptyResponseReason.NoVisibleAnswer && result.error == ResponseFinalizer.FAILED_NO_ANSWER_MESSAGE
                    check((result.status != RequestTerminalStatus.Failed || emptySpeech) && result.status != RequestTerminalStatus.Stopped) { result.error ?: "Media inference failed" }
                    if(imageGeneration) check(engine.generatedImage?.isFile == true) { "Pipeline produced no image" }
                    samples.add((System.nanoTime()-begin)/1e6)
                    engine.generatedImage?.delete()
                }
                progress(BenchmarkProgress("Complete",4,4))
                val median=samples.drop(1).sorted()[1]
                val fixture = if(audioOnly) "1-second generated tone" else if(imageGeneration) "fixed image-generation prompt" else "224x224 blue test image"
                BenchmarkResult(false,"${engine.activeBackendDisplayName}: $fixture; output accuracy was not evaluated.",
                    0.0,0.0,0.0,loadMs+samples[0],0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0L,
                    uiFramesMeasured=false,taskLatencyMedianMs=median)
            } finally {
                try {
                    withContext(NonCancellable) {
                        stopWatcher.cancelAndJoin()
                        engine.generatedImage?.delete()
                        val shutdown = engine.close()
                        check(shutdown.isSuccess) { "Benchmark runtime did not shut down: $shutdown" }
                    }
                } finally { bitmap?.recycle(); wav?.delete() }
            }
        } } }
}
