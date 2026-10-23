package com.shounak.localmeshai.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Successful chat/vision loads only; benchmark runs must not reorder the model library. */
class ModelUsageStore internal constructor(
    preferences: () -> SharedPreferences,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private data class Mutation(val id: String, val usedAt: Long?)
    private val mutations = Channel<Mutation>(Channel.UNLIMITED)
    private val mutableUsedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val usedAt = mutableUsedAt.asStateFlow()

    init {
        // Opening preferences can itself wait for disk. Load first, then apply queued
        // mutations so deletion during startup cannot resurrect an old usage entry.
        scope.launch(Dispatchers.IO) {
            val prefs = preferences()
            mutableUsedAt.value = prefs.all.mapNotNull { (id, value) ->
                (value as? Long)?.let { id to it }
            }.toMap()
            for (mutation in mutations) {
                val time = mutation.usedAt?.let {
                    maxOf(it, (mutableUsedAt.value.values.maxOrNull() ?: 0L) + 1L)
                }
                if (time == null) {
                    prefs.edit().remove(mutation.id).apply()
                    mutableUsedAt.value = mutableUsedAt.value - mutation.id
                } else {
                    prefs.edit().putLong(mutation.id, time).apply()
                    mutableUsedAt.value = mutableUsedAt.value + (mutation.id to time)
                }
            }
        }
    }

    fun recordUsed(modelId: String) {
        if (modelId.isNotBlank()) mutations.trySend(Mutation(modelId, System.currentTimeMillis()))
    }

    fun remove(modelId: String) {
        mutations.trySend(Mutation(modelId, null))
    }

    companion object {
        @Volatile private var instance: ModelUsageStore? = null
        fun get(context: Context): ModelUsageStore = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                ModelUsageStore({ appContext.getSharedPreferences("model_usage", Context.MODE_PRIVATE) })
                    .also { instance = it }
            }
        }
    }
}
