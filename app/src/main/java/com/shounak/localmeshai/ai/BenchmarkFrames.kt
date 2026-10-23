package com.shounak.localmeshai.ai

import android.view.Choreographer
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.Collections

internal class BenchmarkFrames : AbstractCoroutineContextElement(Key), Choreographer.FrameCallback {
    companion object Key : CoroutineContext.Key<BenchmarkFrames> {
        suspend fun <T> measure(block: suspend () -> T): T {
            if (currentCoroutineContext()[Key] != null) return block()
            val monitor = BenchmarkFrames()
            withContext(Dispatchers.Main) { Choreographer.getInstance().postFrameCallback(monitor) }
            try { return withContext(monitor) { block() } }
            finally { withContext(NonCancellable + Dispatchers.Main) { Choreographer.getInstance().removeFrameCallback(monitor) } }
        }
    }
    val cpu: MutableList<Double> = Collections.synchronizedList(mutableListOf())
    val gpu: MutableList<Double> = Collections.synchronizedList(mutableListOf())
    @Volatile private var gpuPhase = false
    private var last = 0L
    fun phase(name: String) { if (name.contains("Vulkan") || name.contains("GPU")) gpuPhase = true }
    override fun doFrame(time: Long) {
        if (last != 0L) { val list = if (gpuPhase) gpu else cpu; synchronized(list) { if (list.size < 100_000) list.add((time-last)/1e6) } }
        last = time
        Choreographer.getInstance().postFrameCallback(this)
    }
    fun measured() = cpu.isNotEmpty()
}
