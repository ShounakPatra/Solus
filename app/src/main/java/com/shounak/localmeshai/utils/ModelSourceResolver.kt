package com.shounak.localmeshai.utils

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelBundle
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

object ModelSourceResolver {
    private const val TAG = "ModelSourceResolver"

    interface Lease : AutoCloseable {
        val paths: List<String>
        val projectorPath: String?
        val isMultimodal: Boolean get() = projectorPath != null
    }

    private class ModelBundleLease(
        override val paths: List<String>,
        override val projectorPath: String?,
        private val closeables: List<AutoCloseable>
    ) : Lease {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            for (closeable in closeables) {
                runCatching { closeable.close() }.onFailure {
                    Log.w(TAG, "Error closing leased descriptor: ${it.message}")
                }
            }
        }
    }

    /**
     * Resolves all artifacts in a [ModelBundle] into runtime paths accessible by native code.
     * Persisted document URIs are resolved via read-only [ParcelFileDescriptor]s using /proc/self/fd/<fd>,
     * and kept open for the lifetime of the returned [Lease].
     */
    fun resolve(context: Context, bundle: ModelBundle): Lease {
        val openedCloseables = mutableListOf<AutoCloseable>()
        try {
            val resolvedPaths = mutableListOf<String>()
            for (artifact in bundle.mainArtifacts) {
                val (path, closeable) = resolveSource(context, artifact.source)
                resolvedPaths.add(path)
                if (closeable != null) {
                    openedCloseables.add(closeable)
                }
            }

            var resolvedProjectorPath: String? = null
            if (bundle.projectorArtifact != null) {
                val (path, closeable) = resolveSource(context, bundle.projectorArtifact.source)
                resolvedProjectorPath = path
                if (closeable != null) {
                    openedCloseables.add(closeable)
                }
            }

            return ModelBundleLease(
                paths = resolvedPaths,
                projectorPath = resolvedProjectorPath,
                closeables = openedCloseables
            )
        } catch (e: Throwable) {
            for (c in openedCloseables) {
                runCatching { c.close() }
            }
            throw e
        }
    }

    /**
     * Resolves a single [ArtifactSource] to a readable native path and an optional [AutoCloseable] lease.
     */
    fun resolveSource(context: Context, source: ArtifactSource): Pair<String, AutoCloseable?> {
        return when (source) {
            is ArtifactSource.AppManaged -> {
                val file = File(source.path)
                if (!file.exists()) {
                    throw FileNotFoundException("App managed file does not exist: ${source.path}")
                }
                val lease = ModelArtifactStorage.leaseArtifact(file)
                Pair(file.absolutePath, lease)
            }
            is ArtifactSource.PersistedUri -> {
                val uri = Uri.parse(source.uriString)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Failed to open file descriptor for URI: ${source.uriString}")
                val fdPath = "/proc/self/fd/${pfd.fd}"
                Pair(fdPath, pfd)
            }
        }
    }

    /**
     * Verifies that all artifacts in a [ModelBundle] are physically accessible and readable.
     * For storage imports with persisted URIs, verifies that ContentResolver can open a file descriptor.
     */
    fun checkBundleAccessible(context: Context, bundle: ModelBundle): Boolean {
        for (artifact in bundle.allArtifacts) {
            when (val source = artifact.source) {
                is ArtifactSource.AppManaged -> {
                    val file = File(source.path)
                    if (!file.exists() || !file.canRead()) return false
                }
                is ArtifactSource.PersistedUri -> {
                    val uri = Uri.parse(source.uriString)
                    val accessible = runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
                    if (!accessible) return false
                }
            }
        }
        return true
    }
}
