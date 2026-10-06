package ai.unicto.unibot.knowledge

import kotlin.math.sqrt

/**
 * On-device text embeddings with zero dependencies and a tiny RAM footprint.
 *
 * Character-trigram signed hashing into a fixed 128-dimensional vector, L2
 * normalized and int8-quantized (1 byte per dimension). This is a retrieval
 * heuristic, not a neural embedding — but it runs in microseconds with no
 * model download, no network, and no native library, which is exactly what a
 * 4 GB phone needs for a personal knowledge base.
 *
 * RAM budget: 3,000 chunks x 128 B = 384 KB for the whole vector index.
 */
object HashedEmbedding {
    const val DIM = 128

    /**
     * Embed [text] into a quantized 128-byte vector. Deterministic: the same
     * text always yields the same bytes. Empty text yields the zero vector.
     */
    fun embed(text: String): ByteArray {
        val acc = FloatArray(DIM)
        if (text.isNotBlank()) {
            val padded = "^${text.lowercase()}$"
            var i = 0
            while (i + 3 <= padded.length) {
                var tri = padded[i].code
                tri = tri * 31 + padded[i + 1].code
                tri = tri * 31 + padded[i + 2].code
                val idx = (tri and Int.MAX_VALUE) % DIM
                // Signed hashing: even hash adds, odd subtracts. Cancels
                // collisions instead of stacking them.
                acc[idx] += if ((tri and 1) == 0) 1f else -1f
                i++
            }
        }
        var normSq = 0f
        for (v in acc) normSq += v * v
        val out = ByteArray(DIM)
        if (normSq > 0f) {
            val norm = sqrt(normSq)
            for (d in 0 until DIM) {
                out[d] = (acc[d] / norm * 127f).toInt().coerceIn(-127, 127).toByte()
            }
        }
        return out
    }

    /**
     * Cosine similarity of two quantized vectors, in [-1, 1]. Zero vectors
     * score 0.
     */
    fun cosine(a: ByteArray, b: ByteArray): Float {
        require(a.size == DIM && b.size == DIM) { "vectors must be $DIM-dimensional" }
        var dot = 0
        var na = 0
        var nb = 0
        for (i in 0 until DIM) {
            val x = a[i].toInt()
            val y = b[i].toInt()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0 || nb == 0) return 0f
        return (dot / sqrt((na * nb).toFloat())).coerceIn(-1f, 1f)
    }
}
