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

enum class ModelKind { TTS_KOKORO, TTS_PIPER, VAD, STT, KWS }

data class ModelSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val kind: ModelKind,
    val approxMB: Int,
    /** true = tar.bz2 archive with a top-level directory named [archiveRoot]. */
    val isArchive: Boolean = true,
    val archiveRoot: String = "",
)

object Models {
    val KOKORO = ModelSpec(
        id = "kokoro-int8-v1_0",
        displayName = "Kokoro voice (Emma & Isabella)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        kind = ModelKind.TTS_KOKORO,
        approxMB = 132,
        archiveRoot = "kokoro-int8-multi-lang-v1_0",
    )

    val PIPER = ModelSpec(
        id = "piper-en_gb-jenny-int8",
        displayName = "Piper fallback voice (en_GB)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-jenny_dioco-medium-int8.tar.bz2",
        kind = ModelKind.TTS_PIPER,
        approxMB = 21,
        archiveRoot = "vits-piper-en_GB-jenny_dioco-medium-int8",
    )

    val SILERO_VAD = ModelSpec(
        id = "silero-vad",
        displayName = "Silero VAD (endpointing)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        kind = ModelKind.VAD,
        approxMB = 1,
        isArchive = false,
    )

    val WHISPER_SMALL_EN = ModelSpec(
        id = "whisper-small-en",
        displayName = "Whisper small.en (ears)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.en.tar.bz2",
        kind = ModelKind.STT,
        approxMB = 636,
        archiveRoot = "sherpa-onnx-whisper-small.en",
    )

    val KWS_ZIPFORMER = ModelSpec(
        id = "kws-zipformer-en",
        displayName = "Wake word (\"Kate\")",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2",
        kind = ModelKind.KWS,
        approxMB = 16,
        archiveRoot = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile",
    )

    val ALL = listOf(KOKORO, PIPER, SILERO_VAD, WHISPER_SMALL_EN, KWS_ZIPFORMER)
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
