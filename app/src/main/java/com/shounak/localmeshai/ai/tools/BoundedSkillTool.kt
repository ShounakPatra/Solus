package com.shounak.localmeshai.ai.tools

import java.util.concurrent.*

/** OpenApiTool callbacks are synchronous; bound off-thread work and always return cancellation as data. */
internal class BoundedSkillTool(private val delegate: ChatTool, private val cancelled: () -> Boolean,
    private val timeoutMillis: Long = 6000) : ChatTool {
    override val name get() = delegate.name
    override fun getToolDescriptionJsonString() = delegate.getToolDescriptionJsonString()
    override fun execute(paramsJsonString: String): String {
        if (cancelled()) return "Error: cancelled"
        // A checked durable commit cannot be rolled back by interrupting its thread.
        if (name == "mood_tracker") return delegate.execute(paramsJsonString)
        val cancellation = SkillCancellation()
        val task = FutureTask { cancellation.run { if (cancelled()) "Error: cancelled" else delegate.execute(paramsJsonString) } }
        return try {
            executor.execute(task)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                if (cancelled()) return "Error: cancelled"
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return "Error: Skill timed out"
                try { return task.get(minOf(remaining, 25_000_000), TimeUnit.NANOSECONDS) }
                catch (_: TimeoutException) { }
            }
            @Suppress("UNREACHABLE_CODE") "Error: Skill unavailable"
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); "Error: cancelled" }
        catch (_: CancellationException) { "Error: cancelled" }
        catch (_: RejectedExecutionException) { "Error: Skills are busy; try again" }
        catch (_: Exception) { "Error: Skill unavailable" }
        finally { cancellation.cancel(); task.cancel(true); executor.remove(task) }
    }
    companion object {
        private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(2),
            ThreadFactory { Thread(it, "solus-skill").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
}
