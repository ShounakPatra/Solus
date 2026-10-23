package com.shounak.localmeshai.ai

import kotlin.math.exp
import kotlin.random.Random

/** Primitive candidate storage: greedy O(vocabulary), top-K O(vocabulary * log K). */
internal object PortableSampler {
    private const val INVALID_LOGITS = "Model produced no finite logits"

    private fun argmax(logits: FloatArray): Int {
        var best = -1
        for (index in logits.indices) {
            if (logits[index].isFinite() && (best < 0 || logits[index] > logits[best])) best = index
        }
        require(best >= 0) { INVALID_LOGITS }
        return best
    }

    /** Returns best-first indices, preserving lower token IDs for ties. Never boxes vocabulary IDs. */
    internal fun candidates(logits: FloatArray, limit: Int): IntArray {
        val heap = IntArray(limit.coerceIn(0, logits.size))
        var size = 0
        fun weaker(a: Int, b: Int) = logits[a] < logits[b] || (logits[a] == logits[b] && a > b)
        fun siftDown(start: Int, end: Int) {
            var parent = start
            while (parent * 2 + 1 < end) {
                var child = parent * 2 + 1
                if (child + 1 < end && weaker(heap[child + 1], heap[child])) child++
                if (!weaker(heap[child], heap[parent])) break
                val swap = heap[parent]; heap[parent] = heap[child]; heap[child] = swap
                parent = child
            }
        }
        if (heap.isEmpty()) return heap
        for (index in logits.indices) {
            if (!logits[index].isFinite()) continue
            if (size < heap.size) {
                var child = size++
                heap[child] = index
                while (child > 0) {
                    val parent = (child - 1) / 2
                    if (!weaker(heap[child], heap[parent])) break
                    val swap = heap[parent]; heap[parent] = heap[child]; heap[child] = swap
                    child = parent
                }
            } else if (weaker(heap[0], index)) {
                heap[0] = index
                siftDown(0, size)
            }
        }
        // Drain the weakest root to the end, leaving strongest candidates at the front.
        for (end in size - 1 downTo 1) {
            val swap = heap[0]; heap[0] = heap[end]; heap[end] = swap
            siftDown(0, end)
        }
        return if (size == heap.size) heap else heap.copyOf(size)
    }

    fun sample(logits: FloatArray, temperature: Float, topP: Float, topK: Int, random: Random = Random.Default): Int {
        require(temperature.isFinite()) { "Sampling temperature must be finite" }
        if (temperature <= 0f) return argmax(logits)
        require(topP.isFinite()) { "Sampling topP must be finite" }
        // Without top-K or nucleus filtering, no sort is needed. Softmax still needs all finite logits.
        if (topK <= 0 && topP >= 1f) {
            val max = logits[argmax(logits)]
            val weights = DoubleArray(logits.size)
            var total = 0.0
            for (index in logits.indices) if (logits[index].isFinite()) {
                weights[index] = exp((logits[index].toDouble() - max) / temperature)
                total += weights[index]
            }
            var draw = random.nextDouble() * total
            var last = 0
            for (index in weights.indices) if (weights[index] > 0.0) {
                last = index
                draw -= weights[index]
                if (draw < 0.0) return index
            }
            return last
        }
        val selected = candidates(logits, if (topK > 0) topK else logits.size)
        require(selected.isNotEmpty()) { INVALID_LOGITS }
        val max = logits[selected[0]]
        val weights = DoubleArray(selected.size)
        var total = 0.0
        for (index in selected.indices) {
            weights[index] = exp((logits[selected[index]].toDouble() - max) / temperature)
            total += weights[index]
        }
        var cumulative = 0.0
        var count = weights.size
        val threshold = topP.coerceIn(0.01f, 1f)
        for (index in weights.indices) {
            cumulative += weights[index] / total
            if (cumulative >= threshold) { count = index + 1; break }
        }
        var mass = 0.0
        for (index in 0 until count) mass += weights[index]
        var draw = random.nextDouble() * mass
        var last = selected[0]
        for (index in 0 until count) if (weights[index] > 0.0) {
            last = selected[index]
            draw -= weights[index]
            if (draw < 0.0) return selected[index]
        }
        return last
    }
}
