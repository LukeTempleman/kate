package tech.gonxt.kate.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    /** Real STT hooks (M1.3): the voice pipeline drives these three. */
    fun beginListening() {
        _partialUserText.value = ""
        _orbState.value = OrbState.LISTENING
        latency.begin("wake")
    }

    fun updatePartial(text: String) {
        _partialUserText.value = text
    }

    fun submitUtterance(text: String) {
        _partialUserText.value = ""
        if (text.isBlank()) {
            _orbState.value = OrbState.IDLE
            return
        }
        latency.mark("stt_final")
        turnJob = scope.launch { runTurn(text, alreadyMarked = true) }
    }

    /** Voice lab: speak arbitrary text through the current TTS, no brain involved. */
    fun speakDirect(text: String) {
        if (text.isBlank()) return
        bargeIn()
        turnJob = scope.launch {
            _orbState.value = OrbState.SPEAKING
            try {
                val chunker = SentenceChunker()
                val sentences = chunker.feed(text) + listOfNotNull(chunker.flush())
                for (s in sentences) tts.speak(s)
            } finally {
                tts.stop()
                _orbState.value = OrbState.IDLE
            }
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
        var firstToken = true

        try {
            coroutineScope {
                // Kate speaks sentence #1 while sentence #2 is still generating:
                // synthesis consumes this queue in parallel with token collection.
                val sentences = Channel<String>(Channel.UNLIMITED)
                val speaker = launch {
                    var first = true
                    for (s in sentences) {
                        if (first) {
                            first = false
                            latency.mark("first_speech")
                            _orbState.value = OrbState.SPEAKING
                        }
                        tts.speak(s)
                    }
                }

                brain.reply(_messages.value.filter { !it.streaming }).collect { token ->
                    if (firstToken) {
                        firstToken = false
                        latency.mark("first_token")
                    }
                    updateStreaming(kateId) { it + token }
                    chunker.feed(token).forEach { sentences.send(it) }
                }
                chunker.flush()?.let { sentences.send(it) }
                updateStreaming(kateId, markDone = true) { it.trimEnd() }
                latency.mark("gen_done")
                sentences.close()
                speaker.join()
                latency.mark("speech_done")
                latency.complete()
            }
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
