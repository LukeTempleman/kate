package tech.gonxt.kate.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(c: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY startedAt DESC")
    fun all(): Flow<List<ConversationEntity>>
}

@Dao
interface TurnDao {
    @Insert
    suspend fun insert(t: TurnEntity): Long

    @Query("SELECT * FROM turns WHERE conversationId = :conversationId ORDER BY id")
    suspend fun forConversation(conversationId: Long): List<TurnEntity>

    @Query("SELECT * FROM turns WHERE role = 'kate' ORDER BY id DESC LIMIT :limit")
    fun kateAnswers(limit: Int = 200): Flow<List<TurnEntity>>

    @Query("UPDATE turns SET rating = :rating WHERE id = :turnId")
    suspend fun rate(turnId: Long, rating: Int)
}

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(m: MemoryEntity): Long

    @Query("SELECT * FROM memories WHERE deleted = 0 ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 2000): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE deleted = 0 AND content LIKE '%' || :needle || '%' ORDER BY id DESC LIMIT :limit")
    suspend fun textSearch(needle: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE deleted = 0 ORDER BY id DESC LIMIT 1")
    suspend fun latest(): MemoryEntity?

    @Query("UPDATE memories SET deleted = 1 WHERE id = :id")
    suspend fun tombstone(id: Long)

    @Query("UPDATE memories SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("SELECT COUNT(*) FROM memories WHERE deleted = 0")
    fun count(): Flow<Int>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: Long): MemoryEntity?
}

@Dao
interface GraphDao {
    @Insert
    suspend fun insertNode(n: GraphNodeEntity): Long

    @Query("SELECT * FROM graph_nodes WHERE kind = :kind AND label = :label LIMIT 1")
    suspend fun findNode(kind: String, label: String): GraphNodeEntity?

    @Insert
    suspend fun insertEdge(e: GraphEdgeEntity): Long

    @Query("UPDATE graph_edges SET weight = weight + :delta WHERE fromNode = :from AND toNode = :to AND relation = :relation")
    suspend fun bumpEdge(from: Long, to: Long, relation: String, delta: Float): Int

    @Query("SELECT * FROM graph_nodes")
    fun nodes(): Flow<List<GraphNodeEntity>>

    @Query("SELECT * FROM graph_edges")
    fun edges(): Flow<List<GraphEdgeEntity>>

    @Query("SELECT * FROM graph_nodes WHERE label LIKE '%' || :needle || '%'")
    suspend fun searchNodes(needle: String): List<GraphNodeEntity>

    @Query("DELETE FROM graph_nodes WHERE id = :id")
    suspend fun deleteNode(id: Long)

    @Query("SELECT * FROM graph_nodes WHERE id = :id")
    suspend fun nodeById(id: Long): GraphNodeEntity?

    @Query("DELETE FROM graph_edges WHERE fromNode = :nodeId OR toNode = :nodeId")
    suspend fun deleteEdgesFor(nodeId: Long)
}
