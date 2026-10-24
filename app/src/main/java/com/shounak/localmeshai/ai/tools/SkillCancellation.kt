package com.shounak.localmeshai.ai.tools

/** Binds blocking I/O to its one tool invocation, including cancellation before I/O starts. */
internal class SkillCancellation {
    private val lock = Any()
    private var cancelled = false
    private var action: (() -> Unit)? = null
    private fun attach(value: () -> Unit) {
        val invoke = synchronized(lock) { action = value; cancelled }
        if (invoke) value()
    }
    fun cancel() {
        val value = synchronized(lock) { cancelled = true; action }
        value?.invoke()
    }
    fun <T> run(block: () -> T): T {
        current.set(this)
        return try { block() } finally { current.remove() }
    }
    companion object {
        private val current = ThreadLocal<SkillCancellation>()
        fun register(action: () -> Unit) { current.get()?.attach(action) }
    }
}
