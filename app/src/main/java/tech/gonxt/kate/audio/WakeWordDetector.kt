package tech.gonxt.kate.audio

import kotlinx.coroutines.flow.Flow

/**
 * Wake word contract. Porcupine custom "Kate" (or sherpa-onnx KWS as the OSS
 * alternative) implements this in M1.3, hosted in a foreground service.
 */
interface WakeWordDetector {
    val id: String

    /** Emits Unit each time the wake word fires. Collection holds the mic open. */
    fun detections(): Flow<Unit>
}
