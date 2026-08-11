package tech.gonxt.kate.capability

// Extension interface from spec §1.6 — ships in the Iteration 1 scaffold so later
// capability modules (calendar, music, navigation…) slot in without core changes.

data class IntentPattern(val phrases: List<String>)

data class ParsedIntent(
    val capability: String,
    val utterance: String,
    val slots: Map<String, String> = emptyMap(),
)

sealed interface CapabilityResult {
    data class Spoken(val text: String) : CapabilityResult
    data class Deferred(val taskId: String, val acknowledgement: String) : CapabilityResult
    data class Failed(val reason: String) : CapabilityResult
}

interface KateCapability {
    val name: String
    val intents: List<IntentPattern>
    fun handle(intent: ParsedIntent): CapabilityResult
}
