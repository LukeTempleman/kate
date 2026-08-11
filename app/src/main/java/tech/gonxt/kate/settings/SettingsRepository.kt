package tech.gonxt.kate.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class KateVoice(val label: String, val modelVoice: String) {
    EMMA("Emma", "bf_emma"),
    ISABELLA("Isabella", "bf_isabella"),
    PIPER("Piper (fallback)", "en_GB"),
}

enum class BrainMode(val label: String) {
    AUTO("Auto"),
    ONLINE("Online"),
    OFFLINE("Offline"),
}

data class KateSettings(
    val voice: KateVoice = KateVoice.EMMA,
    val brainMode: BrainMode = BrainMode.AUTO,
    val wakeWordEnabled: Boolean = true,
    val latencyReadout: Boolean = false,
    val drivingModeAuto: Boolean = true,
    val groqApiKey: String = "",
    val picovoiceAccessKey: String = "",
    val portalUrl: String = "https://kate-portal.luke1-temp16.workers.dev",
    val portalToken: String = "",
)

private val Context.dataStore by preferencesDataStore(name = "kate_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val VOICE = stringPreferencesKey("voice")
        val BRAIN_MODE = stringPreferencesKey("brain_mode")
        val WAKE_WORD = booleanPreferencesKey("wake_word")
        val LATENCY_READOUT = booleanPreferencesKey("latency_readout")
        val DRIVING_AUTO = booleanPreferencesKey("driving_auto")
        val GROQ_KEY = stringPreferencesKey("groq_api_key")
        val PICOVOICE_KEY = stringPreferencesKey("picovoice_access_key")
        val PORTAL_URL = stringPreferencesKey("portal_url")
        val PORTAL_TOKEN = stringPreferencesKey("portal_token")
        val PORTAL_SEQ = longPreferencesKey("portal_seq")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val settings: Flow<KateSettings> = context.dataStore.data.map { p ->
        KateSettings(
            voice = p[Keys.VOICE]?.let { runCatching { KateVoice.valueOf(it) }.getOrNull() } ?: KateVoice.EMMA,
            brainMode = p[Keys.BRAIN_MODE]?.let { runCatching { BrainMode.valueOf(it) }.getOrNull() } ?: BrainMode.AUTO,
            wakeWordEnabled = p[Keys.WAKE_WORD] ?: true,
            latencyReadout = p[Keys.LATENCY_READOUT] ?: false,
            drivingModeAuto = p[Keys.DRIVING_AUTO] ?: true,
            groqApiKey = p[Keys.GROQ_KEY] ?: "",
            picovoiceAccessKey = p[Keys.PICOVOICE_KEY] ?: "",
            portalUrl = p[Keys.PORTAL_URL] ?: "https://kate-portal.luke1-temp16.workers.dev",
            portalToken = p[Keys.PORTAL_TOKEN] ?: "",
        )
    }

    suspend fun setVoice(v: KateVoice) = context.dataStore.edit { it[Keys.VOICE] = v.name }
    suspend fun setBrainMode(m: BrainMode) = context.dataStore.edit { it[Keys.BRAIN_MODE] = m.name }
    suspend fun setWakeWord(on: Boolean) = context.dataStore.edit { it[Keys.WAKE_WORD] = on }
    suspend fun setLatencyReadout(on: Boolean) = context.dataStore.edit { it[Keys.LATENCY_READOUT] = on }
    suspend fun setDrivingModeAuto(on: Boolean) = context.dataStore.edit { it[Keys.DRIVING_AUTO] = on }
    suspend fun setGroqApiKey(key: String) = context.dataStore.edit { it[Keys.GROQ_KEY] = key }
    suspend fun setPicovoiceAccessKey(key: String) = context.dataStore.edit { it[Keys.PICOVOICE_KEY] = key }
    suspend fun setPortalUrl(url: String) = context.dataStore.edit { it[Keys.PORTAL_URL] = url }
    suspend fun setPortalToken(t: String) = context.dataStore.edit { it[Keys.PORTAL_TOKEN] = t }

    suspend fun portalSeq(): Long = context.dataStore.data.first()[Keys.PORTAL_SEQ] ?: 0L
    suspend fun setPortalSeq(seq: Long) = context.dataStore.edit { it[Keys.PORTAL_SEQ] = seq }

    /** Stable per-install id used in HLC timestamps and global entity ids. */
    suspend fun deviceId(): String {
        val existing = context.dataStore.data.first()[Keys.DEVICE_ID]
        if (existing != null) return existing
        val fresh = "ph" + java.util.UUID.randomUUID().toString().take(8)
        context.dataStore.edit { it[Keys.DEVICE_ID] = fresh }
        return fresh
    }
}
