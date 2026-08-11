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

        return try {
            for ((i, step) in skill.steps.withIndex()) {
                log.appendLine("step ${i + 1}/${skill.steps.size}: ${step.type}")
                when (step.type) {
                    "llm" -> {
                        prev = askBrain(app, fillTemplate(step.prompt.orEmpty(), inputs, prev))
                    }
                    "research" -> {
                        val q = fillTemplate(step.query.orEmpty(), inputs, prev)
                        val research = askBrain(
                            app,
                            "Research task: $q\nList concrete findings (for scripture: book chapter:verse with the full text). Prior context:\n$prev",
                        )
                        prev = "$prev\n\nRESEARCH NOTES:\n$research"
                    }
                    "save_artifact" -> {
                        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "artifacts").apply { mkdirs() }
                        val f = File(dir, "${skill.id}-run$runId.md")
                        f.writeText("# ${skill.name} — ${inputs.values.joinToString()}\n\n$prev\n")
                        artifactPath = f.absolutePath
                    }
                    else -> log.appendLine("unknown step type '${step.type}' skipped")
                }
            }
            runs.markFinished(runId, "done", artifactPath, log.toString(), System.currentTimeMillis())
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
