package tech.gonxt.kate

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.gonxt.kate.brain.Brain
import tech.gonxt.kate.brain.BrainRouter
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.settings.BrainMode

@OptIn(ExperimentalCoroutinesApi::class)
class BrainRouterTest {

    private class StubBrain(
        override val id: String,
        private val available: Boolean = true,
        private val fails: Boolean = false,
        private val tokens: List<String> = listOf("hi ", "there"),
    ) : Brain {
        override suspend fun isAvailable() = available
        override fun reply(history: List<ChatMessage>): Flow<String> = flow {
            if (fails) error("$id down")
            tokens.forEach { emit(it) }
        }
    }

    private fun router(
        online: Brain,
        primary: Brain,
        fallback: Brain,
        mode: BrainMode = BrainMode.AUTO,
        constrained: Boolean = false,
    ) = BrainRouter(online, primary, fallback, { mode }, { constrained })

    @Test
    fun `auto prefers online`() = runTest {
        val r = router(StubBrain("groq"), StubBrain("big"), StubBrain("small"))
        r.reply(emptyList()).toList()
        assertEquals("GROQ", r.activeLabel.value)
    }

    @Test
    fun `silent fallback when online fails before speaking`() = runTest {
        val r = router(StubBrain("groq", fails = true), StubBrain("big"), StubBrain("small"))
        val out = r.reply(emptyList()).toList()
        assertEquals(listOf("hi ", "there"), out)
        assertEquals("BIG", r.activeLabel.value)
    }

    @Test
    fun `offline mode skips online even when available`() = runTest {
        val r = router(StubBrain("groq"), StubBrain("big"), StubBrain("small"), mode = BrainMode.OFFLINE)
        r.reply(emptyList()).toList()
        assertEquals("BIG", r.activeLabel.value)
    }

    @Test
    fun `constrained device prefers small model`() = runTest {
        val r = router(
            StubBrain("groq", available = false),
            StubBrain("big"),
            StubBrain("small"),
            constrained = true,
        )
        r.reply(emptyList()).toList()
        assertEquals("SMALL", r.activeLabel.value)
    }

    @Test
    fun `no brains available yields graceful message`() = runTest {
        val r = router(
            StubBrain("groq", available = false),
            StubBrain("big", available = false),
            StubBrain("small", available = false),
        )
        val out = r.reply(emptyList()).toList()
        assertEquals(1, out.size)
        assertEquals("NONE", r.activeLabel.value)
    }
}
