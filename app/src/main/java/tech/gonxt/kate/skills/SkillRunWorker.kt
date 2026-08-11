package tech.gonxt.kate.skills

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import tech.gonxt.kate.KateApplication
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.core.Role
import java.io.File

/**
 * AI #2 (spec §1.2): executes one skill run behind the conversation.
 * WorkManager owns retries and the network constraint — a run whose steps
 * `require: online` simply waits (`pending-online`) until connectivity.
 */
class SkillRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as KateApplication
        val runId = inputData.getLong(KEY_RUN_ID, -1)
        if (runId == -1L) return Result.failure()

        val runs = app.db.skillRuns()
        val run = runs.byId(runId) ?: return Result.failure()
        val skillRow = app.db.skills().byId(run.skillId) ?: return Result.failure()
        val skill = parseSkill(skillRow.definitionJson)
        val inputs = Json.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            run.inputsJson,
        )

        runs.markStarted(runId, "running", System.currentTimeMillis())
        val log = StringBuilder()
        var prev = ""
        var artifactPath: String? = null

        var ranOnCloud = false
        return try {
            for ((i, step) in skill.steps.withIndex()) {
                log.appendLine("step ${i + 1}/${skill.steps.size}: ${step.type}")
                when (step.type) {
                    "llm" -> {
                        val prompt = fillTemplate(step.prompt.orEmpty(), inputs, prev)
                        prev = cloudStep(app, prompt)?.also { ranOnCloud = true } ?: askBrain(app, prompt)
                    }
                    "research" -> {
                        val q = fillTemplate(step.query.orEmpty(), inputs, prev)
                        val prompt = "Research task: $q\nList concrete findings (for scripture: book chapter:verse with the full text). Prior context:\n$prev"
                        val research = cloudStep(app, prompt)?.also { ranOnCloud = true } ?: askBrain(app, prompt)
                        prev = "$prev\n\nRESEARCH NOTES:\n$research"
                    }
                    "save_artifact" -> {
                        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "artifacts").apply { mkdirs() }
                        val f = File(dir, "${skill.id}-run$runId.md")
                        val content = "# ${skill.name} — ${inputs.values.joinToString()}\n\n$prev\n"
                        f.writeText(content)
                        artifactPath = f.absolutePath
                        uploadArtifact(app, "${skill.id}-run$runId.md", content)
                    }
                    else -> log.appendLine("unknown step type '${step.type}' skipped")
                }
            }
            runs.markFinished(runId, "done", artifactPath, log.toString(), System.currentTimeMillis())
            app.syncRecorder?.let { r ->
                r.record(
                    "skill_run", r.globalId(runId), "upsert",
                    kotlinx.serialization.json.buildJsonObject {
                        put("skill_id", kotlinx.serialization.json.JsonPrimitive(skill.id))
                        put("status", kotlinx.serialization.json.JsonPrimitive("done"))
                        put("ran_on", kotlinx.serialization.json.JsonPrimitive(if (ranOnCloud) "cloud" else "local"))
                        put("artifact_r2_key", kotlinx.serialization.json.JsonPrimitive("${skill.id}-run$runId.md"))
                        put("finished_at", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
                    },
                )
            }
            app.skillManager.announce(skill, runId, success = true)
            Result.success()
        } catch (e: Exception) {
            log.appendLine("failed: ${e.message}")
            if (runAttemptCount < 2) {
                runs.markStarted(runId, "queued", System.currentTimeMillis())
                Result.retry()
            } else {
                runs.markFinished(runId, "failed", artifactPath, log.toString(), System.currentTimeMillis())
                app.skillManager.announce(skill, runId, success = false)
                Result.failure()
            }
        }
    }

    /** Spec §2.5: once the portal exists, heavy steps run on Workers AI. */
    private suspend fun cloudStep(app: KateApplication, prompt: String): String? {
        val s = app.settings.value
        if (s.portalToken.isBlank() || !tech.gonxt.kate.brain.isOnline(app)) return null
        return runCatching {
            val conn = java.net.URL("${s.portalUrl.trimEnd('/')}/api/ai/step")
                .openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.setRequestProperty("Authorization", "Bearer ${s.portalToken}")
                conn.setRequestProperty("Content-Type", "application/json")
                val body = kotlinx.serialization.json.buildJsonObject {
                    put("prompt", kotlinx.serialization.json.JsonPrimitive(prompt))
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                check(conn.responseCode == 200) { "portal ${conn.responseCode}" }
                val reply = kotlinx.serialization.json.Json.parseToJsonElement(
                    conn.inputStream.bufferedReader().readText(),
                ).let { it as kotlinx.serialization.json.JsonObject }
                (reply["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Artifacts → R2 through the portal; D1 keeps the key (spec §2.2 step 4). */
    private fun uploadArtifact(app: KateApplication, key: String, content: String) {
        val s = app.settings.value
        if (s.portalToken.isBlank() || !tech.gonxt.kate.brain.isOnline(app)) return
        runCatching {
            val conn = java.net.URL("${s.portalUrl.trimEnd('/')}/api/artifact?key=$key")
                .openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer ${s.portalToken}")
                conn.outputStream.use { it.write(content.toByteArray()) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun askBrain(app: KateApplication, prompt: String): String {
        val tokens = app.brainRouter
            .reply(listOf(ChatMessage(0, Role.USER, prompt)))
            .toList()
        val text = tokens.joinToString("").trim()
        check(text.isNotEmpty()) { "brain returned nothing" }
        return text
    }

    companion object {
        const val KEY_RUN_ID = "run_id"
    }
}
