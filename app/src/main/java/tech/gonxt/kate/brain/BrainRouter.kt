package tech.gonxt.kate.brain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.settings.BrainMode

/**
 * Spec M1.4 routing: Groq → on-device 8B → on-device 3B-class, automatic and
 * silent. Falls to the next brain only if the current one produced nothing;
 * once tokens are flowing a failure surfaces (the reply is already spoken).
 */
class BrainRouter(
    private val online: Brain,
    private val devicePrimary: Brain,
    private val deviceFallback: Brain,
    private val mode: () -> BrainMode,
    /** Battery saver / thermal throttle — prefer the small model (spec M1.4). */
    private val constrained: () -> Boolean = { false },
) : Brain {

    override val id = "router"

    private val _activeLabel = MutableStateFlow("—")
    val activeLabel: StateFlow<String> = _activeLabel

    override suspend fun isAvailable(): Boolean = candidates().any { it.isAvailable() }

    suspend fun candidates(): List<Brain> {
        val device = if (constrained()) {
            listOf(deviceFallback, devicePrimary)
        } else {
            listOf(devicePrimary, deviceFallback)
        }
        val ordered = when (mode()) {
            BrainMode.ONLINE -> listOf(online)
            BrainMode.OFFLINE -> device
            BrainMode.AUTO -> listOf(online) + device
        }
        return ordered.filter { it.isAvailable() }
    }

    override fun reply(history: List<ChatMessage>): Flow<String> = flow {
        val brains = candidates()
        for ((index, brain) in brains.withIndex()) {
            var emitted = false
            try {
                _activeLabel.value = brain.id.uppercase()
                brain.reply(history).collect {
                    emitted = true
                    emit(it)
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (emitted || index == brains.lastIndex) throw e
                // Silent fallback: nothing was said yet, try the next brain.
            }
        }
        if (brains.isEmpty()) {
            _activeLabel.value = "NONE"
            emit("I have no brain available. Add a Groq key in settings, or download an offline model.")
        }
    }
}
