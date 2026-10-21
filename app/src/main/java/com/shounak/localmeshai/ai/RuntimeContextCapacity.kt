package com.shounak.localmeshai.ai

/** Records the capacity of a successful runtime allocation, never a catalog limit. */
internal class RuntimeContextCapacity {
    @Volatile var tokens: Int? = null
        private set

    fun <T> initialize(tokens: Int, create: () -> T): T {
        require(tokens > 0)
        this.tokens = null
        val runtime = create()
        this.tokens = tokens
        return runtime
    }

    fun clear() { tokens = null }
}
