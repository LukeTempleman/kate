package tech.gonxt.kate.skills.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val definitionJson: String,
    val version: Int = 1,
    val createdVia: String = "voice",
    val updatedAt: Long,
)

@Entity(
    tableName = "skill_runs",
    indices = [Index("skillId"), Index("status")],
)
data class SkillRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: String,
    val inputsJson: String,
    val status: String, // queued | pending-online | running | done | failed | announced
    val artifactPath: String? = null,
    val log: String = "",
    val ranOn: String = "local", // local | cloud (Iteration 5)
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val createdAt: Long,
)

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY updatedAt DESC")
    fun all(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills")
    suspend fun list(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun byId(id: String): SkillEntity?
}

@Dao
interface SkillRunDao {
    @Insert
    suspend fun insert(r: SkillRunEntity): Long

    @Query("SELECT * FROM skill_runs WHERE id = :id")
    suspend fun byId(id: Long): SkillRunEntity?

    @Query("UPDATE skill_runs SET status = :status, startedAt = COALESCE(startedAt, :now) WHERE id = :id")
    suspend fun markStarted(id: Long, status: String, now: Long)

    @Query("UPDATE skill_runs SET status = :status, artifactPath = :artifactPath, log = :log, finishedAt = :now WHERE id = :id")
    suspend fun markFinished(id: Long, status: String, artifactPath: String?, log: String, now: Long)

    @Query("SELECT * FROM skill_runs ORDER BY id DESC LIMIT 100")
    fun recent(): Flow<List<SkillRunEntity>>
}
