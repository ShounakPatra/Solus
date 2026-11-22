package com.shounak.localmeshai.models.state

import com.shounak.localmeshai.utils.LocalModelImporter

/**
 * Explicit state model for local device model inspection and import.
 * Replaces disjoint flags in PhoneImportSession:
 * isInspecting, isRegistering, isDisposed, isTeardownStarted, isDismissPending.
 */
sealed interface PhoneImportSessionState {
    val canInspect: Boolean get() = this is Idle
    val isTerminal: Boolean get() = this is Disposed
    val statusKey: String

    object Idle : PhoneImportSessionState {
        override val statusKey: String = "idle"
    }

    data class Inspecting(
        val startedAtMs: Long = System.currentTimeMillis()
    ) : PhoneImportSessionState {
        override val statusKey: String = "inspecting"
    }

    data class DraftReady(
        val draft: LocalModelImporter.ImportResult? = null,
        val grantedUris: Set<String> = emptySet()
    ) : PhoneImportSessionState {
        override val statusKey: String = "draft_ready"
    }

    data class Registering(
        val draft: LocalModelImporter.ImportResult? = null
    ) : PhoneImportSessionState {
        override val statusKey: String = "registering"
    }

    data class TeardownPending(
        val pendingDraft: LocalModelImporter.ImportResult? = null
    ) : PhoneImportSessionState {
        override val statusKey: String = "teardown_pending"
    }

    object Disposed : PhoneImportSessionState {
        override val statusKey: String = "disposed"
    }

    fun toAgentDescriptor(): Map<String, Any?> = mapOf(
        "status" to statusKey,
        "canInspect" to canInspect,
        "isTerminal" to isTerminal
    )
}
