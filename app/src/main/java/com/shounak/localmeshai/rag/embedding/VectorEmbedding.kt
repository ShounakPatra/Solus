package com.shounak.localmeshai.rag.embedding

import kotlin.math.sqrt

/**
 * Dense vector representation for semantic text embeddings.
 */
data class VectorEmbedding(
    val values: FloatArray
) {
    val dimension: Int get() = values.size

    fun dotProduct(other: VectorEmbedding): Float {
        return dotProduct(this.values, other.values)
    }

    fun cosineSimilarity(other: VectorEmbedding): Float {
        return cosineSimilarity(this.values, other.values)
    }

    fun magnitude(): Float {
        return magnitude(this.values)
    }

    fun normalized(): VectorEmbedding {
        return VectorEmbedding(l2Normalize(this.values))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorEmbedding
        return values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        return values.contentHashCode()
    }

    companion object {
        /**
         * Computes the dot product of two float arrays.
         */
        fun dotProduct(a: FloatArray, b: FloatArray): Float {
            val len = minOf(a.size, b.size)
            var sum = 0f
            for (i in 0 until len) {
                sum += a[i] * b[i]
            }
            return sum
        }

        /**
         * Computes the Euclidean (L2) norm of a vector.
         */
        fun magnitude(v: FloatArray): Float {
            var sum = 0f
            for (i in v.indices) {
                sum += v[i] * v[i]
            }
            return sqrt(sum)
        }

        /**
         * Returns a new L2-normalized unit vector (||v|| = 1.0).
         * If the input vector has zero magnitude, returns a zero vector.
         */
        fun l2Normalize(v: FloatArray): FloatArray {
            val mag = magnitude(v)
            if (mag <= 1e-9f) {
                return FloatArray(v.size)
            }
            val invMag = 1.0f / mag
            val out = FloatArray(v.size)
            for (i in v.indices) {
                out[i] = v[i] * invMag
            }
            return out
        }

        /**
         * Computes cosine similarity between two vectors in [-1.0, 1.0].
         * For already L2-normalized vectors, this is equivalent to dot product.
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val dot = dotProduct(a, b)
            val magA = magnitude(a)
            val magB = magnitude(b)
            val denom = magA * magB
            if (denom <= 1e-9f) return 0f
            val sim = dot / denom
            return sim.coerceIn(-1.0f, 1.0f)
        }
    }
}
