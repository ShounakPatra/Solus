package com.shounak.localmeshai.ui.models

/** Decides when the text and vision chats can share one loaded multimodal model instead of reloading it. */
internal object ChatModelHandoff {
    /** A hidden chat keeps its runtime while it holds exactly the model that is still selected. */
    fun keepsHiddenRuntime(hiddenModelPath: String?, selectedModelPath: String?): Boolean =
        hiddenModelPath != null && hiddenModelPath == selectedModelPath

    /** The visible chat selects its model without loading it while the other chat already owns that model. */
    fun defersLoad(visibleModelPath: String, otherOwnerModelPath: String?): Boolean =
        otherOwnerModelPath != null && otherOwnerModelPath == visibleModelPath
}
