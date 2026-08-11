package tech.gonxt.kate.audio

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * "Kate" wake word via sherpa-onnx KeywordSpotter (zipformer KWS, gigaspeech BPE).
 * OSS alternative to Porcupine allowed by spec §1.1 — no access key, fully offline.
 *
 * "KATE" BPE-encodes to `▁K ATE` with this model's bpe.model (verified against
 * tokens.txt: ▁K=164, ATE=155; no ▁KATE merge exists).
 */
class SherpaWake(
    modelDir: File,
    keywords: String = "▁K ATE @kate",
    threshold: Float = 0.25f,
) {
    private val spotter: KeywordSpotter
    private val stream: OnlineStream

    init {
        val d = modelDir.absolutePath
        spotter = KeywordSpotter(
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$d/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder = "$d/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                        joiner = "$d/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    ),
                    tokens = "$d/tokens.txt",
                    modelType = "zipformer2",
                    numThreads = 1,
                ),
                keywordsFile = "$d/keywords.txt",
                keywordsThreshold = threshold,
            ),
        )
        stream = spotter.createStream(keywords)
    }

    /** Feed one mic frame; true when the wake word just fired. */
    fun feed(samples: FloatArray, sampleRate: Int = 16_000): Boolean {
        stream.acceptWaveform(samples, sampleRate)
        var hit = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val r = spotter.getResult(stream)
            if (r.keyword.isNotEmpty()) {
                hit = true
                spotter.reset(stream)
            }
        }
        return hit
    }

    fun release() {
        stream.release()
        spotter.release()
    }
}
