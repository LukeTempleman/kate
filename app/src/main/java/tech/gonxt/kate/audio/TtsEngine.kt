package tech.gonxt.kate.audio

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin
import kotlin.random.Random

/**
 * TTS contract. Kokoro-82M via sherpa-onnx implements this in M1.2;
 * Piper en_GB is the low-battery/thermal fallback. Amplitude drives the orb.
 */
interface TtsEngine {
    val id: String

    /** Live output amplitude 0..1 while speaking; 0 when silent. */
    val amplitude: StateFlow<Float>

    /** Synthesize and play one sentence; suspends until playback of it completes. */
    suspend fun speak(sentence: String)

    /** Immediately stop playback (barge-in). */
    fun stop()
}

/**
 * M1.1 placeholder: no audio, but produces a speech-like amplitude envelope for
 * the orb's speaking waveform, paced at roughly human speech rate.
 */
class DummyTts : TtsEngine {

    override val id = "dummy"

    private val _amplitude = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amplitude

    @Volatile
    private var stopped = false

    override suspend fun speak(sentence: String) {
        stopped = false
        val words = sentence.split(" ").count { it.isNotBlank() }
        val durationMs = (words * 320L).coerceAtLeast(400L)
        val stepMs = 33L
        var t = 0L
        while (t < durationMs && !stopped) {
            val phase = t / 1000f
            val envelope = 0.55f + 0.45f * sin(phase * 9f).toFloat()
            _amplitude.value = (envelope * (0.7f + Random.nextFloat() * 0.3f)).coerceIn(0f, 1f)
            delay(stepMs)
            t += stepMs
        }
        _amplitude.value = 0f
    }

    override fun stop() {
        stopped = true
        _amplitude.value = 0f
    }
}
