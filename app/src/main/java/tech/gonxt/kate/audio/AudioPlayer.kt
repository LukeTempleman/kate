package tech.gonxt.kate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Streams float PCM from TTS straight to the speaker and publishes a live RMS
 * amplitude for the orb's speaking waveform.
 */
class AudioPlayer {

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    private var track: AudioTrack? = null
    private var sampleRate = 0

    @Volatile
    var stopped = false
        private set

    @Synchronized
    fun start(rate: Int) {
        stopped = false
        if (track != null && sampleRate == rate) {
            track?.play()
            return
        }
        release()
        sampleRate = rate
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    /** Blocking write; call from Dispatchers.IO. Returns false if stopped mid-write. */
    fun write(samples: FloatArray): Boolean {
        val t = track ?: return false
        var offset = 0
        while (offset < samples.size && !stopped) {
            val n = t.write(samples, offset, min(4096, samples.size - offset), AudioTrack.WRITE_BLOCKING)
            if (n < 0) return false
            _amplitude.value = rms(samples, offset, n)
            offset += n
        }
        return !stopped
    }

    /** Lets the final buffered audio play out. */
    fun drain() {
        val t = track ?: return
        if (!stopped) {
            // Rough wait: buffer length at most minBuf*4; poll playback head instead of guessing.
            try {
                t.stop() // plays out remaining buffered data in STREAM mode
            } catch (_: IllegalStateException) {
            }
        }
        _amplitude.value = 0f
    }

    @Synchronized
    fun stopNow() {
        stopped = true
        try {
            track?.pause()
            track?.flush()
        } catch (_: IllegalStateException) {
        }
        _amplitude.value = 0f
    }

    @Synchronized
    fun release() {
        stopped = true
        track?.release()
        track = null
        _amplitude.value = 0f
    }

    private fun rms(buf: FloatArray, offset: Int, len: Int): Float {
        if (len == 0) return 0f
        var sum = 0.0
        for (i in offset until offset + len) sum += buf[i] * buf[i]
        val v = sqrt(sum / len).toFloat()
        return (v * 4f).coerceIn(0f, 1f)
    }
}
