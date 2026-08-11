package tech.gonxt.kate.brain

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.core.Role
import tech.gonxt.kate.models.ModelManager
import tech.gonxt.kate.models.ModelSpec

/**
 * ChatML (Qwen) with an empty think block — keeps thinking-capable models in
 * non-thinking mode so spoken replies start immediately.
 */
fun buildChatMlPrompt(history: List<ChatMessage>, persona: String): String = buildString {
    append("<|im_start|>system\n").append(persona).append("<|im_end|>\n")
    for (m in history.takeLast(12)) {
        val role = if (m.role == Role.USER) "user" else "assistant"
        append("<|im_start|>").append(role).append('\n').append(m.text).append("<|im_end|>\n")
    }
    append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
}

/** Phi-4 chat template. */
fun buildPhiPrompt(history: List<ChatMessage>, persona: String): String = buildString {
    append("<|system|>").append(persona).append("<|end|>")
    for (m in history.takeLast(12)) {
        val role = if (m.role == Role.USER) "user" else "assistant"
        append("<|").append(role).append("|>").append(m.text).append("<|end|>")
    }
    append("<|assistant|>")
}

fun buildPrompt(style: tech.gonxt.kate.models.PromptStyle, history: List<ChatMessage>, persona: String): String =
    when (style) {
        tech.gonxt.kate.models.PromptStyle.PHI -> buildPhiPrompt(history, persona)
        tech.gonxt.kate.models.PromptStyle.CHATML -> buildChatMlPrompt(history, persona)
    }

/**
 * Offline brain (spec M1.4): LiteRT-LM models via MediaPipe LLM Inference.
 * Loaded on demand — never held resident alongside Whisper + Kokoro until used.
 */
class OnDeviceBrain(
    private val context: Context,
    private val modelManager: ModelManager,
    private val spec: ModelSpec,
    override val id: String,
) : Brain {

    private var llm: LlmInference? = null
    private val loadMutex = Mutex()

    override suspend fun isAvailable(): Boolean = modelManager.isReady(spec)

    private suspend fun load(): LlmInference = loadMutex.withLock {
        llm ?: withContext(Dispatchers.IO) {
            LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelManager.path(spec).absolutePath)
                    .setMaxTokens(1024)
                    .build(),
            ).also { llm = it }
        }
    }

    /** Frees ~5GB when the system is under memory pressure or brains switch. */
    fun unload() {
        llm?.close()
        llm = null
    }

    override fun reply(history: List<ChatMessage>): Flow<String> = callbackFlow {
        val engine = load()
        val session = LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(0.6f)
                .setTopK(40)
                .build(),
        )
        session.addQueryChunk(buildPrompt(spec.promptStyle, history, KATE_PERSONA))
        session.generateResponseAsync { partial, done ->
            if (!partial.isNullOrEmpty()) trySend(partial)
            if (done) close()
        }
        awaitClose { session.close() }
    }.flowOn(Dispatchers.IO)
}
