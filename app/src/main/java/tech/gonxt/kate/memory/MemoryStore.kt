package tech.gonxt.kate.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.gonxt.kate.memory.db.GraphEdgeEntity
import tech.gonxt.kate.memory.db.GraphNodeEntity
import tech.gonxt.kate.memory.db.KateDb
import tech.gonxt.kate.memory.db.MemoryEntity
import tech.gonxt.kate.memory.db.ConversationEntity
import tech.gonxt.kate.memory.db.TurnEntity

data class RecallHit(val memory: MemoryEntity, val score: Float)

/**
 * Iteration 2: every turn captured, embedded, and woven into the graph
 * (conversation → topics → entities); recall = vector + graph search.
 */
class MemoryStore(
    private val db: KateDb,
    @Volatile var embedder: Embedder,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Iteration 5: every write also appends a sync event. */
    var recorder: tech.gonxt.kate.sync.SyncRecorder? = null,
) {
    private var conversationId: Long = -1
    private var conversationNodeId: Long = -1
    private val convMutex = Mutex()

    val memoryCount = db.memories().count()
    val graphNodes = db.graph().nodes()
    val graphEdges = db.graph().edges()
    val kateAnswers = db.turns().kateAnswers()

    private suspend fun conversation(): Long = convMutex.withLock {
        if (conversationId == -1L) {
            val startedAt = clock()
            conversationId = db.conversations().insert(
                ConversationEntity(startedAt = startedAt),
            )
            conversationNodeId = nodeId("conversation", "session ${conversationId}")
            recorder?.let { r ->
                r.record(
                    "conversation", r.globalId(conversationId), "upsert",
                    buildJsonObject {
                        put("started_at", startedAt)
                        put("device", r.deviceId)
                    },
                )
            }
        }
        conversationId
    }

    /** Called by the engine after each turn; heavy work happens off the voice loop. */
    fun onTurn(role: String, text: String, modelUsed: String?, latencyMs: Long?) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val convId = conversation()
            val createdAt = clock()
            val turnId = db.turns().insert(
                TurnEntity(
                    conversationId = convId,
                    role = role,
                    text = text,
                    modelUsed = modelUsed,
                    latencyMs = latencyMs,
                    createdAt = createdAt,
                ),
            )
            recorder?.let { r ->
                r.record(
                    "turn", r.globalId(turnId), "upsert",
                    buildJsonObject {
                        put("conversation_id", r.globalId(convId))
                        put("role", role)
                        put("text", text)
                        put("model_used", modelUsed)
                        put("latency_ms", latencyMs)
                        put("created_at", createdAt)
                    },
                )
            }
            if (role == "user") capture(text, turnId)
        }
    }

    private suspend fun capture(text: String, turnId: Long) {
        val createdAt = clock()
        val memoryId = db.memories().insert(
            MemoryEntity(
                type = "utterance",
                content = text,
                embedding = runCatching { embedder.embed(text).toBytes() }.getOrNull(),
                embedderId = embedder.id,
                sourceTurnId = turnId,
                createdAt = createdAt,
            ),
        )
        recorder?.let { r ->
            r.record(
                "memory", r.globalId(memoryId), "upsert",
                buildJsonObject {
                    put("type", "utterance")
                    put("content", text)
                    put("pinned", 0)
                    put("deleted", 0)
                    put("source_turn_id", r.globalId(turnId))
                    put("created_at", createdAt)
                },
            )
        }
        val memoryNode = nodeId("memory", "m$memoryId", memoryId)
        link(conversationNodeId, memoryNode, "contains")

        val (topics, entities) = TopicExtractor.extract(text)
        for (t in topics) link(memoryNode, nodeId("topic", t), "about")
        for (e in entities) link(memoryNode, nodeId("entity", e), "mentions")
    }

    private suspend fun nodeId(kind: String, label: String, memoryId: Long? = null): Long {
        db.graph().findNode(kind, label)?.let { return it.id }
        val id = db.graph().insertNode(GraphNodeEntity(kind = kind, label = label, memoryId = memoryId))
        recorder?.let { r ->
            r.record(
                "graph_node", r.globalId(id), "upsert",
                buildJsonObject {
                    put("kind", kind)
                    put("label", label)
                    put("memory_id", memoryId?.let { r.globalId(it) })
                },
            )
        }
        return id
    }

    private suspend fun link(from: Long, to: Long, relation: String) {
        if (from <= 0 || to <= 0) return
        if (db.graph().bumpEdge(from, to, relation, 1f) == 0) {
            val id = db.graph().insertEdge(GraphEdgeEntity(fromNode = from, toNode = to, relation = relation))
            recorder?.let { r ->
                r.record(
                    "graph_edge", r.globalId(id), "upsert",
                    buildJsonObject {
                        put("from_node", r.globalId(from))
                        put("to_node", r.globalId(to))
                        put("relation", relation)
                        put("weight", 1f)
                    },
                )
            }
        }
    }

    /** "Kate, what did I say about X?" — vector similarity + plain text match. */
    suspend fun recall(query: String, limit: Int = 3): List<RecallHit> {
        val q = runCatching { embedder.embed(query) }.getOrNull()
        val candidates = db.memories().recent()
        val scored = candidates.map { m ->
            val vecScore = if (q != null && m.embedding != null && m.embedderId == embedder.id) {
                cosine(q, m.embedding.toFloats())
            } else 0f
            val textScore = query.lowercase().split(" ")
                .count { it.length > 3 && m.content.lowercase().contains(it) } * 0.15f
            RecallHit(m, vecScore + textScore)
        }
        return scored.filter { it.score > 0.1f }.sortedByDescending { it.score }.take(limit)
    }

    /** "Forget that" — tombstone the newest memory; deletions always win later in sync. */
    suspend fun forgetLast(): MemoryEntity? {
        val last = db.memories().latest() ?: return null
        db.memories().tombstone(last.id)
        recorder?.let { it.record("memory", it.globalId(last.id), "delete", null) }
        return last
    }

    /** "Pin that" — mark the newest memory permanent. */
    suspend fun pinLast(): MemoryEntity? {
        val last = db.memories().latest() ?: return null
        db.memories().setPinned(last.id, true)
        recorder?.let { r ->
            r.record(
                "memory", r.globalId(last.id), "upsert",
                buildJsonObject {
                    put("type", last.type)
                    put("content", last.content)
                    put("pinned", 1)
                    put("created_at", last.createdAt)
                },
            )
        }
        return last
    }

    suspend fun rateTurn(turnId: Long, rating: Int) = db.turns().rate(turnId, rating)

    // Dashboard graph editing (spec Iteration 4: tap to expand, edit/delete/pin).

    suspend fun nodeDetail(nodeId: Long): String? {
        val node = db.graph().nodeById(nodeId) ?: return null
        val mem = node.memoryId?.let { db.memories().byId(it) }
        return mem?.content ?: node.label
    }

    suspend fun deleteNode(nodeId: Long) {
        val node = db.graph().nodeById(nodeId) ?: return
        db.graph().deleteEdgesFor(nodeId)
        db.graph().deleteNode(nodeId)
        recorder?.let { it.record("graph_node", it.globalId(nodeId), "delete", null) }
        node.memoryId?.let { memId ->
            db.memories().tombstone(memId)
            recorder?.let { it.record("memory", it.globalId(memId), "delete", null) }
        }
    }

    suspend fun pinNode(nodeId: Long): Boolean {
        val node = db.graph().nodeById(nodeId) ?: return false
        val memId = node.memoryId ?: return false
        db.memories().setPinned(memId, true)
        return true
    }
}
