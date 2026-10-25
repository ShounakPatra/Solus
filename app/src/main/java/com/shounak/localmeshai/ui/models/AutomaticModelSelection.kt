package com.shounak.localmeshai.ui.models

/** Retained with the view model: returning to a tab is not a new load request. */
class AutomaticModelSelection {
    private var previous: List<Any?>? = null

    fun shouldLoad(path: String?, modelId: String?, unsafeOverride: Boolean, retryRevision: Int): Boolean {
        val selection = path?.let { listOf(it, modelId, unsafeOverride, retryRevision) }
        if (selection == previous) return false
        previous = selection
        return selection != null
    }
}
