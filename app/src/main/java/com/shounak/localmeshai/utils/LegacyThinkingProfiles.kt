package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ThinkingControlStrategy
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Compatibility for a verified legacy export whose metadata predates thinking declarations. */
object LegacyThinkingProfiles {
    internal const val QWEN_SIZE = 614_236_160L
    internal const val QWEN_SHA256 = "555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4"
    private val cache = ConcurrentHashMap<String, ThinkingControlStrategy>()

    internal fun match(size: Long, verifiedSha256: String): ThinkingControlStrategy =
        if (size == QWEN_SIZE && verifiedSha256.equals(QWEN_SHA256, ignoreCase = true))
            ThinkingControlStrategy.QwenSoftSwitch else ThinkingControlStrategy.None

    fun controlFor(file: File, verifiedSha256: String? = null): ThinkingControlStrategy {
        if (file.length() != QWEN_SIZE) return ThinkingControlStrategy.None
        if (verifiedSha256 != null) return match(file.length(), verifiedSha256)
        val key = "${file.canonicalPath}:${file.length()}:${file.lastModified()}"
        return cache.getOrPut(key) {
            val before = file.length() to file.lastModified()
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Model inspection cancelled")
                    digest.update(buffer, 0, count)
                }
            }
            require(before == (file.length() to file.lastModified())) { "Model changed during inspection" }
            if (cache.size > 64) cache.clear()
            match(file.length(), digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    /** Applied only to inference input, once per latest user turn. */
    fun applyPrompt(prompt: String, strategy: ThinkingControlStrategy, thinking: Boolean): String {
        if (strategy != ThinkingControlStrategy.QwenSoftSwitch) return prompt
        val switch = if (thinking) "/think" else "/no_think"
        val clean = prompt.trimEnd()
        return if (clean.lineSequence().lastOrNull()?.trim() == switch) clean else "$clean\n$switch"
    }
}
