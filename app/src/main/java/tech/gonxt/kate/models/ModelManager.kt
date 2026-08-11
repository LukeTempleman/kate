package tech.gonxt.kate.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class ModelKind { TTS_KOKORO, TTS_PIPER, VAD, STT, KWS, LLM, EMBEDDING }

enum class PromptStyle { CHATML, PHI }

data class ModelSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val kind: ModelKind,
    val approxMB: Int,
    /** true = tar.bz2 archive with a top-level directory named [archiveRoot]. */
    val isArchive: Boolean = true,
    val archiveRoot: String = "",
    val promptStyle: PromptStyle = PromptStyle.CHATML,
)

object Models {
    val KOKORO = ModelSpec(
        id = "kokoro-int8-v1_0",
        displayName = "Her voice (Emma & Isabella)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        kind = ModelKind.TTS_KOKORO,
        approxMB = 132,
        archiveRoot = "kokoro-int8-multi-lang-v1_0",
    )

    val PIPER = ModelSpec(
        id = "piper-en_gb-jenny-int8",
        displayName = "Backup voice (battery saver)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-jenny_dioco-medium-int8.tar.bz2",
        kind = ModelKind.TTS_PIPER,
        approxMB = 21,
        archiveRoot = "vits-piper-en_GB-jenny_dioco-medium-int8",
    )

    val SILERO_VAD = ModelSpec(
        id = "silero-vad",
        displayName = "Speech detection",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        kind = ModelKind.VAD,
        approxMB = 1,
        isArchive = false,
    )

    val WHISPER_SMALL_EN = ModelSpec(
        id = "whisper-small-en",
        displayName = "Hearing (speech to text)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.en.tar.bz2",
        kind = ModelKind.STT,
        approxMB = 636,
        archiveRoot = "sherpa-onnx-whisper-small.en",
    )

    val KWS_ZIPFORMER = ModelSpec(
        id = "kws-zipformer-en",
        displayName = "Wake word (\u201cMoneypenny\u201d)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2",
        kind = ModelKind.KWS,
        approxMB = 16,
        archiveRoot = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile",
    )

    // Spec M1.4 names Llama 8B/3B, but Meta-licensed LiteRT builds are HF-gated and
    // .litertlm files fail in MediaPipe 0.10.35 ("sentencepiece tokenizer not found").
    // These are the strongest ungated .task builds that actually load.
    val LLM_PRIMARY = ModelSpec(
        id = "phi4-mini-q8",
        displayName = "Offline brain (smart, 3.9GB)",
        url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task",
        kind = ModelKind.LLM,
        approxMB = 3910,
        isArchive = false,
        promptStyle = PromptStyle.PHI,
    )

    val LLM_FALLBACK = ModelSpec(
        id = "qwen25-0_5b-q8",
        displayName = "Offline brain (small, 550MB)",
        url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        kind = ModelKind.LLM,
        approxMB = 550,
        isArchive = false,
        promptStyle = PromptStyle.CHATML,
    )

    /** Model ids from earlier builds whose files no longer load — reclaim the gigabytes. */
    val LEGACY_IDS = listOf("qwen3-8b-int4", "qwen3-1_7b-int4")

    val EMBEDDER = ModelSpec(
        id = "use-embedder",
        displayName = "Memory upgrade (smarter recall)",
        url = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite",
        kind = ModelKind.EMBEDDING,
        approxMB = 100,
        isArchive = false,
    )

    val ALL = listOf(KOKORO, PIPER, SILERO_VAD, WHISPER_SMALL_EN, KWS_ZIPFORMER, LLM_PRIMARY, LLM_FALLBACK, EMBEDDER)
}

sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus
    data class Downloading(val progress: Float) : ModelStatus
    data object Extracting : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

/**
 * Big models never ship in the APK — they download once over HTTPS into
 * app-specific storage and everything runs offline from then on (spec §1.5).
 */
class ModelManager(context: Context) {

    private val root: File = (context.getExternalFilesDir(null) ?: context.filesDir)
        .resolve("models").apply { mkdirs() }

    init {
        for (id in Models.LEGACY_IDS) {
            root.listFiles { f -> f.name.startsWith(id) || f.name.contains("litertlm") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    private val statuses = Models.ALL.associate { spec ->
        spec.id to MutableStateFlow(if (isReady(spec)) ModelStatus.Ready else ModelStatus.NotDownloaded as ModelStatus)
    }

    fun status(spec: ModelSpec): StateFlow<ModelStatus> = statuses.getValue(spec.id)

    /** Directory (archives) or file (plain models) to feed sherpa-onnx configs. */
    fun path(spec: ModelSpec): File =
        if (spec.isArchive) root.resolve(spec.archiveRoot) else root.resolve(spec.url.substringAfterLast('/'))

    fun isReady(spec: ModelSpec): Boolean = doneMarker(spec).exists()

    suspend fun download(spec: ModelSpec) = withContext(Dispatchers.IO) {
        val status = statuses.getValue(spec.id)
        if (isReady(spec)) { status.value = ModelStatus.Ready; return@withContext }
        try {
            status.value = ModelStatus.Downloading(0f)
            val target = if (spec.isArchive) root.resolve("${spec.id}.tar.bz2") else path(spec)
            fetch(spec.url, target) { status.value = ModelStatus.Downloading(it) }
            if (spec.isArchive) {
                status.value = ModelStatus.Extracting
                extractTarBz2(target, root)
                target.delete()
            }
            doneMarker(spec).writeText(spec.url)
            status.value = ModelStatus.Ready
        } catch (e: Exception) {
            status.value = ModelStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun delete(spec: ModelSpec) {
        path(spec).deleteRecursively()
        doneMarker(spec).delete()
        statuses.getValue(spec.id).value = ModelStatus.NotDownloaded
    }

    private fun doneMarker(spec: ModelSpec) = root.resolve("${spec.id}.done")

    private fun fetch(url: String, target: File, onProgress: (Float) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            if (!tmp.renameTo(target)) error("rename failed for ${target.name}")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        BufferedInputStream(archive.inputStream()).use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val out = destDir.resolve(entry.name).canonicalFile
                        require(out.path.startsWith(destDir.canonicalPath)) { "bad tar entry ${entry.name}" }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
