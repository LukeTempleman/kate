package tech.gonxt.kate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.gonxt.kate.models.ModelSpec
import tech.gonxt.kate.service.KateVoiceService
import tech.gonxt.kate.settings.BrainMode
import tech.gonxt.kate.settings.KateSettings
import tech.gonxt.kate.settings.KateVoice
import tech.gonxt.kate.sync.SyncEngine

class KateViewModel(app: Application) : AndroidViewModel(app) {

    private val kate = app as KateApplication
    private val settingsRepo = kate.settingsRepository

    val settings: StateFlow<KateSettings> = kate.settings
    val modelManager = kate.modelManager
    val ttsRouter = kate.ttsRouter
    val engine = kate.engine
    val voicePipeline = kate.voicePipeline

    /** Which brain answered last (router updates it as turns route). */
    val activeBrainLabel: StateFlow<String> = kate.brainRouter.activeLabel

    val memoryCount: StateFlow<Int> = kate.memoryStore.memoryCount
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Dashboard flows (Iteration 4)
    val graphNodes = kate.memoryStore.graphNodes.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val graphEdges = kate.memoryStore.graphEdges.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val kateAnswers = kate.memoryStore.kateAnswers.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val skills = kate.skillManager.skills.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val skillRuns = kate.skillManager.runs.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    suspend fun nodeDetail(nodeId: Long): String? = kate.memoryStore.nodeDetail(nodeId)
    fun deleteNode(nodeId: Long) = viewModelScope.launch { kate.memoryStore.deleteNode(nodeId) }
    fun pinNode(nodeId: Long) = viewModelScope.launch { kate.memoryStore.pinNode(nodeId) }
    fun rateTurn(turnId: Long, rating: Int) = viewModelScope.launch { kate.memoryStore.rateTurn(turnId, rating) }

    val drivingMode = MutableStateFlow(false)

    private val demoUtterances = listOf(
        "Kate, are you there?",
        "What can you do so far?",
        "Tell me about your voice.",
        "Who are you?",
    )
    private var demoIndex = 0

    /** TALK: real capture when the ears exist, simulated flow otherwise. */
    fun onTalkPressed() {
        if (voicePipeline.earsReady() && voicePipeline.micPermitted()) {
            voicePipeline.tapToTalk()
        } else {
            engine.simulateVoiceInput(demoUtterances[demoIndex % demoUtterances.size])
            demoIndex++
        }
    }

    fun sendText(text: String) = engine.sendUserText(text)

    fun setDrivingMode(on: Boolean) { drivingMode.value = on }

    /** Called once mic + notification permissions are granted. */
    fun onPermissionsGranted() {
        if (settings.value.wakeWordEnabled && voicePipeline.earsReady()) {
            KateVoiceService.start(getApplication())
        }
    }

    fun setVoice(v: KateVoice) = viewModelScope.launch { settingsRepo.setVoice(v) }
    fun setBrainMode(m: BrainMode) = viewModelScope.launch { settingsRepo.setBrainMode(m) }

    fun setWakeWord(on: Boolean) = viewModelScope.launch {
        settingsRepo.setWakeWord(on)
        if (on && voicePipeline.earsReady() && voicePipeline.micPermitted()) {
            KateVoiceService.start(getApplication())
        } else if (!on) {
            KateVoiceService.stop(getApplication())
        }
    }

    fun setLatencyReadout(on: Boolean) = viewModelScope.launch { settingsRepo.setLatencyReadout(on) }
    fun setDrivingModeAuto(on: Boolean) = viewModelScope.launch { settingsRepo.setDrivingModeAuto(on) }
    fun setGroqApiKey(k: String) = viewModelScope.launch { settingsRepo.setGroqApiKey(k) }
    fun setPicovoiceAccessKey(k: String) = viewModelScope.launch { settingsRepo.setPicovoiceAccessKey(k) }

    fun downloadModel(spec: ModelSpec) = viewModelScope.launch { modelManager.download(spec) }
    fun deleteModel(spec: ModelSpec) = modelManager.delete(spec)
    fun speakDirect(text: String) = engine.speakDirect(text)

    fun setPortalUrl(url: String) = viewModelScope.launch { settingsRepo.setPortalUrl(url) }
    fun setPortalToken(t: String) = viewModelScope.launch { settingsRepo.setPortalToken(t) }
    fun syncNow() = SyncEngine.syncNow(getApplication())
}
