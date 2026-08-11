package tech.gonxt.kate.brain

import kotlinx.coroutines.flow.Flow
import tech.gonxt.kate.core.ChatMessage

/**
 * AI #1 — the conversational brain. Implementations stream reply tokens.
 * Iteration 1 routing (M1.4): Groq → on-device 8B → on-device 3B.
 */
interface Brain {
    val id: String
    suspend fun isAvailable(): Boolean
    fun reply(history: List<ChatMessage>): Flow<String>
}

const val KATE_PERSONA = """You are Moneypenny, a warm, concise British personal assistant speaking aloud in a car.
Keep spoken turns short — one to three sentences. Never use markdown, lists, or emoji: you are heard, not read.
If an answer is genuinely long, give a one-sentence spoken summary and offer to save the detail for later."""
