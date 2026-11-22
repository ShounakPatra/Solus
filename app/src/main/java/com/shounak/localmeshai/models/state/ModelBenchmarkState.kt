package com.shounak.localmeshai.models.state

/**
 * Explicit state model for local hardware benchmarking and GGUF diagnostics.
 * Replaces uncoordinated flags: isRunningBench, isStoppingBench, showBenchDialog.
 */
sealed interface ModelBenchmarkState {
    val isRunning: Boolean get() = this is Preparing || this is Running || this is Stopping
    val statusKey: String

    object Idle : ModelBenchmarkState {
        override val statusKey: String = "idle"
    }

    data class Preparing(
        val modelId: String,
        val configSummary: String
    ) : ModelBenchmarkState {
        override val statusKey: String = "preparing"
    }

    data class Running(
        val modelId: String,
        val currentPromptIndex: Int,
        val totalPrompts: Int,
        val latestTokensPerSecond: Float = 0f
    ) : ModelBenchmarkState {
        override val statusKey: String = "running"
        val progressFraction: Float get() = if (totalPrompts > 0) currentPromptIndex.toFloat() / totalPrompts.toFloat() else 0f
    }

    data class Stopping(
        val modelId: String
    ) : ModelBenchmarkState {
        override val statusKey: String = "stopping"
    }

    data class Completed(
        val modelId: String,
        val summary: String = ""
    ) : ModelBenchmarkState {
        override val statusKey: String = "completed"
    }

    data class Failed(
        val modelId: String,
        val errorMessage: String
    ) : ModelBenchmarkState {
        override val statusKey: String = "failed"
    }

    fun toAgentDescriptor(): Map<String, Any?> = mapOf(
        "status" to statusKey,
        "isRunning" to isRunning
    )
}
