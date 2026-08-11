package tech.gonxt.kate.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.gonxt.kate.audio.TtsEngine
import tech.gonxt.kate.brain.Brain

/**
 * Drives one Kate conversation: orb state, transcript, streaming replies, speech.
 * Pure Kotlin (no Android deps) so the whole loop is unit-testable.
 */
class ConversationEngine(
    private val brain: Brain,
    private val tts: TtsEngine,
    private val scope: CoroutineScope,
    private val latency: LatencyTracker = LatencyTracker(),
) {
    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _partialUserText = MutableStateFlow("")
    val partialUserText: StateFlow<String> = _partialUserText

    val speakingAmplitude: StateFlow<Float> get() = tts.amplitude
    val lastTurnLatency: StateFlow<LatencyTracker.Turn> get() = latency.lastCompleted

    private var nextId = 1L
    private var turnJob: Job? = null

    fun sendUserText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        bargeIn()
        turnJob = scope.launch { runTurn(trimmed) }
    }

    /**
     * M1.1 demo listening: animates live partial text like streaming STT will in
     * M1.3, then submits the utterance to the brain.
     */
    fun simulateVoiceInput(utterance: String) {
        bargeIn()
        turnJob = scope.launch {
            _orbState.value = OrbState.LISTENING
            latency.begin("wake")
            val words = utterance.split(" ")
            for (i in words.indices) {
                _partialUserText.value = words.take(i + 1).joinToString(" ")
                delay(140)
            }
            latency.mark("stt_final")
            _partialUserText.value = ""
            runTurn(utterance, alreadyMarked = true)
        }
    }

    /** Cuts Kate off instantly — wake word during playback, or a new send. */
    fun bargeIn() {
        turnJob?.cancel()
        tts.stop()
        _partialUserText.value = ""
        val msgs = _messages.value
        if (msgs.isNotEmpty() && msgs.last().streaming) {
            _messages.value = msgs.dropLast(1) + msgs.last().copy(streaming = false)
        }
        _orbState.value = OrbState.IDLE
    }

    private suspend fun runTurn(userText: String, alreadyMarked: Boolean = false) {
        if (!alreadyMarked) latency.begin("submit")
        _messages.value += ChatMessage(nextId++, Role.USER, userText)
        _orbState.value = OrbState.THINKING

        val kateId = nextId++
        _messages.value += ChatMessage(kateId, Role.KATE, "", streaming = true)

        val chunker = SentenceChunker()
        val pendingSentences = ArrayDeque<String>()
        var firstToken = true
        var speaking = false

        suspend fun speakQueued() {
            while (pendingSentences.isNotEmpty()) {
                val s = pendingSentences.removeFirst()
                if (!speaking) {
                    speaking = true
                    latency.mark("first_speech")
                    _orbState.value = OrbState.SPEAKING
                }
                tts.speak(s)
            }
        }

        try {
            brain.reply(_messages.value.filter { !it.streaming }).collect { token ->
                if (firstToken) {
                    firstToken = false
                    latency.mark("first_token")
                }
                updateStreaming(kateId) { it + token }
                pendingSentences += chunker.feed(token)
                // M1.1: dummy TTS is fast enough to speak inline between tokens.
                // M1.2 moves synthesis to a parallel queue so generation never waits.
                speakQueued()
            }
            chunker.flush()?.let { pendingSentences += it }
            speakQueued()
            updateStreaming(kateId, markDone = true) { it.trimEnd() }
            latency.mark("done")
            latency.complete()
        } finally {
            tts.stop()
            _orbState.value = OrbState.IDLE
        }
    }

    private fun updateStreaming(id: Long, markDone: Boolean = false, transform: (String) -> String) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(text = transform(it.text), streaming = !markDone && it.streaming) else it
        }
    }
}
