package tech.gonxt.kate.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.gonxt.kate.audio.MicSource
import tech.gonxt.kate.audio.SherpaStt
import tech.gonxt.kate.audio.SherpaWake
import tech.gonxt.kate.models.ModelManager
import tech.gonxt.kate.models.Models

/**
 * The whole voice loop (spec M1.5 wiring, M1.3 components):
 * mic → wake word ("Kate") → VAD + whisper partials → engine turn.
 * Runs inside the wake-word foreground service; also drives tap-to-talk.
 * Barge-in: the wake word firing while Kate speaks cuts her off instantly.
 */
class VoicePipeline(
    context: Context,
    private val modelManager: ModelManager,
    private val engine: ConversationEngine,
    private val scope: CoroutineScope,
) {
    enum class Mode { OFF, WAKE, CAPTURE }

    private val mic = MicSource(context)

    private val _mode = MutableStateFlow(Mode.OFF)
    val mode: StateFlow<Mode> = _mode

    private val _status = MutableStateFlow("ears off")
    val status: StateFlow<String> = _status

    private var loopJob: Job? = null
    private var wakeEnabled = true

    fun earsReady(): Boolean =
        modelManager.isReady(Models.WHISPER_SMALL_EN) &&
            modelManager.isReady(Models.SILERO_VAD) &&
            modelManager.isReady(Models.KWS_ZIPFORMER)

    fun micPermitted(): Boolean = mic.hasPermission()

    /** Start the always-on loop (foreground service calls this). */
    fun start(wake: Boolean) {
        wakeEnabled = wake
        if (loopJob?.isActive == true) return
        if (!earsReady() || !micPermitted()) {
            _status.value = if (!micPermitted()) "mic permission needed" else "ear models not downloaded"
            return
        }
        loopJob = scope.launch(Dispatchers.Default) { runLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _mode.value = Mode.OFF
        _status.value = "ears off"
    }

    /** TALK button: skip the wake word and capture immediately. */
    fun tapToTalk() {
        if (loopJob?.isActive == true) {
            engine.bargeIn()
            engine.beginListening()
            _mode.value = Mode.CAPTURE
        } else {
            start(wake = wakeEnabled)
            scope.launch {
                if (loopJob?.isActive == true) {
                    engine.beginListening()
                    _mode.value = Mode.CAPTURE
                }
            }
        }
    }

    private suspend fun runLoop() {
        _status.value = "loading ear models…"
        val (wake, stt) = try {
            withContext(Dispatchers.IO) {
                SherpaWake(modelManager.path(Models.KWS_ZIPFORMER)) to
                    SherpaStt(
                        whisperDir = modelManager.path(Models.WHISPER_SMALL_EN),
                        vadModel = modelManager.path(Models.SILERO_VAD),
                    )
            }
        } catch (e: Exception) {
            android.util.Log.e("KatePipeline", "ear model load failed", e)
            _status.value = "ears failed: ${e.message?.take(60)}"
            _mode.value = Mode.OFF
            return
        }
        _mode.value = Mode.WAKE
        _status.value = "say “Kate”"
        try {
            mic.frames().collect { frame ->
                when (_mode.value) {
                    Mode.WAKE -> {
                        if (wakeEnabled && wake.feed(frame)) {
                            // Barge-in: cut Kate off the instant her name fires.
                            engine.bargeIn()
                            engine.beginListening()
                            stt.resetSession()
                            _mode.value = Mode.CAPTURE
                            _status.value = "listening"
                        }
                    }
                    Mode.CAPTURE -> {
                        for (event in stt.feed(frame)) {
                            when (event) {
                                SherpaStt.Event.SpeechStart -> _status.value = "hearing you"
                                is SherpaStt.Event.Partial -> engine.updatePartial(event.text)
                                is SherpaStt.Event.Final -> {
                                    _mode.value = Mode.WAKE
                                    _status.value = "say “Kate”"
                                    engine.submitUtterance(event.text)
                                }
                            }
                        }
                    }
                    Mode.OFF -> {}
                }
            }
        } finally {
            wake.release()
            stt.release()
            _mode.value = Mode.OFF
        }
    }
}
