package com.shounak.localmeshai.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Successful chat/vision loads only; benchmark runs must not reorder the model library. */
class ModelUsageStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("model_usage", Context.MODE_PRIVATE)
    private val mutableUsedAt = MutableStateFlow(prefs.all.mapNotNull { (id, value) ->
        (value as? Long)?.let { id to it }
    }.toMap())
    val usedAt = mutableUsedAt.asStateFlow()

    @Synchronized fun recordUsed(modelId: String) {
        if (modelId.isBlank()) return
        val time = maxOf(System.currentTimeMillis(), (mutableUsedAt.value.values.maxOrNull() ?: 0L) + 1L)
        prefs.edit().putLong(modelId, time).apply()
        mutableUsedAt.value = mutableUsedAt.value + (modelId to time)
    }

    @Synchronized fun remove(modelId: String) {
        prefs.edit().remove(modelId).apply()
        mutableUsedAt.value = mutableUsedAt.value - modelId
    }

    companion object {
        @Volatile private var instance: ModelUsageStore? = null
        fun get(context: Context): ModelUsageStore = instance ?: synchronized(this) {
            instance ?: ModelUsageStore(context.applicationContext).also { instance = it }
        }
    }
}
