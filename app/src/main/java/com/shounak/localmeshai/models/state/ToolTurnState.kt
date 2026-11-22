package com.shounak.localmeshai.models.state

import com.shounak.localmeshai.ai.tools.ToolUse

/**
 * Explicit state model for multi-turn model tool calling and execution.
 * Eliminates uncoordinated counter loops and ensures tool cancellation guarantees.
 */
sealed interface ToolTurnState {
    val isComplete: Boolean get() = this is FinalAnswerReady || this is Aborted
    val statusKey: String

    object Idle : ToolTurnState {
        override val statusKey: String = "idle"
    }

    data class GeneratingThought(
        val round: Int = 0,
        val maxRounds: Int = 3
    ) : ToolTurnState {
        override val statusKey: String = "generating_thought"
    }

    data class ExecutingTools(
        val round: Int,
        val toolNames: List<String>,
        val currentTool: String
    ) : ToolTurnState {
        override val statusKey: String = "executing_tools"
    }

    data class InjectingResults(
        val round: Int,
        val executedTools: List<ToolUse>
    ) : ToolTurnState {
        override val statusKey: String = "injecting_results"
    }

    data class FinalAnswerReady(
        val allToolsUsed: List<ToolUse>
    ) : ToolTurnState {
        override val statusKey: String = "final_answer_ready"
    }

    data class Aborted(
        val reason: String,
        val toolsExecutedBeforeAbort: List<ToolUse> = emptyList()
    ) : ToolTurnState {
        override val statusKey: String = "aborted"
    }

    fun toAgentDescriptor(): Map<String, Any?> = mapOf(
        "status" to statusKey,
        "isComplete" to isComplete
    )
}
