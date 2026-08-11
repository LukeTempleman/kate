package tech.gonxt.kate.memory

/** Voice memory controls (spec Iteration 2): recall, "forget that," pin. */
object RecallIntents {

    sealed interface Intent {
        data class Recall(val query: String) : Intent
        data object Forget : Intent
        data object Pin : Intent
        data object None : Intent
    }

    private val RECALL = listOf(
        Regex("what did i (?:say|tell you) about (.+?)\\??$", RegexOption.IGNORE_CASE),
        Regex("what do you (?:know|remember) about (.+?)\\??$", RegexOption.IGNORE_CASE),
        Regex("do you remember (?:anything about |what i said about )?(.+?)\\??$", RegexOption.IGNORE_CASE),
        Regex("what did we (?:talk|chat|speak) about (.+?)\\??$", RegexOption.IGNORE_CASE),
    )

    private val FORGET = Regex(
        "^(?:(?:kate|moneypenny)[,;]?\\s*)?forget (?:that|it|this|what i just said)\\.?$",
        RegexOption.IGNORE_CASE,
    )

    private val PIN = Regex(
        "^(?:(?:kate|moneypenny)[,;]?\\s*)?(?:pin (?:that|this|it)|remember (?:that|this) (?:forever|permanently)|never forget (?:that|this))\\.?$",
        RegexOption.IGNORE_CASE,
    )

    fun classify(text: String): Intent {
        val t = text.trim()
        if (FORGET.matches(t)) return Intent.Forget
        if (PIN.matches(t)) return Intent.Pin
        for (r in RECALL) {
            r.find(t)?.let { return Intent.Recall(it.groupValues[1].trim().removeSuffix("?")) }
        }
        return Intent.None
    }
}
