package tech.gonxt.kate

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.gonxt.kate.ui.graph.GraphSim
import kotlin.math.hypot

class GraphSimTest {

    private fun dist(sim: GraphSim, a: Long, b: Long): Float {
        val pa = sim.positions[a]!!
        val pb = sim.positions[b]!!
        return hypot((pa.x - pb.x).toDouble(), (pa.y - pb.y).toDouble()).toFloat()
    }

    @Test
    fun `connected nodes end up closer than unconnected ones`() {
        val sim = GraphSim()
        sim.setGraph(listOf(1L, 2L, 3L), listOf(1L to 2L))
        repeat(600) { sim.step() }
        assertTrue(dist(sim, 1, 2) < dist(sim, 1, 3))
        assertTrue(dist(sim, 1, 2) < dist(sim, 2, 3))
    }

    @Test
    fun `simulation settles`() {
        val sim = GraphSim()
        sim.setGraph((1L..30L).toList(), (2L..30L).map { 1L to it })
        repeat(1000) { sim.step() }
        assertTrue(sim.settled)
    }

    @Test
    fun `hit test finds nearby node only`() {
        val sim = GraphSim()
        sim.setGraph(listOf(1L), emptyList())
        val p = sim.positions[1L]!!
        assertNotNull(sim.hitTest(p.x + 5f, p.y - 5f, radius = 40f))
        assertNull(sim.hitTest(p.x + 500f, p.y, radius = 40f))
    }

    @Test
    fun `removed nodes drop from positions`() {
        val sim = GraphSim()
        sim.setGraph(listOf(1L, 2L), listOf(1L to 2L))
        sim.setGraph(listOf(2L), emptyList())
        assertNull(sim.positions[1L])
        assertNotNull(sim.positions[2L])
    }
}
