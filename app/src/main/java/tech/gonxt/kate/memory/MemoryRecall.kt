package tech.gonxt.kate.memory

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pre-brain intercept: memory questions are answered from the store with a
 * source, never hallucinated by the LLM. Returns null to let the brain answer.
 */
class MemoryRecall(private val store: MemoryStore, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun intercept(userText: String): String? =
        when (val intent = RecallIntents.classify(userText)) {
            is RecallIntents.Intent.Recall -> answerRecall(intent.query)
            RecallIntents.Intent.Forget ->
                if (store.forgetLast() != null) "Forgotten. It's gone from my memory."
                else "There's nothing recent to forget."
            RecallIntents.Intent.Pin ->
                if (store.pinLast() != null) "Pinned. I'll keep that permanently."
                else "There's nothing recent to pin."
            RecallIntents.Intent.None -> null
        }

    private suspend fun answerRecall(query: String): String {
        val hits = store.recall(query)
        if (hits.isEmpty()) return "I don't have anything about $query in my memory yet."
        val best = hits.first()
        val extra = if (hits.size > 1) " I have ${hits.size - 1} more related note${if (hits.size > 2) "s" else ""}." else ""
        return "About $query — ${whenSaid(best.memory.createdAt)} you said: “${best.memory.content}”.$extra"
    }

    private fun whenSaid(at: Long): String {
        val elapsed = clock() - at
        val minutes = elapsed / 60_000
        return when {
            minutes < 2 -> "just now"
            minutes < 60 -> "$minutes minutes ago"
            minutes < 60 * 24 -> "earlier today"
            minutes < 60 * 48 -> "yesterday"
            else -> "on " + SimpleDateFormat("d MMMM", Locale.UK).format(Date(at))
        }
    }
}
