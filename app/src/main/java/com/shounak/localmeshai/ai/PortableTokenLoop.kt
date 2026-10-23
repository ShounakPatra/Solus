package com.shounak.localmeshai.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared production token loop, independent of JNI, ONNX and TFLite for focused contract tests. */
internal object PortableTokenLoop {
    suspend fun generate(
        prompt: IntArray,
        context: Int,
        limit: Int,
        eos: Set<Int>,
        stopped: () -> Boolean,
        next: (tokens: List<Int>, step: Int) -> Int,
        decode: (IntArray) -> String,
        onText: (String) -> Unit,
        onToken: (Int) -> Unit,
        stopSequences: List<String> = emptyList()
    ): String {
        require(prompt.isNotEmpty()) { "Prompt produced no tokens" }
        require(prompt.size < context) { "Prompt exceeds model context ($context tokens). Start a shorter conversation." }
        val all = prompt.toMutableList()
        val generated = mutableListOf<Int>()
        var rendered = ""
        var pending = ""
        for (step in 0 until minOf(limit, context - prompt.size)) {
            currentCoroutineContext().ensureActive()
            if (stopped()) break
            val token = next(all, step)
            currentCoroutineContext().ensureActive()
            if (stopped() || token in eos) break
            all.add(token); generated.add(token)
            onToken(generated.size)
            // Byte fallback may temporarily end in U+FFFD; emit only complete text.
            val stable = decode(generated.toIntArray()).trimEnd('\uFFFD')
            val stops = stopSequences.filter { it.isNotEmpty() }
            val boundary = stops.map { stable.indexOf(it) }.filter { it >= 0 }.minOrNull()
            val held = if (boundary != null) 0 else stops.maxOfOrNull { marker ->
                (1 until marker.length).filter { stable.endsWith(marker.take(it)) }.maxOrNull() ?: 0
            } ?: 0
            val visible = if (boundary != null) stable.take(boundary) else stable.dropLast(held)
            check(visible.startsWith(rendered)) { "Tokenizer decoder rewrote already streamed output" }
            if (visible.length > rendered.length) {
                onText(visible.substring(rendered.length)); rendered = visible
            }
            pending = if (boundary == null) stable.takeLast(held) else ""
            if (boundary != null) break
        }
        currentCoroutineContext().ensureActive()
        if (!stopped() && pending.isNotEmpty()) { onText(pending); rendered += pending }
        return rendered
    }
}
