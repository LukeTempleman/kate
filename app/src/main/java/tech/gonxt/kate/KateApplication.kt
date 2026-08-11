package tech.gonxt.kate

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import android.os.PowerManager
import kotlinx.coroutines.launch
import tech.gonxt.kate.audio.TtsRouter
import tech.gonxt.kate.brain.BrainRouter
import tech.gonxt.kate.brain.GroqBrain
import tech.gonxt.kate.brain.OnDeviceBrain
import tech.gonxt.kate.core.ConversationEngine
import tech.gonxt.kate.memory.HashingEmbedder
import tech.gonxt.kate.memory.MediaPipeEmbedder
import tech.gonxt.kate.memory.MemoryRecall
import tech.gonxt.kate.memory.MemoryStore
import tech.gonxt.kate.memory.db.KateDb
import tech.gonxt.kate.models.ModelStatus
import tech.gonxt.kate.models.Models
import tech.gonxt.kate.skills.SkillManager
import tech.gonxt.kate.sync.Hlc
import tech.gonxt.kate.sync.SyncEngine
import tech.gonxt.kate.sync.SyncRecorder
import tech.gonxt.kate.core.VoicePipeline
import tech.gonxt.kate.models.ModelManager
import tech.gonxt.kate.settings.KateSettings
import tech.gonxt.kate.settings.SettingsRepository

/**
 * App-scoped graph: the voice pipeline must outlive any single Activity
 * (it runs inside the wake-word foreground service), so the engine and
 * audio stack live here rather than in a ViewModel.
 */
class KateApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository by lazy { SettingsRepository(this) }

    val settings: StateFlow<KateSettings> by lazy {
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, KateSettings())
    }

    val modelManager by lazy { ModelManager(this) }

    val ttsRouter by lazy { TtsRouter(this, modelManager, settings, appScope) }

    val brainRouter by lazy {
        BrainRouter(
            online = GroqBrain(this) { settings.value.groqApiKey },
            devicePrimary = OnDeviceBrain(this, modelManager, Models.LLM_PRIMARY, "phi4-mini"),
            deviceFallback = OnDeviceBrain(this, modelManager, Models.LLM_FALLBACK, "qwen2.5"),
            mode = { settings.value.brainMode },
            constrained = ::isConstrained,
        )
    }

    val db by lazy { KateDb.build(this) }

    // Iteration 5: sync plumbing. Plain file, not DataStore — this is read during
    // startup on the main thread and must never block behind DataStore's queue.
    val deviceId: String by lazy {
        val f = java.io.File(filesDir, "device_id.txt")
        if (f.exists()) f.readText().trim().ifEmpty { null } ?: newDeviceId(f) else newDeviceId(f)
    }

    private fun newDeviceId(f: java.io.File): String =
        ("ph" + java.util.UUID.randomUUID().toString().take(8)).also { f.writeText(it) }
    val hlc by lazy { Hlc(deviceId) }
    val syncRecorder: SyncRecorder? by lazy { SyncRecorder(db, hlc, deviceId) }
    val syncEngine by lazy { SyncEngine(this, this) }

    val memoryStore by lazy {
        MemoryStore(db, initialEmbedder(), appScope, recorder = syncRecorder).also { store ->
            // Upgrade lexical→semantic recall live once the embedder model lands.
            appScope.launch {
                modelManager.status(Models.EMBEDDER).collect { status ->
                    if (status == ModelStatus.Ready && store.embedder.id != "mp-use-v1") {
                        runCatching {
                            store.embedder = MediaPipeEmbedder(this@KateApplication, modelManager.path(Models.EMBEDDER))
                        }
                    }
                }
            }
        }
    }

    private fun initialEmbedder() =
        if (modelManager.isReady(Models.EMBEDDER)) {
            runCatching {
                MediaPipeEmbedder(this, modelManager.path(Models.EMBEDDER)) as tech.gonxt.kate.memory.Embedder
            }.getOrDefault(HashingEmbedder())
        } else HashingEmbedder()

    val memoryRecall by lazy { MemoryRecall(memoryStore) }

    val skillManager by lazy {
        SkillManager(this, db, brainRouter, appScope) { engine }.also { it.recorder = syncRecorder }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        SyncEngine.schedule(this)
    }

    /** Remote-debuggable crashes: stack trace lands in a file Settings can show. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile().writeText(
                    "${java.util.Date()}\nthread: ${thread.name}\n" +
                        android.util.Log.getStackTraceString(e),
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun crashFile(): java.io.File = java.io.File(filesDir, "last_crash.txt")

    val engine: ConversationEngine by lazy {
        ConversationEngine(
            brain = brainRouter,
            tts = ttsRouter,
            scope = appScope,
            intercept = { text -> skillManager.intercept(text) ?: memoryRecall.intercept(text) },
            onTurn = { role, text, model, latencyMs -> memoryStore.onTurn(role, text, model, latencyMs) },
            modelLabel = { brainRouter.activeLabel.value.lowercase() },
        )
    }

    /** Battery saver or serious thermal throttle → prefer the small model. */
    private fun isConstrained(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        val hot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        return pm.isPowerSaveMode || hot
    }

    val voicePipeline by lazy { VoicePipeline(this, modelManager, engine, appScope) }
}
