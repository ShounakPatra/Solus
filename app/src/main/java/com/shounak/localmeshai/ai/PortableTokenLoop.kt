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
        onToken: (Int) -> Unit
    ): String {
        require(prompt.isNotEmpty()) { "Prompt produced no tokens" }
        require(prompt.size < context) { "Prompt exceeds model context ($context tokens). Start a shorter conversation." }
        val all = prompt.toMutableList()
        val generated = mutableListOf<Int>()
        var rendered = ""
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
            check(stable.startsWith(rendered)) { "Tokenizer decoder rewrote already streamed output" }
            if (stable.length > rendered.length) {
                onText(stable.substring(rendered.length)); rendered = stable
            }
        }
        return rendered
    }
}
