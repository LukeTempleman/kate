package tech.gonxt.kate.brain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tech.gonxt.kate.core.ChatMessage
import kotlin.math.absoluteValue

/**
 * M1.1 stand-in brain: streams plausible Kate replies word by word so the
 * interface, orb states, and transcript streaming are real before the LLM lands.
 */
class DummyBrain(private val tokenDelayMs: Long = 40) : Brain {

    override val id = "dummy"

    override suspend fun isAvailable() = true

    override fun reply(history: List<ChatMessage>): Flow<String> = flow {
        val prompt = history.lastOrNull()?.text.orEmpty()
        val reply = pick(prompt)
        for (word in reply.split(" ")) {
            emit("$word ")
            delay(tokenDelayMs)
        }
    }

    private fun pick(prompt: String): String {
        val p = prompt.lowercase()
        return when {
            "hello" in p || "hi " in p || p == "hi" ->
                "Hello. Lovely to hear you. What do you need?"
            "weather" in p ->
                "I can't check live weather yet. That arrives when my online brain is wired in shortly."
            "who are you" in p || "your name" in p ->
                "I'm Kate, your personal assistant. Right now I'm running on a placeholder brain while my voice and ears are tuned."
            "time" in p ->
                "My clock capability lands in a later iteration. For now, your dashboard knows best."
            else -> CANNED[prompt.hashCode().absoluteValue % CANNED.size]
        }
    }

    private companion object {
        val CANNED = listOf(
            "Understood. My real brain arrives in milestone one point four. Until then, consider this a rehearsal.",
            "Noted. I'm still on my placeholder brain, but the voice pipeline you're testing is the real thing.",
            "Right. I'll be able to answer that properly once Groq or the on-device model is connected.",
            "Got it. This is my dummy brain speaking — judge my voice, not my wit, for now.",
        )
    }
}
