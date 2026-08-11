package tech.gonxt.kate.skills

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Skill JSON per spec §1.3 — created by voice, versioned, runs logged.

@Serializable
data class SkillInput(
    val name: String,
    val type: String = "voice_string",
)

@Serializable
data class SkillStep(
    val type: String, // llm | research | save_artifact
    val prompt: String? = null,
    val query: String? = null,
    val requires: String? = null, // "online"
    val format: String? = null,
    val destination: String? = null,
)

@Serializable
data class SkillOutput(
    @SerialName("spoken_summary") val spokenSummary: Boolean = true,
    val artifact: Boolean = true,
)

@Serializable
data class SkillDef(
    val id: String,
    val name: String,
    @SerialName("trigger_phrases") val triggerPhrases: List<String> = emptyList(),
    val inputs: List<SkillInput> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val output: SkillOutput = SkillOutput(),
) {
    val requiresOnline: Boolean get() = steps.any { it.requires == "online" }
}

val skillJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

fun parseSkill(json: String): SkillDef = skillJson.decodeFromString(SkillDef.serializer(), json)
fun encodeSkill(def: SkillDef): String = skillJson.encodeToString(SkillDef.serializer(), def)

/** `{topic}` etc. from inputs, plus `{prev}` = previous step's output. */
fun fillTemplate(template: String, inputs: Map<String, String>, prev: String): String {
    var out = template.replace("{prev}", prev)
    for ((k, v) in inputs) out = out.replace("{$k}", v)
    return out
}

/** The reference skill from spec §1.3. */
val CHRISTIAN_BOT = SkillDef(
    id = "christian-bot",
    name = "Christian Bot",
    triggerPhrases = listOf("christian bot", "bible script"),
    inputs = listOf(SkillInput("topic")),
    steps = listOf(
        SkillStep(
            type = "llm",
            prompt = "Analyze the topic '{topic}' from a biblical perspective. Cover the key theological themes, common misunderstandings, and why it matters to a modern audience.",
        ),
        SkillStep(
            type = "research",
            query = "Bible verses about {topic}",
            requires = "online",
        ),
        SkillStep(
            type = "llm",
            prompt = "Using this analysis:\n{prev}\n\nCompile a YouTube script about '{topic}': hook, key points, verses (book ch:v), research notes, CTA. Write it ready to read aloud.",
        ),
        SkillStep(type = "save_artifact", format = "markdown", destination = "local"),
    ),
)
