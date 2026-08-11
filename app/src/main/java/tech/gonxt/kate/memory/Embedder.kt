package tech.gonxt.kate.memory

import android.content.Context
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

interface Embedder {
    val id: String
    fun embed(text: String): FloatArray
}

fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
}

fun FloatArray.toBytes(): ByteArray {
    val out = ByteArray(size * 4)
    for (i in indices) {
        val bits = java.lang.Float.floatToIntBits(this[i])
        out[i * 4] = (bits shr 24).toByte()
        out[i * 4 + 1] = (bits shr 16).toByte()
        out[i * 4 + 2] = (bits shr 8).toByte()
        out[i * 4 + 3] = bits.toByte()
    }
    return out
}

fun ByteArray.toFloats(): FloatArray {
    val out = FloatArray(size / 4)
    for (i in out.indices) {
        val bits = ((this[i * 4].toInt() and 0xFF) shl 24) or
            ((this[i * 4 + 1].toInt() and 0xFF) shl 16) or
            ((this[i * 4 + 2].toInt() and 0xFF) shl 8) or
            (this[i * 4 + 3].toInt() and 0xFF)
        out[i] = java.lang.Float.intBitsToFloat(bits)
    }
    return out
}

/**
 * Zero-dependency fallback: character-trigram hashing into 256 dims.
 * Lexical rather than semantic, but recall of "what did I say about X"
 * still works offline before the real embedding model is downloaded.
 */
class HashingEmbedder(private val dims: Int = 256) : Embedder {

    override val id = "hash-trigram-v1"

    override fun embed(text: String): FloatArray {
        val v = FloatArray(dims)
        val clean = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), "") + " "
        for (i in 0..clean.length - 3) {
            val tri = clean.substring(i, i + 3)
            val h = abs(tri.hashCode())
            v[h % dims] += 1f
        }
        return v
    }
}

/** Universal Sentence Encoder via MediaPipe — real semantic recall (spec §1.4). */
class MediaPipeEmbedder(context: Context, modelFile: File) : Embedder {

    override val id = "mp-use-v1"

    private val embedder = TextEmbedder.createFromFile(context, modelFile.absolutePath)

    override fun embed(text: String): FloatArray {
        val result = embedder.embed(text.take(1000))
        return result.embeddingResult().embeddings().first().floatEmbedding()
    }
}
