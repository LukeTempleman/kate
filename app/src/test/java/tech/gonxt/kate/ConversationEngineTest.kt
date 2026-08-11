package tech.gonxt.kate

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.gonxt.kate.audio.TtsEngine
import tech.gonxt.kate.brain.Brain
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.core.ConversationEngine
import tech.gonxt.kate.core.OrbState
import tech.gonxt.kate.core.Role

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationEngineTest {

    private class FakeBrain : Brain {
        override val id = "fake"
        override suspend fun isAvailable() = true
        override fun reply(history: List<ChatMessage>): Flow<String> = flow {
            emit("Right ")
            emit("then. ")
            emit("All done ")
            emit("here. ")
        }
    }

    private class FakeTts : TtsEngine {
        override val id = "fake"
        override val amplitude: StateFlow<Float> = MutableStateFlow(0f)
        val spoken = mutableListOf<String>()
        var stopCalls = 0
        override suspend fun speak(sentence: String) { spoken += sentence }
        override fun stop() { stopCalls++ }
    }

    @Test
    fun `turn produces user and kate messages then returns to idle`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(FakeBrain(), tts, this)

        engine.sendUserText("hello kate")
        advanceUntilIdle()

        val msgs = engine.messages.value
        assertEquals(2, msgs.size)
        assertEquals(Role.USER, msgs[0].role)
        assertEquals("hello kate", msgs[0].text)
        assertEquals(Role.KATE, msgs[1].role)
        assertEquals("Right then. All done here.", msgs[1].text)
        assertFalse(msgs[1].streaming)
        assertEquals(OrbState.IDLE, engine.orbState.value)
    }

    @Test
    fun `sentences are spoken as they complete`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(FakeBrain(), tts, this)

        engine.sendUserText("hello")
        advanceUntilIdle()

        assertEquals(listOf("Right then.", "All done here."), tts.spoken)
    }

    @Test
    fun `blank input is ignored`() = runTest {
        val engine = ConversationEngine(FakeBrain(), FakeTts(), this)
        engine.sendUserText("   ")
        advanceUntilIdle()
        assertTrue(engine.messages.value.isEmpty())
    }

    @Test
    fun `barge-in stops tts and finalises streaming message`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(FakeBrain(), tts, this)

        engine.sendUserText("hello")
        advanceUntilIdle()
        engine.bargeIn()

        assertTrue(tts.stopCalls >= 1)
        assertEquals(OrbState.IDLE, engine.orbState.value)
        assertTrue(engine.messages.value.none { it.streaming })
    }

    @Test
    fun `simulated voice input streams partials then submits`() = runTest {
        val engine = ConversationEngine(FakeBrain(), FakeTts(), this)

        engine.simulateVoiceInput("kate are you there")
        advanceUntilIdle()

        assertEquals("", engine.partialUserText.value)
        assertEquals("kate are you there", engine.messages.value.first().text)
        assertEquals(OrbState.IDLE, engine.orbState.value)
    }
}
