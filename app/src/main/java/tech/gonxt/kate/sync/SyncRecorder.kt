package tech.gonxt.kate.sync

import kotlinx.serialization.json.JsonObject
import tech.gonxt.kate.memory.db.KateDb

/**
 * Appends one event per local change (spec §2.2 step 1). IDs are globally
 * namespaced as "<device>:<localId>" so multiple devices can't collide in D1.
 */
class SyncRecorder(
    private val db: KateDb,
    private val hlc: Hlc,
    val deviceId: String,
) {
    fun globalId(localId: Any): String = "$deviceId:$localId"

    suspend fun record(entity: String, entityId: String, op: String, payload: JsonObject?) {
        db.syncLog().insert(
            SyncLogEntity(
                entity = entity,
                entityId = entityId,
                op = op,
                payloadJson = payload?.toString() ?: "{}",
                hlc = hlc.tick(),
            ),
        )
    }
}
