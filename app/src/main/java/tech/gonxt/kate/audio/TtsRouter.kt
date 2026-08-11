package tech.gonxt.kate.audio

import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.gonxt.kate.models.ModelManager
import tech.gonxt.kate.models.Models
import tech.gonxt.kate.settings.KateSettings
import tech.gonxt.kate.settings.KateVoice

enum class TtsChoice { KOKORO, PIPER, DUMMY }

/**
 * Voice routing (spec M1.2): Kokoro is the flagship; Piper en_GB takes over on
 * low battery (thermal joins in M1.5) or when Kokoro isn't downloaded; the
 * silent dummy keeps the app alive before any model exists. Never hard-fails.
 */
fun chooseTts(
    voice: KateVoice,
    kokoroReady: Boolean,
    piperReady: Boolean,
    lowBattery: Boolean,
): TtsChoice = when {
    voice == KateVoice.PIPER && piperReady -> TtsChoice.PIPER
    kokoroReady && !lowBattery -> TtsChoice.KOKORO
    piperReady -> TtsChoice.PIPER
    kokoroReady -> TtsChoice.KOKORO
    else -> TtsChoice.DUMMY
}

class TtsRouter(
    private val context: Context,
    private val modelManager: ModelManager,
    private val settings: StateFlow<KateSettings>,
    private val scope: CoroutineScope,
) : TtsEngine {

    override val id = "router"

    private val _amplitude = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amplitude

    val activeLabel = MutableStateFlow("NONE")

    private val player = AudioPlayer()
    private val dummy = DummyTts()
    private var kokoro: SherpaTts? = null
    private var piper: SherpaTts? = null
    private val loadMutex = Mutex()

    @Volatile
    private var active: TtsEngine = dummy
    private var ampJob: Job? = null

    override suspend fun speak(sentence: String) {
        val engine = resolve()
        if (engine !== active) {
            active = engine
            activeLabel.value = engine.id.uppercase()
            ampJob?.cancel()
            ampJob = scope.launch { engine.amplitude.collect { _amplitude.value = it } }
        }
        engine.speak(sentence)
    }

    override fun stop() {
        active.stop()
    }

    private suspend fun resolve(): TtsEngine {
        val s = settings.value
        val choice = chooseTts(
            voice = s.voice,
            kokoroReady = modelManager.isReady(Models.KOKORO),
            piperReady = modelManager.isReady(Models.PIPER),
            lowBattery = isLowBattery(),
        )
        return loadMutex.withLock {
            when (choice) {
                TtsChoice.KOKORO -> withContext(Dispatchers.IO) {
                    val sid = if (s.voice == KateVoice.ISABELLA) KokoroSpeakers.BF_ISABELLA else KokoroSpeakers.BF_EMMA
                    (kokoro ?: SherpaTts.kokoro(modelManager.path(Models.KOKORO), sid, player).also { kokoro = it })
                        .also { it.speakerId = sid }
                }
                TtsChoice.PIPER -> withContext(Dispatchers.IO) {
                    piper ?: SherpaTts.piper(modelManager.path(Models.PIPER), player).also { piper = it }
                }
                TtsChoice.DUMMY -> dummy
            }
        }
    }

    private fun isLowBattery(): Boolean {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return false
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return pct in 1..19 && !bm.isCharging
    }
}
