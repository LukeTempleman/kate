package tech.gonxt.kate

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import android.os.PowerManager
import tech.gonxt.kate.audio.TtsRouter
import tech.gonxt.kate.brain.BrainRouter
import tech.gonxt.kate.brain.GroqBrain
import tech.gonxt.kate.brain.OnDeviceBrain
import tech.gonxt.kate.core.ConversationEngine
import tech.gonxt.kate.models.Models
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
            devicePrimary = OnDeviceBrain(this, modelManager, Models.LLM_PRIMARY, "qwen3-8b"),
            deviceFallback = OnDeviceBrain(this, modelManager, Models.LLM_FALLBACK, "qwen3-1.7b"),
            mode = { settings.value.brainMode },
            constrained = ::isConstrained,
        )
    }

    val engine by lazy { ConversationEngine(brain = brainRouter, tts = ttsRouter, scope = appScope) }

    /** Battery saver or serious thermal throttle → prefer the small model. */
    private fun isConstrained(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        val hot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        return pm.isPowerSaveMode || hot
    }

    val voicePipeline by lazy { VoicePipeline(this, modelManager, engine, appScope) }
}
