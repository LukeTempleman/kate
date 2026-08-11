package tech.gonxt.kate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.gonxt.kate.sync.Hlc

class HlcTest {

    @Test
    fun `ticks are monotonic even with a frozen wall clock`() {
        var now = 1_000_000L
        val hlc = Hlc("dev1") { now }
        val a = hlc.tick()
        val b = hlc.tick()
        val c = hlc.tick()
        assertTrue(a < b && b < c)
        now += 5
        val d = hlc.tick()
        assertTrue(c < d)
    }

    @Test
    fun `string encoding sorts across devices`() {
        val early = Hlc.encode(1_000L, 0, "aaa")
        val late = Hlc.encode(2_000L, 0, "zzz")
        val lateLowCounter = Hlc.encode(2_000L, 1, "aaa")
        assertTrue(early < late)
        assertTrue(late < lateLowCounter)
    }

    @Test
    fun `update folds in a remote clock ahead of us`() {
        var now = 1_000L
        val hlc = Hlc("dev1") { now }
        hlc.update(Hlc.encode(50_000L, 3, "dev2"))
        val next = hlc.tick()
        assertTrue(next > Hlc.encode(50_000L, 3, "dev2"))
    }

    @Test
    fun `encode pads for lexicographic order`() {
        assertEquals("00000000001000-0000-d", Hlc.encode(1000, 0, "d"))
    }
}
