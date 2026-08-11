package tech.gonxt.kate.skills

/** Voice entry points for AI #2: run a saved skill, or build a new one. */
object SkillIntents {

    sealed interface Intent {
        data class Run(val nameOrTrigger: String, val input: String?) : Intent
        data class Create(val description: String) : Intent
        data object None : Intent
    }

    private val RUN = listOf(
        Regex("^(?:(?:kate|moneypenny)[,;]?\\s*)?run (?:the )?(.+?)(?: (?:on|about|for) (.+?))?\\.?$", RegexOption.IGNORE_CASE),
        Regex("^(?:(?:kate|moneypenny)[,;]?\\s*)?(?:start|launch) (?:the )?(.+?) (?:skill|bot)(?: (?:on|about|for) (.+?))?\\.?$", RegexOption.IGNORE_CASE),
    )

    private val CREATE = listOf(
        Regex("^(?:(?:kate|moneypenny)[,;]?\\s*)?(?:build|create|make) (?:me )?a (?:new )?skill (?:that |which |to |called )?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:(?:kate|moneypenny)[,;]?\\s*)?build me (?:a |an )?(.+? bot .+)$", RegexOption.IGNORE_CASE),
    )

    fun classify(text: String): Intent {
        val t = text.trim()
        for (r in CREATE) {
            r.find(t)?.let { return Intent.Create(it.groupValues[1].trim()) }
        }
        for (r in RUN) {
            r.find(t)?.let { m ->
                val name = m.groupValues[1].trim()
                val input = m.groupValues.getOrNull(2)?.trim()?.ifEmpty { null }
                return Intent.Run(name, input)
            }
        }
        return Intent.None
    }
}
