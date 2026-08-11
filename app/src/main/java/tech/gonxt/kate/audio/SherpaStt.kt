package tech.gonxt.kate.audio

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Ears (spec M1.3): whisper small.en for transcription, Silero VAD for
 * endpointing. Whisper isn't a streaming model, so "streaming partials" are
 * periodic decodes of the growing utterance buffer; the final decode runs the
 * instant VAD closes the segment.
 */
class SherpaStt(whisperDir: File, vadModel: File) {

    private fun find(dir: File, suffix: String): String =
        dir.listFiles { f -> f.name.endsWith(suffix) }?.firstOrNull()?.absolutePath
            ?: error("missing *$suffix in ${dir.name}")

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = find(whisperDir, "encoder.int8.onnx"),
                    decoder = find(whisperDir, "decoder.int8.onnx"),
                    language = "en",
                ),
                tokens = find(whisperDir, "tokens.txt"),
                modelType = "whisper",
                numThreads = 4,
            ),
        ),
    )

    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModel.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 14f,
            ),
            sampleRate = 16_000,
        ),
    )

    sealed interface Event {
        data object SpeechStart : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
    }

    private val utterance = ArrayDeque<Float>()
    private var speaking = false
    private var samplesSincePartial = 0

    /** ~1.5s between partial decodes — whisper small.en is heavy. */
    private val partialIntervalSamples = 24_000

    /**
     * Feed one 512-sample mic frame; returns events as they occur.
     * A [Event.Final] resets the session for the next utterance.
     */
    fun feed(frame: FloatArray): List<Event> {
        val events = mutableListOf<Event>()
        vad.acceptWaveform(frame)

        if (vad.isSpeechDetected()) {
            if (!speaking) {
                speaking = true
                events += Event.SpeechStart
            }
            for (s in frame) utterance.add(s)
            samplesSincePartial += frame.size
            if (samplesSincePartial >= partialIntervalSamples && utterance.size <= 16_000 * 12) {
                samplesSincePartial = 0
                val text = decode(utterance.toFloatArray())
                if (text.isNotBlank()) events += Event.Partial(text)
            }
        }

        // VAD queues a completed segment once trailing silence confirms speech end.
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val text = decode(segment.samples)
            events += Event.Final(text)
            resetSession()
        }
        return events
    }

    /** Force-close the current utterance (tap-to-stop / timeout). */
    fun finishNow(): Event.Final {
        vad.flush()
        var text: String? = null
        while (!vad.empty()) {
            text = decode(vad.front().samples)
            vad.pop()
        }
        if (text == null && utterance.isNotEmpty()) {
            text = decode(utterance.toFloatArray())
        }
        resetSession()
        return Event.Final(text.orEmpty())
    }

    fun resetSession() {
        speaking = false
        samplesSincePartial = 0
        utterance.clear()
        vad.clear()
    }

    private fun decode(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, 16_000)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        vad.release()
        recognizer.release()
    }
}
