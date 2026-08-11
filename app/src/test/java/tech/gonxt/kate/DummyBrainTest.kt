package tech.gonxt.kate

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.gonxt.kate.brain.DummyBrain
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.core.Role

@OptIn(ExperimentalCoroutinesApi::class)
class DummyBrainTest {

    @Test
    fun `streams a multi-token reply for any prompt`() = runTest {
        val brain = DummyBrain(tokenDelayMs = 0)
        val tokens = brain.reply(listOf(ChatMessage(1, Role.USER, "hello"))).toList()
        assertTrue(tokens.size > 3)
        assertTrue(tokens.joinToString("").trim().isNotEmpty())
    }

    @Test
    fun `identity prompt mentions kate`() = runTest {
        val brain = DummyBrain(tokenDelayMs = 0)
        val text = brain.reply(listOf(ChatMessage(1, Role.USER, "who are you?"))).toList().joinToString("")
        assertTrue(text.contains("Kate"))
    }
}
