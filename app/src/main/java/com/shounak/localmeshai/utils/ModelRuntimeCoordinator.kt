package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ai.ShutdownResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class RegistrationToken(val id: String = java.util.UUID.randomUUID().toString())

enum class ModelRuntimeOwner { Chat, Vision }

/**
 * Coordinates single active model runtime ownership across Chat and Vision.
 * Protects lifecycle operations through a single admission gate and eliminates competing mutexes.
 */
object ModelRuntimeCoordinator {
    private val lock = Any()
    private val operations = Mutex()
    internal val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRuntimeBusy = MutableStateFlow(false)
    val isRuntimeBusy: StateFlow<Boolean> = _isRuntimeBusy.asStateFlow()

    private data class Registration(
        val token: RegistrationToken,
        val stop: () -> Unit,
        val release: suspend () -> ShutdownResult,
        var onReleased: ((RegistrationToken) -> Unit)?
    )
    private val registrations = mutableMapOf<ModelRuntimeOwner, Registration>()
    private var activeOwner: ModelRuntimeOwner? = null

    fun register(
        owner: ModelRuntimeOwner,
        onReleased: ((RegistrationToken) -> Unit)? = null,
        requestStop: () -> Unit = {},
        release: suspend () -> ShutdownResult
    ): RegistrationToken {
        val token = RegistrationToken()
        synchronized(lock) {
            registrations[owner] = Registration(
                token = token,
                stop = requestStop,
                release = release,
                onReleased = onReleased
            )
        }
        return token
    }

    fun unregister(owner: ModelRuntimeOwner, token: RegistrationToken? = null): Boolean {
        synchronized(lock) {
            val current = registrations[owner]
            if (current != null && (token == null || current.token == token)) {
                registrations.remove(owner)
                if (activeOwner == owner) {
                    activeOwner = null
                    _isRuntimeBusy.value = false
                }
                return true
            }
            return false
        }
    }

    fun setReleasedCallback(owner: ModelRuntimeOwner, onReleased: ((RegistrationToken) -> Unit)?) {
        synchronized(lock) { registrations[owner]?.onReleased = onReleased }
    }

    /**
     * Non-blocking stop request to the specified owner bypassing the operation gate.
     */
    fun requestStop(owner: ModelRuntimeOwner) {
        val reg = synchronized(lock) { registrations[owner] }
        reg?.stop?.invoke()
    }

    /**
     * Non-blocking stop request to the currently active runtime owner bypassing the operation gate.
     */
    fun requestActiveStop() {
        val reg = synchronized(lock) { activeOwner?.let { registrations[it] } }
        reg?.stop?.invoke()
    }

    suspend fun <T> withRuntime(owner: ModelRuntimeOwner, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val (previousReg, callbackToFire) = run {
                    val current = synchronized(lock) { activeOwner }
                    if (current != null && current != owner) {
                        val reg = synchronized(lock) { registrations[current] }
                        Pair(reg, reg?.onReleased to reg?.token)
                    } else {
                        Pair(null, null)
                    }
                }

                if (previousReg != null) {
                    previousReg.stop()
                    val shutdownResult = withTimeoutOrNull(10_000L) {
                        try {
                            previousReg.release()
                        } catch (e: Throwable) {
                            ShutdownResult.Failed(e)
                        }
                    } ?: ShutdownResult.TimedOut

                    if (shutdownResult !is ShutdownResult.Success) {
                        throw IllegalStateException(
                            "Failed to transition runtime to $owner: releasing previous owner failed with $shutdownResult"
                        )
                    }
                }

                synchronized(lock) {
                    activeOwner = owner
                }

                // Fire release callback strictly outside lock
                callbackToFire?.first?.let { cb ->
                    callbackToFire.second?.let { tok -> cb.invoke(tok) }
                }

                _isRuntimeBusy.value = true
                try {
                    block()
                } finally {
                    _isRuntimeBusy.value = false
                }
            }
        }

    suspend fun activate(owner: ModelRuntimeOwner) = withRuntime(owner) { Unit }

    fun clear(owner: ModelRuntimeOwner) {
        synchronized(lock) {
            if (activeOwner == owner) {
                activeOwner = null
                _isRuntimeBusy.value = false
            }
        }
    }

    fun isActive(owner: ModelRuntimeOwner): Boolean = synchronized(lock) { activeOwner == owner }

    suspend fun releaseAll(): ShutdownResult = withContext(Dispatchers.IO) {
        // 1. Signal stop to all registered runtimes outside operationGate
        val targets = synchronized(lock) {
            registrations.values.toList()
        }
        targets.forEach { it.stop() }

        // 2. Acquire single operation gate
        operations.withLock {
            val callbacksToFire = mutableListOf<Pair<((RegistrationToken) -> Unit), RegistrationToken>>()
            var aggregateResult: ShutdownResult = ShutdownResult.Success

            for (target in targets) {
                val res = withTimeoutOrNull(10_000L) {
                    try {
                        target.release()
                    } catch (e: Throwable) {
                        ShutdownResult.Failed(e)
                    }
                } ?: ShutdownResult.TimedOut

                if (res !is ShutdownResult.Success) {
                    if (aggregateResult is ShutdownResult.Success) {
                        aggregateResult = res
                    }
                } else {
                    target.onReleased?.let { cb -> callbacksToFire.add(cb to target.token) }
                }
            }

            if (aggregateResult is ShutdownResult.Success) {
                synchronized(lock) {
                    activeOwner = null
                    _isRuntimeBusy.value = false
                }
            }

            // Fire release callbacks outside lock
            callbacksToFire.forEach { (cb, tok) -> cb.invoke(tok) }

            aggregateResult
        }
    }
}
