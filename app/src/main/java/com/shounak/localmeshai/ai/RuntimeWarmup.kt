package com.shounak.localmeshai.ai

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import com.shounak.localmeshai.utils.AppSettings
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object RuntimeWarmup {
    private val watchdog = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "runtime-warmup-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    /** Closing is a barrier: an in-flight cancel cannot touch a conversation after it is closed. */
    internal fun cancellationDeadline(timeoutMillis: Long = 120_000, onTimeout: () -> Unit): AutoCloseable {
        val lock = Any()
        var callback: (() -> Unit)? = onTimeout
        val task = watchdog.schedule({ synchronized(lock) { callback?.invoke() } }, timeoutMillis, TimeUnit.MILLISECONDS)
        return AutoCloseable { synchronized(lock) { callback = null }; task.cancel(false) }
    }
    fun enabled(context: Context): Boolean = AppSettings.getInstance(context).settings.value.warmupAfterLoading &&
        !RuntimeExecutionPolicy.resolve(context).isLowRam

    fun liteRt(engine: Engine, publish: (Conversation?) -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        // Two generated tokens force a decode step after the token produced from prefill logits.
        engine.createConversation(ConversationConfig(maxOutputToken = 2, thinkingConfig = ThinkingConfig(enableThinking = false),
            extraContext = mapOf("enable_thinking" to false))).use { conversation ->
            publish(conversation)
            val expired = AtomicBoolean(false)
            val deadline = cancellationDeadline { expired.set(true); runCatching { conversation.cancelProcess() } }
            try {
                conversation.sendMessage(Message.user("Hi"))
                check(!expired.get()) { "Warm-up timed out" }
            } finally { deadline.close(); publish(null) }
        }
        return SystemClock.elapsedRealtime() - start
    }

    fun gguf(context: Context, engine: LlamaCppEngine): Long {
        if (!enabled(context)) return 0
        val start = SystemClock.elapsedRealtime()
        check(engine.warmUp()) { "GGUF warm-up failed or was cancelled" }
        return (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
    }

    suspend fun portable(engine: PortableTextEngine): Long {
        val start = SystemClock.elapsedRealtime()
        PromptDeadline().run(engine::stop) {
            engine.generate(engine.renderPrompt(listOf("user" to "Hi"), "Hi", "", false), 2, 0f, 1f, 1,
                onText = {}, onToken = {})
        }
        return (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
    }
}
