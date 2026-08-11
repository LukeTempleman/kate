package tech.gonxt.kate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.gonxt.kate.core.SentenceChunker

class SentenceChunkerTest {

    private fun run(tokens: List<String>): Pair<List<String>, String?> {
        val chunker = SentenceChunker()
        val sentences = mutableListOf<String>()
        tokens.forEach { sentences += chunker.feed(it) }
        return sentences to chunker.flush()
    }

    @Test
    fun `single sentence emits on terminator plus space`() {
        val (sentences, rest) = run(listOf("Hello ", "there ", "friend. ", "And"))
        assertEquals(listOf("Hello there friend."), sentences)
        assertEquals("And", rest)
    }

    @Test
    fun `multiple sentences split correctly`() {
        val (sentences, rest) = run(listOf("One is done. ", "Two is done! ", "Three?", " tail"))
        assertEquals(listOf("One is done.", "Two is done!", "Three?"), sentences)
        assertEquals("tail", rest)
    }

    @Test
    fun `abbreviations do not split`() {
        val (sentences, _) = run(listOf("Dr. Smith arrived early today. "))
        assertEquals(listOf("Dr. Smith arrived early today."), sentences)
    }

    @Test
    fun `decimal numbers do not split`() {
        val (sentences, _) = run(listOf("The value is 3.14 which is pi. "))
        assertEquals(listOf("The value is 3.14 which is pi."), sentences)
    }

    @Test
    fun `newline is a boundary`() {
        val (sentences, _) = run(listOf("First line of speech\n", "second part. "))
        assertTrue(sentences.first().startsWith("First line"))
    }

    @Test
    fun `flush on empty returns null`() {
        val chunker = SentenceChunker()
        assertNull(chunker.flush())
    }

    @Test
    fun `token split mid-sentence still forms one sentence`() {
        val (sentences, _) = run(listOf("Ka", "te spe", "aks now. ", ""))
        assertEquals(listOf("Kate speaks now."), sentences)
    }
}
