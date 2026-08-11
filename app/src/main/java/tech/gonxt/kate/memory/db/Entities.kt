package tech.gonxt.kate.memory.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Local mirror of the D1 schema (spec §2.3); synced up in Iteration 5.

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val context: String = "",
    val device: String = "phone",
)

@Entity(
    tableName = "turns",
    indices = [Index("conversationId")],
)
data class TurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // user | kate
    val text: String,
    val audioMs: Long = 0,
    val modelUsed: String? = null,
    val latencyMs: Long? = null,
    val rating: Int = 0, // -1 | 0 | +1
    val createdAt: Long,
)

@Entity(
    tableName = "memories",
    indices = [Index("sourceTurnId"), Index("deleted")],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // utterance | fact | topic-summary
    val content: String,
    val embedding: ByteArray? = null,
    val embedderId: String? = null,
    val pinned: Boolean = false,
    val deleted: Boolean = false, // tombstone — deletions always win in sync
    val sourceTurnId: Long? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "graph_nodes",
    indices = [Index(value = ["kind", "label"], unique = true)],
)
data class GraphNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String, // topic | entity | conversation | skill
    val label: String,
    val memoryId: Long? = null,
)

@Entity(
    tableName = "graph_edges",
    indices = [Index("fromNode"), Index("toNode")],
)
data class GraphEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromNode: Long,
    val toNode: Long,
    val relation: String, // mentions | about | relates
    val weight: Float = 1f,
)
