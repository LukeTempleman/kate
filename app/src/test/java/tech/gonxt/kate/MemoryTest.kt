package tech.gonxt.kate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.gonxt.kate.memory.HashingEmbedder
import tech.gonxt.kate.memory.RecallIntents
import tech.gonxt.kate.memory.TopicExtractor
import tech.gonxt.kate.memory.cosine
import tech.gonxt.kate.memory.toBytes
import tech.gonxt.kate.memory.toFloats

class MemoryTest {

    @Test
    fun `recall intent extracts query`() {
        val i = RecallIntents.classify("Kate, what did I say about the Johannesburg meeting?")
        assertTrue(i is RecallIntents.Intent.Recall)
        assertEquals("the Johannesburg meeting", (i as RecallIntents.Intent.Recall).query)
    }

    @Test
    fun `remember variant recall`() {
        val i = RecallIntents.classify("do you remember anything about my dentist appointment")
        assertEquals("my dentist appointment", (i as RecallIntents.Intent.Recall).query)
    }

    @Test
    fun `forget and pin intents`() {
        assertEquals(RecallIntents.Intent.Forget, RecallIntents.classify("forget that"))
        assertEquals(RecallIntents.Intent.Forget, RecallIntents.classify("Kate, forget what I just said."))
        assertEquals(RecallIntents.Intent.Pin, RecallIntents.classify("pin that"))
        assertEquals(RecallIntents.Intent.Pin, RecallIntents.classify("remember this permanently"))
    }

    @Test
    fun `ordinary chat is not an intent`() {
        assertEquals(RecallIntents.Intent.None, RecallIntents.classify("what's the weather like"))
        assertEquals(RecallIntents.Intent.None, RecallIntents.classify("tell me about rome"))
    }

    @Test
    fun `topic extractor finds entities and topics`() {
        val e = TopicExtractor.extract("Remind me that Sarah wants the Meridian report before Friday's meeting")
        assertTrue(e.entities.contains("Sarah"))
        assertTrue(e.entities.contains("Meridian"))
        assertTrue(e.topics.contains("report") || e.topics.contains("meeting"))
    }

    @Test
    fun `hashing embedder puts related text closer`() {
        val emb = HashingEmbedder()
        val a = emb.embed("the weather in johannesburg is sunny")
        val b = emb.embed("what did I say about johannesburg weather")
        val c = emb.embed("purple elephants dancing tango")
        assertTrue(cosine(a, b) > cosine(a, c))
    }

    @Test
    fun `embedding bytes roundtrip`() {
        val v = floatArrayOf(0.5f, -1.25f, 3.14159f, 0f)
        assertArrayEquals(v, v.toBytes().toFloats(), 0f)
    }
}
