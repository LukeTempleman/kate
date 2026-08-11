package tech.gonxt.kate.skills

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import tech.gonxt.kate.brain.Brain
import tech.gonxt.kate.brain.isOnline
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.core.OrbState
import tech.gonxt.kate.core.ConversationEngine
import tech.gonxt.kate.core.Role
import tech.gonxt.kate.memory.db.KateDb
import tech.gonxt.kate.skills.db.SkillEntity
import tech.gonxt.kate.skills.db.SkillRunEntity
import java.util.concurrent.TimeUnit

/**
 * Skills engine (spec §1.3 / Iteration 3): create by voice, versioned JSON,
 * trigger by name; AI #1 acknowledges instantly while AI #2 runs behind her.
 */
class SkillManager(
    private val context: Context,
    private val db: KateDb,
    private val brain: Brain,
    private val scope: CoroutineScope,
    private val engineProvider: () -> ConversationEngine,
) {
    val skills = db.skills().all()
    val runs = db.skillRuns().recent()

    init {
        scope.launch { seed() }
        val channel = NotificationChannel(
            CHANNEL_ID, "Kate tasks", NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private suspend fun seed() {
        if (db.skills().byId(CHRISTIAN_BOT.id) == null) {
            db.skills().upsert(
                SkillEntity(
                    id = CHRISTIAN_BOT.id,
                    name = CHRISTIAN_BOT.name,
                    definitionJson = encodeSkill(CHRISTIAN_BOT),
                    createdVia = "built-in",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Pre-brain intercept. Returns Kate's instant acknowledgement, or null. */
    suspend fun intercept(userText: String): String? =
        when (val intent = SkillIntents.classify(userText)) {
            is SkillIntents.Intent.Run -> handleRun(intent)
            is SkillIntents.Intent.Create -> handleCreate(intent)
            SkillIntents.Intent.None -> null
        }

    private suspend fun handleRun(intent: SkillIntents.Intent.Run): String? {
        val skill = findSkill(intent.nameOrTrigger) ?: return null
        val def = parseSkill(skill.definitionJson)
        val inputName = def.inputs.firstOrNull()?.name
        if (inputName != null && intent.input == null) {
            return "Which ${inputName}? Say: run ${def.name} on, then the $inputName."
        }
        val inputs = if (inputName != null) mapOf(inputName to intent.input!!) else emptyMap()
        val runId = enqueue(def, inputs)
        val waiting = def.requiresOnline && !isOnline(context)
        return if (waiting) {
            "Queued ${def.name} — it needs the internet, so it'll run the moment we're back online."
        } else {
            "On it. ${def.name} is running behind me — I'll tell you when it's done."
        }
    }

    private suspend fun handleCreate(intent: SkillIntents.Intent.Create): String {
        scope.launch {
            runCatching { draftSkill(intent.description) }
                .onSuccess { def ->
                    save(def, createdVia = "voice")
                    notify("Skill saved", "“${def.name}” v1 — say: run ${def.name}")
                    speakIfIdle("Your new skill ${def.name} is saved. Say run ${def.name} to use it.")
                }
                .onFailure {
                    notify("Skill creation failed", it.message ?: "unknown error")
                    speakIfIdle("I couldn't build that skill — ${it.message ?: "something went wrong"}.")
                }
        }
        return "Right, I'm drafting that skill now — give me a moment and I'll confirm."
    }

    private suspend fun draftSkill(description: String): SkillDef {
        val example = encodeSkill(CHRISTIAN_BOT)
        val prompt = """Design a Kate skill as JSON for this request: "$description".
Use exactly this schema (example):
$example

Rules: kebab-case id; step types only "llm", "research" (requires:"online"), "save_artifact";
inputs of type voice_string; prompts may use {input_name} and {prev}. Reply with JSON only."""
        val raw = brain.reply(listOf(ChatMessage(0, Role.USER, prompt))).toList().joinToString("")
        val json = raw.substringAfter("```json", raw).substringAfter("```", raw).substringBefore("```").trim()
            .ifEmpty { raw.trim() }
        return parseSkill(json)
    }

    suspend fun save(def: SkillDef, createdVia: String) {
        val existing = db.skills().byId(def.id)
        db.skills().upsert(
            SkillEntity(
                id = def.id,
                name = def.name,
                definitionJson = encodeSkill(def),
                version = (existing?.version ?: 0) + 1,
                createdVia = createdVia,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun findSkill(nameOrTrigger: String): SkillEntity? {
        val needle = nameOrTrigger.lowercase().trim()
        return db.skills().list().firstOrNull { row ->
            val def = runCatching { parseSkill(row.definitionJson) }.getOrNull() ?: return@firstOrNull false
            row.name.lowercase() == needle ||
                def.triggerPhrases.any { it.lowercase() == needle || needle.contains(it.lowercase()) }
        }
    }

    suspend fun enqueue(def: SkillDef, inputs: Map<String, String>): Long {
        val runId = db.skillRuns().insert(
            SkillRunEntity(
                skillId = def.id,
                inputsJson = kotlinx.serialization.json.Json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    inputs,
                ),
                status = if (def.requiresOnline && !isOnline(context)) "pending-online" else "queued",
                createdAt = System.currentTimeMillis(),
            ),
        )
        val request = OneTimeWorkRequestBuilder<SkillRunWorker>()
            .setInputData(workDataOf(SkillRunWorker.KEY_RUN_ID to runId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply {
                if (def.requiresOnline) {
                    setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                }
            }
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return runId
    }

    /** Results announced by voice or notification (spec Iteration 3). */
    fun announce(def: SkillDef, runId: Long, success: Boolean) {
        val text = if (success) {
            "${def.name} is done — the artifact is saved in your builds."
        } else {
            "${def.name} failed. The log is in your builds view."
        }
        notify(if (success) "Task complete" else "Task failed", "${def.name} · run $runId")
        speakIfIdle(text)
    }

    private fun speakIfIdle(text: String) {
        val engine = engineProvider()
        if (engine.orbState.value == OrbState.IDLE) engine.speakDirect(text)
    }

    private fun notify(title: String, body: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(tech.gonxt.kate.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10_000).toInt(), n)
    }

    private companion object {
        const val CHANNEL_ID = "kate_tasks"
    }
}
