package com.shounak.localmeshai.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface LlamaTokenCallback {
    fun onToken(token: String)
    fun onComplete()
    fun onError(error: String)
}

class LlamaCppEngine(private val context: Context) {
    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var isInitialized = false
    private val lock = Any()

    companion object {
        private const val TAG = "LlamaCppEngine"
        private var isLibraryLoaded = false

        init {
            try {
                System.loadLibrary("solus_llama")
                isLibraryLoaded = true
                Log.i(TAG, "Successfully loaded native libsolus_llama.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Could not load libsolus_llama.so, fallback mode will be used: ${e.message}")
            }
        }
    }

    fun initialize(modelPath: String, threads: Int = 4, contextWindow: Int = 2048) = synchronized(lock) {
        val file = File(modelPath)
        if (!file.exists()) {
            throw IllegalArgumentException("GGUF model file not found at $modelPath")
        }

        close()

        if (isLibraryLoaded) {
            nativeHandle = nativeInitModel(modelPath, threads, contextWindow)
            if (nativeHandle == 0L) {
                throw IllegalStateException("Failed to initialize llama.cpp native handle for $modelPath")
            }
        }
        isInitialized = true
        Log.i(TAG, "LlamaCppEngine initialized for model: ${file.name}")
    }

    suspend fun generateStream(prompt: String, onToken: (String) -> Unit) = withContext(Dispatchers.IO) {
        val handle = nativeHandle
        if (!isInitialized || handle == 0L) {
            throw IllegalStateException("LlamaCppEngine is not initialized")
        }

        if (isLibraryLoaded) {
            val callback = object : LlamaTokenCallback {
                override fun onToken(token: String) {
                    onToken(token)
                }

                override fun onComplete() {
                    Log.d(TAG, "GGUF stream generation completed")
                }

                override fun onError(error: String) {
                    Log.e(TAG, "GGUF stream error: $error")
                }
            }
            nativeGenerateStream(handle, prompt, callback)
        } else {
            throw IllegalStateException("Native llama.cpp engine handle is not active. Model file must be loaded for inference.")
        }
    }

    fun stop() {
        val handle = nativeHandle
        if (isLibraryLoaded && handle != 0L) {
            nativeStop(handle)
        }
    }

    fun close() = synchronized(lock) {
        val handle = nativeHandle
        if (handle != 0L) {
            if (isLibraryLoaded) {
                nativeFree(handle)
            }
            nativeHandle = 0L
        }
        isInitialized = false
    }

    private external fun nativeInitModel(modelPath: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerateStream(handle: Long, prompt: String, callback: LlamaTokenCallback)
    private external fun nativeStop(handle: Long)
    private external fun nativeFree(handle: Long)
}
