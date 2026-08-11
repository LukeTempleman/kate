package tech.gonxt.kate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.gonxt.kate.audio.DummyTts
import tech.gonxt.kate.brain.DummyBrain
import tech.gonxt.kate.core.ConversationEngine
import tech.gonxt.kate.settings.BrainMode
import tech.gonxt.kate.settings.KateSettings
import tech.gonxt.kate.settings.KateVoice
import tech.gonxt.kate.settings.SettingsRepository

class KateViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)

    val settings: StateFlow<KateSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, KateSettings())

    val engine = ConversationEngine(
        brain = DummyBrain(),
        tts = DummyTts(),
        scope = viewModelScope,
    )

    /** Which brain is actually behind the orb right now (M1.1: always dummy). */
    val activeBrainLabel = MutableStateFlow("DUMMY")

    val drivingMode = MutableStateFlow(false)

    private val demoUtterances = listOf(
        "Kate, are you there?",
        "What can you do so far?",
        "Tell me about your voice.",
        "Who are you?",
    )
    private var demoIndex = 0

    /** M1.1 TALK: simulates the wake→listen→transcribe flow until real STT lands. */
    fun onTalkPressed() {
        engine.simulateVoiceInput(demoUtterances[demoIndex % demoUtterances.size])
        demoIndex++
    }

    fun sendText(text: String) = engine.sendUserText(text)

    fun setDrivingMode(on: Boolean) { drivingMode.value = on }

    fun setVoice(v: KateVoice) = viewModelScope.launch { settingsRepo.setVoice(v) }
    fun setBrainMode(m: BrainMode) = viewModelScope.launch { settingsRepo.setBrainMode(m) }
    fun setWakeWord(on: Boolean) = viewModelScope.launch { settingsRepo.setWakeWord(on) }
    fun setLatencyReadout(on: Boolean) = viewModelScope.launch { settingsRepo.setLatencyReadout(on) }
    fun setDrivingModeAuto(on: Boolean) = viewModelScope.launch { settingsRepo.setDrivingModeAuto(on) }
    fun setGroqApiKey(k: String) = viewModelScope.launch { settingsRepo.setGroqApiKey(k) }
    fun setPicovoiceAccessKey(k: String) = viewModelScope.launch { settingsRepo.setPicovoiceAccessKey(k) }
}
