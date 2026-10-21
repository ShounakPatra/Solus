package com.shounak.localmeshai.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production coordinator for import sheet sessions and model presentation visibility.
 *
 * Ensures all session lifecycle and presentation visibility operations
 * (open, close, hide, reveal) are strictly synchronized and atomic.
 */
class ImportPresentationCoordinator {
    private val lock = Any()
    private val openSheetSessions = mutableSetOf<String>()
    private val sessionHiddenModelIds = mutableMapOf<String, MutableSet<String>>()
    private var activeSheetSessionId: String? = null

    private val _hiddenFromPresentationIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenFromPresentationIds: StateFlow<Set<String>> = _hiddenFromPresentationIds.asStateFlow()

    fun openImportSheet(sheetSessionId: String) {
        synchronized(lock) {
            if (sheetSessionId.isBlank()) return
            activeSheetSessionId = sheetSessionId
            openSheetSessions.add(sheetSessionId)
        }
    }

    fun isSheetSessionOpen(sheetSessionId: String): Boolean {
        synchronized(lock) {
            return if (sheetSessionId.isBlank()) false else openSheetSessions.contains(sheetSessionId)
        }
    }

    fun isSessionActive(sheetSessionId: String): Boolean {
        synchronized(lock) {
            return if (sheetSessionId.isBlank()) false else activeSheetSessionId == sheetSessionId
        }
    }

    fun getActiveSessionId(): String? {
        synchronized(lock) {
            return activeSheetSessionId
        }
    }

    /**
     * Atomically checks whether the owning sheet session is still open.
     * If open, hides the newly inserted model card while the sheet remains displayed.
     * If the owning sheet has already closed, the card is published immediately (not hidden).
     * Re-importing existing models never hides the card.
     */
    fun hideModelForSession(sheetSessionId: String, modelId: String, isNewModel: Boolean): Boolean {
        synchronized(lock) {
            if (!isNewModel || sheetSessionId.isBlank()) {
                return false
            }
            if (openSheetSessions.contains(sheetSessionId)) {
                sessionHiddenModelIds.computeIfAbsent(sheetSessionId) { mutableSetOf() }.add(modelId)
                updateHiddenLocked()
                return true
            }
            return false
        }
    }

    /**
     * Dismisses a sheet session.
     * Always cleans up the specified session's presentation records and unhides its models,
     * even if another session has become active in the meantime.
     * Returns true if this dismissed session was the active session.
     */
    fun dismissImportSheet(sheetSessionId: String): Boolean {
        synchronized(lock) {
            if (sheetSessionId.isBlank()) {
                openSheetSessions.clear()
                sessionHiddenModelIds.clear()
                activeSheetSessionId = null
                updateHiddenLocked()
                return true
            }
            openSheetSessions.remove(sheetSessionId)
            sessionHiddenModelIds.remove(sheetSessionId)
            updateHiddenLocked()
            val wasActive = (activeSheetSessionId == sheetSessionId)
            if (wasActive) {
                activeSheetSessionId = null
            }
            return wasActive
        }
    }

    fun unhideModel(modelId: String) {
        synchronized(lock) {
            sessionHiddenModelIds.values.forEach { it.remove(modelId) }
            updateHiddenLocked()
        }
    }

    fun unhideAllModels() {
        synchronized(lock) {
            sessionHiddenModelIds.clear()
            updateHiddenLocked()
        }
    }

    fun getHiddenModelIdsForSession(sheetSessionId: String): Set<String> {
        synchronized(lock) {
            return sessionHiddenModelIds[sheetSessionId]?.toSet() ?: emptySet()
        }
    }

    private fun updateHiddenLocked() {
        _hiddenFromPresentationIds.value = sessionHiddenModelIds.values.flatten().toSet()
    }
}
