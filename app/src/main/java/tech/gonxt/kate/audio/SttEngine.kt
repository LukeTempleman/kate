package tech.gonxt.kate.audio

import kotlinx.coroutines.flow.Flow

/** One utterance's transcription events, streamed while the user speaks. */
sealed interface SttEvent {
    data class Partial(val text: String) : SttEvent
    data class Final(val text: String) : SttEvent
}

/**
 * STT contract. whisper.cpp small.en (via sherpa-onnx) implements this in M1.3,
 * with Silero VAD endpointing deciding when the utterance is over.
 */
interface SttEngine {
    val id: String

    /** Opens the mic and streams partials until VAD detects speech end, then emits Final. */
    fun listen(): Flow<SttEvent>

    fun cancel()
}
