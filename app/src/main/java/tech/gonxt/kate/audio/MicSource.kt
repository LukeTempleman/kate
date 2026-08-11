package tech.gonxt.kate.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 16kHz mono float mic frames (512 samples ≈ 32ms — Silero VAD's window).
 * VOICE_RECOGNITION source plus AEC so Kate doesn't hear herself (spec M1.5).
 */
class MicSource(private val context: Context) {

    val sampleRate = 16_000
    val frameSize = 512

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked via hasPermission before collection
    fun frames(): Flow<FloatArray> = flow {
        check(hasPermission()) { "RECORD_AUDIO not granted" }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuf, frameSize * 8 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        } else null
        val ns = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        } else null
        try {
            record.startRecording()
            val buf = FloatArray(frameSize)
            while (true) {
                val n = record.read(buf, 0, frameSize, AudioRecord.READ_BLOCKING)
                if (n > 0) emit(buf.copyOf(n))
            }
        } finally {
            aec?.release()
            ns?.release()
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
