package tech.gonxt.kate.ui.graph

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Small force-directed layout (Fruchterman–Reingold flavour) for the
 * Obsidian-style memory graph. Pure Kotlin so the physics is testable;
 * the composable just draws whatever this converges to.
 */
class GraphSim(private val random: Random = Random(42)) {

    data class P(var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = 0f)

    val positions = HashMap<Long, P>()
    private var nodeIds: List<Long> = emptyList()
    private var edges: List<Pair<Long, Long>> = emptyList()
    var alpha = 1f
        private set

    fun setGraph(ids: List<Long>, links: List<Pair<Long, Long>>) {
        nodeIds = ids
        edges = links.filter { it.first in positions || it.first in ids }
        for (id in ids) {
            positions.getOrPut(id) {
                P(random.nextFloat() * 1000f - 500f, random.nextFloat() * 1000f - 500f)
            }
        }
        positions.keys.retainAll(ids.toSet())
        alpha = 1f
    }

    val settled: Boolean get() = alpha < 0.01f

    fun step() {
        if (settled || nodeIds.isEmpty()) return
        val k = 90f // ideal spring length

        // Repulsion (capped pairwise — graphs stay in the low hundreds of nodes)
        for (i in nodeIds.indices) {
            val a = positions[nodeIds[i]] ?: continue
            for (j in i + 1 until nodeIds.size) {
                val b = positions[nodeIds[j]] ?: continue
                var dx = a.x - b.x
                var dy = a.y - b.y
                var d2 = dx * dx + dy * dy
                if (d2 < 0.01f) { dx = random.nextFloat() - 0.5f; dy = random.nextFloat() - 0.5f; d2 = 1f }
                val d = sqrt(d2)
                val force = (k * k / d) * 0.02f * alpha
                val fx = dx / d * force
                val fy = dy / d * force
                a.vx += fx; a.vy += fy
                b.vx -= fx; b.vy -= fy
            }
        }

        // Springs along edges
        for ((from, to) in edges) {
            val a = positions[from] ?: continue
            val b = positions[to] ?: continue
            val dx = b.x - a.x
            val dy = b.y - a.y
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.1f)
            val force = (d - k) * 0.05f * alpha
            val fx = dx / d * force
            val fy = dy / d * force
            a.vx += fx; a.vy += fy
            b.vx -= fx; b.vy -= fy
        }

        // Gravity to centre + integrate with damping
        for (id in nodeIds) {
            val p = positions[id] ?: continue
            p.vx -= p.x * 0.005f * alpha
            p.vy -= p.y * 0.005f * alpha
            p.vx *= 0.85f
            p.vy *= 0.85f
            p.x += p.vx
            p.y += p.vy
        }
        alpha *= 0.985f
    }

    /** Nearest node within [radius] of world-space point, for tap hit-testing. */
    fun hitTest(x: Float, y: Float, radius: Float = 40f): Long? =
        positions.entries
            .map { (id, p) -> id to ((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y)) }
            .filter { it.second <= radius * radius }
            .minByOrNull { it.second }
            ?.first
}
