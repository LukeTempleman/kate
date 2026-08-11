package tech.gonxt.kate.audio

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kokoro v1.0 speaker ids (order from the sherpa-onnx kokoro docs table).
 * bf_emma is Kate's default voice (spec M1.2).
 */
object KokoroSpeakers {
    const val BF_EMMA = 21
    const val BF_ISABELLA = 22
}

/**
 * Real TTS over sherpa-onnx OfflineTts. One instance per loaded model;
 * synthesis streams chunks into [AudioPlayer] as they are generated, so
 * playback starts well before the sentence is fully synthesized.
 */
class SherpaTts private constructor(
    override val id: String,
    private val tts: OfflineTts,
    private val player: AudioPlayer,
    @Volatile var speakerId: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TtsEngine {

    override val amplitude: StateFlow<Float> = player.amplitude

    @Volatile
    private var cancelled = false

    override suspend fun speak(sentence: String) = withContext(dispatcher) {
        if (sentence.isBlank()) return@withContext
        cancelled = false
        player.start(tts.sampleRate())
        tts.generateWithCallback(text = sentence, sid = speakerId, speed = 1.0f) { samples ->
            val keepGoing = !cancelled && player.write(samples)
            if (keepGoing) 1 else 0
        }
        if (!cancelled) player.drain()
    }

    override fun stop() {
        cancelled = true
        player.stopNow()
    }

    fun release() {
        tts.release()
        player.release()
    }

    companion object {
        /**
         * kokoro-int8-multi-lang-v1_0 layout: model.int8.onnx, voices.bin, tokens.txt,
         * espeak-ng-data/, lexicon-gb-en.txt + lexicon-us-en.txt (comma-joined per sherpa docs).
         */
        fun kokoro(modelDir: File, speakerId: Int, player: AudioPlayer = AudioPlayer()): SherpaTts {
            val d = modelDir.absolutePath
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$d/model.int8.onnx",
                        voices = "$d/voices.bin",
                        tokens = "$d/tokens.txt",
                        dataDir = "$d/espeak-ng-data",
                        lexicon = "$d/lexicon-gb-en.txt,$d/lexicon-us-en.txt",
                    ),
                    numThreads = 4,
                    provider = "cpu",
                ),
            )
            return SherpaTts("kokoro", OfflineTts(config = config), player, speakerId)
        }

        /** Piper VITS model dir: single *.onnx + tokens.txt + espeak-ng-data/. */
        fun piper(modelDir: File, player: AudioPlayer = AudioPlayer()): SherpaTts {
            val onnx = modelDir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
                ?: error("no .onnx in ${modelDir.name}")
            val d = modelDir.absolutePath
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = onnx.absolutePath,
                        tokens = "$d/tokens.txt",
                        dataDir = "$d/espeak-ng-data",
                    ),
                    numThreads = 2,
                    provider = "cpu",
                ),
            )
            return SherpaTts("piper", OfflineTts(config = config), player, speakerId = 0)
        }
    }
}
