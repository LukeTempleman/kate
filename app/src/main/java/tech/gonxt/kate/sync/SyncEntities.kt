package tech.gonxt.kate.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** Phone-side event log (spec §2.2): every change appends; /sync drains it. */
@Entity(
    tableName = "sync_log",
    indices = [Index("applied")],
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entity: String, // conversation | turn | memory | graph_node | graph_edge | skill | skill_run
    val entityId: String,
    val op: String, // upsert | delete
    val payloadJson: String,
    val hlc: String,
    val applied: Boolean = false,
)

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(e: SyncLogEntity): Long

    @Query("SELECT * FROM sync_log WHERE applied = 0 ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int = 200): List<SyncLogEntity>

    @Query("UPDATE sync_log SET applied = 1 WHERE id IN (:ids)")
    suspend fun markApplied(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM sync_log WHERE applied = 0")
    suspend fun pendingCount(): Int
}
