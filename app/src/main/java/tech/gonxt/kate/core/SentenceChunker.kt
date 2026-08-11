package tech.gonxt.kate.core

/**
 * Splits a token stream into complete sentences so TTS can start speaking
 * on sentence #1 while later sentences are still generating (spec M1.2).
 *
 * Feed tokens with [feed]; each call returns zero or more completed sentences.
 * Call [flush] at end-of-stream for the trailing fragment.
 */
class SentenceChunker(private val minChars: Int = 2) {

    private val buffer = StringBuilder()

    fun feed(token: String): List<String> {
        buffer.append(token)
        val out = mutableListOf<String>()
        while (true) {
            val cut = findBoundary() ?: break
            val sentence = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (sentence.isNotEmpty()) out += sentence
        }
        return out
    }

    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return rest.ifEmpty { null }
    }

    private fun findBoundary(): Int? {
        // Boundary = terminator followed by whitespace, skipping abbreviations
        // like "Dr." and list numbers like "1."
        for (i in 0 until buffer.length - 1) {
            val c = buffer[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                val next = buffer[i + 1]
                if (!next.isWhitespace() && c != '\n') continue
                if (i + 1 < minChars) continue
                if (c == '.' && isAbbreviation(i)) continue
                return i + 1
            }
        }
        return null
    }

    private fun isAbbreviation(dotIndex: Int): Boolean {
        val start = (dotIndex - 1 downTo 0).firstOrNull { buffer[it].isWhitespace() }?.plus(1) ?: 0
        val word = buffer.substring(start, dotIndex)
        if (word.length <= 1 && word.all { it.isDigit() }) return true
        return word in ABBREVIATIONS
    }

    private companion object {
        val ABBREVIATIONS = setOf("Mr", "Mrs", "Ms", "Dr", "St", "vs", "etc", "e.g", "i.e", "eg", "ie")
    }
}
