package tech.gonxt.kate.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tech.gonxt.kate.KateApplication
import tech.gonxt.kate.memory.db.MemoryEntity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Spec §2.2: when online, POST pending events to the Sync Worker, mark them
 * applied, and fold in portal-origin events (nightly consolidation output).
 */
class SyncEngine(
    private val context: Context,
    private val app: KateApplication,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun syncOnce(): Boolean = withContext(Dispatchers.IO) {
        val settings = app.settings.value
        val base = settings.portalUrl.trimEnd('/')
        val token = settings.portalToken
        if (base.isBlank() || token.isBlank()) return@withContext false

        val pending = app.db.syncLog().pending()
        val sinceSeq = app.settingsRepository.portalSeq()
        val body = buildJsonObject {
            put("device", app.deviceId)
            put("since_seq", sinceSeq)
            put(
                "events",
                buildJsonArray {
                    for (e in pending) {
                        add(
                            buildJsonObject {
                                put("entity", e.entity)
                                put("entity_id", e.entityId)
                                put("op", e.op)
                                put("hlc", e.hlc)
                                put("payload", json.parseToJsonElement(e.payloadJson))
                            },
                        )
                    }
                },
            )
        }

        val conn = URL("$base/sync").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            check(conn.responseCode == 200) { "sync http ${conn.responseCode}" } // throw → WorkManager retries

            val reply = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            app.db.syncLog().markApplied(pending.map { it.id })

            // Portal-origin events down (consolidated memories, spec §2.5).
            // Per-event guard: one bad event must not block seq progress (redelivery = duplicates).
            for (ev in reply["events"]?.jsonArray.orEmpty()) {
                runCatching {
                    val o = ev.jsonObject
                    if (o["entity"]?.jsonPrimitive?.content != "memory") return@runCatching
                    if (o["op"]?.jsonPrimitive?.content != "upsert") return@runCatching
                    val p = o["payload"]?.jsonObject ?: return@runCatching
                    val content = p["content"]?.jsonPrimitive?.content ?: return@runCatching
                    o["hlc"]?.jsonPrimitive?.content?.let { app.hlc.update(it) }
                    app.db.memories().insert(
                        MemoryEntity(
                            type = p["type"]?.jsonPrimitive?.content ?: "topic-summary",
                            content = content,
                            createdAt = p["created_at"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                        ),
                    )
                }
            }
            reply["latest_seq"]?.jsonPrimitive?.long?.let { app.settingsRepository.setPortalSeq(it) }
            true
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "kate-sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KateApplication
        return try {
            app.syncEngine.syncOnce() // false = portal not configured; both are terminal states
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
