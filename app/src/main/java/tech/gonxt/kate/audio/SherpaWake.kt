package tech.gonxt.kate.audio

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * "Moneypenny" wake word via sherpa-onnx KeywordSpotter (zipformer KWS, gigaspeech
 * BPE). OSS alternative to Porcupine allowed by spec §1.1 — no key, fully offline.
 *
 * Both plausible BPE segmentations of MONEYPENNY are registered (pieces verified
 * against tokens.txt: ▁MONEY=482, PE=158, P=26, EN=63, N=9, Y=17); "Kate" kept
 * as a short alias.
 */
class SherpaWake(
    modelDir: File,
    keywords: String = "▁MONEY PE N N Y @moneypenny\n▁MONEY P EN N Y @moneypenny2\n▁K ATE @kate",
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
