package tech.gonxt.kate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.gonxt.kate.skills.CHRISTIAN_BOT
import tech.gonxt.kate.skills.SkillIntents
import tech.gonxt.kate.skills.encodeSkill
import tech.gonxt.kate.skills.fillTemplate
import tech.gonxt.kate.skills.parseSkill

class SkillsTest {

    @Test
    fun `spec example json round-trips`() {
        val json = """
        {
          "id": "christian-bot",
          "name": "Christian Bot",
          "trigger_phrases": ["christian bot", "bible script"],
          "inputs": [{ "name": "topic", "type": "voice_string" }],
          "steps": [
            { "type": "llm", "prompt": "Analyze the topic '{topic}' from a biblical perspective..." },
            { "type": "research", "query": "Bible verses about {topic}", "requires": "online" },
            { "type": "llm", "prompt": "Compile a YouTube script." },
            { "type": "save_artifact", "format": "markdown", "destination": "r2 + local" }
          ],
          "output": { "spoken_summary": true, "artifact": true }
        }
        """.trimIndent()
        val def = parseSkill(json)
        assertEquals("christian-bot", def.id)
        assertEquals(listOf("christian bot", "bible script"), def.triggerPhrases)
        assertEquals(4, def.steps.size)
        assertTrue(def.requiresOnline)
        assertTrue(def.output.spokenSummary)
        val reparsed = parseSkill(encodeSkill(def))
        assertEquals(def, reparsed)
    }

    @Test
    fun `template fills inputs and prev`() {
        val out = fillTemplate("About {topic}: {prev}", mapOf("topic" to "grace"), "earlier analysis")
        assertEquals("About grace: earlier analysis", out)
    }

    @Test
    fun `run intent with input`() {
        val i = SkillIntents.classify("Kate, run christian bot on forgiveness")
        assertTrue(i is SkillIntents.Intent.Run)
        i as SkillIntents.Intent.Run
        assertEquals("christian bot", i.nameOrTrigger)
        assertEquals("forgiveness", i.input)
    }

    @Test
    fun `run intent without input`() {
        val i = SkillIntents.classify("run bible script") as SkillIntents.Intent.Run
        assertEquals("bible script", i.nameOrTrigger)
        assertEquals(null, i.input)
    }

    @Test
    fun `create intent captures description`() {
        val i = SkillIntents.classify("build me a skill that summarises my unread emails every morning")
        assertTrue(i is SkillIntents.Intent.Create)
        assertTrue((i as SkillIntents.Intent.Create).description.contains("summarises my unread emails"))
    }

    @Test
    fun `ordinary speech is none`() {
        assertEquals(SkillIntents.Intent.None, SkillIntents.classify("how far is the moon"))
    }

    @Test
    fun `built-in christian bot matches spec shape`() {
        assertEquals("christian-bot", CHRISTIAN_BOT.id)
        assertEquals(listOf("christian bot", "bible script"), CHRISTIAN_BOT.triggerPhrases)
        assertEquals("topic", CHRISTIAN_BOT.inputs.first().name)
        assertTrue(CHRISTIAN_BOT.requiresOnline)
        assertEquals("save_artifact", CHRISTIAN_BOT.steps.last().type)
    }
}
