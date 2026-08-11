package tech.gonxt.kate

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tech.gonxt.kate.audio.TtsRouter
import tech.gonxt.kate.brain.DummyBrain
import tech.gonxt.kate.core.ConversationEngine
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

    val engine by lazy { ConversationEngine(brain = DummyBrain(), tts = ttsRouter, scope = appScope) }

    val voicePipeline by lazy { VoicePipeline(this, modelManager, engine, appScope) }
}
