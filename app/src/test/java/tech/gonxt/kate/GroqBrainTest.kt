package tech.gonxt.kate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.gonxt.kate.brain.buildChatMlPrompt
import tech.gonxt.kate.brain.buildGroqBody
import tech.gonxt.kate.brain.parseGroqChunk
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.core.Role

class GroqBrainTest {

    @Test
    fun `parses delta content from sse line`() {
        val line = """data: {"choices":[{"delta":{"content":"Hello"},"index":0}]}"""
        assertEquals("Hello", parseGroqChunk(line))
    }

    @Test
    fun `ignores done and empty lines`() {
        assertNull(parseGroqChunk("data: [DONE]"))
        assertNull(parseGroqChunk(""))
        assertNull(parseGroqChunk(": ping"))
        assertNull(parseGroqChunk("""data: {"choices":[{"delta":{},"index":0}]}"""))
    }

    @Test
    fun `handles escaped json content`() {
        val line = """data: {"choices":[{"delta":{"content":"She said \"hi\"\n"}}]}"""
        assertEquals("She said \"hi\"\n", parseGroqChunk(line))
    }

    @Test
    fun `body includes persona system message and history`() {
        val body = buildGroqBody(
            listOf(ChatMessage(1, Role.USER, "hello")),
            persona = "You are Kate.",
        )
        assertTrue(body.contains("\"system\""))
        assertTrue(body.contains("You are Kate."))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("llama-3.3-70b-versatile"))
    }

    @Test
    fun `chatml prompt ends with empty think block`() {
        val p = buildChatMlPrompt(listOf(ChatMessage(1, Role.USER, "hi")), "persona")
        assertTrue(p.startsWith("<|im_start|>system\npersona<|im_end|>"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertTrue(p.contains("<|im_start|>user\nhi<|im_end|>"))
    }
}
