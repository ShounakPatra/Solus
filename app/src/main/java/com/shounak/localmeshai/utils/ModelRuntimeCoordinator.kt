package com.shounak.localmeshai.utils

enum class ModelRuntimeOwner {
    Chat,
    Vision
}

object ModelRuntimeCoordinator {
    private val lock = Any()
    private val releaseHandlers = mutableMapOf<ModelRuntimeOwner, () -> Unit>()
    /** Optional callback fired when an owner is evicted by a competing activate(). */
    private val releasedCallbacks = mutableMapOf<ModelRuntimeOwner, () -> Unit>()
    private var activeOwner: ModelRuntimeOwner? = null

    fun register(
        owner: ModelRuntimeOwner,
        onReleased: (() -> Unit)? = null,
        release: () -> Unit
    ) {
        synchronized(lock) {
            releaseHandlers[owner] = release
            if (onReleased != null) releasedCallbacks[owner] = onReleased
        }
    }

    fun unregister(owner: ModelRuntimeOwner) {
        synchronized(lock) {
            releaseHandlers.remove(owner)
            releasedCallbacks.remove(owner)
            if (activeOwner == owner) {
                activeOwner = null
            }
        }
    }

    fun setReleasedCallback(owner: ModelRuntimeOwner, onReleased: (() -> Unit)?) {
        synchronized(lock) {
            if (onReleased == null) {
                releasedCallbacks.remove(owner)
            } else {
                releasedCallbacks[owner] = onReleased
            }
        }
    }

    fun activate(owner: ModelRuntimeOwner) {
        data class EvictedOwner(val release: () -> Unit, val onReleased: (() -> Unit)?)
        val evicted = synchronized(lock) {
            activeOwner = owner
            releaseHandlers
                .filterKeys { it != owner }
                .map { (k, v) -> EvictedOwner(v, releasedCallbacks[k]) }
        }
        evicted.forEach { e ->
            runCatching { e.release() }
            runCatching { e.onReleased?.invoke() }
        }
    }

    fun clear(owner: ModelRuntimeOwner) {
        synchronized(lock) {
            if (activeOwner == owner) {
                activeOwner = null
            }
        }
    }

    fun isActive(owner: ModelRuntimeOwner): Boolean {
        return synchronized(lock) { activeOwner == owner }
    }
}
