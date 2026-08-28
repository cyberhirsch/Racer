package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebrisTest {

    @Test
    fun `a burst throws shards, and they are all real numbers`() {
        val d = Debris()
        d.burst(Vec3(0f, 1f, 0f), Vec3(0f, 0f, -1f), 30f)
        assertTrue("nothing was thrown", d.liveCount > 20)
        for (f in d.vertices) assertTrue("a vertex came out $f", f.isFinite())
    }

    /**
     * The one that would have caught the generator returning almost zero
     * every time: shards must actually go in different directions, or the
     * spray is a single point and the rejection sampler behind it never
     * terminates.
     */
    @Test
    fun `shards scatter rather than all going to the same place`() {
        val d = Debris()
        d.burst(Vec3(0f, 1f, 0f), Vec3(0f, 0f, -1f), 40f)
        repeat(30) { d.step(1f / 60f) }
        val stride = Mesh.FLOATS_PER_VERTEX * 4
        val xs = ArrayList<Float>()
        var i = 0
        while (i < d.vertices.size) {
            val x = d.vertices[i]
            val y = d.vertices[i + 1]
            val z = d.vertices[i + 2]
            if (x != 0f || y != 0f || z != 0f) xs.add(x)
            i += stride
        }
        assertTrue("no live shards to measure", xs.size > 20)
        val spread = xs.max() - xs.min()
        println("shards spread %.2f m across after half a second".format(spread))
        assertTrue("every shard went to the same place: spread $spread m", spread > 1.0f)
    }

    @Test
    fun `shards expire, and the buffer empties itself`() {
        val d = Debris()
        d.burst(Vec3(0f, 1f, 0f), Vec3(0f, 1f, 0f), 25f)
        repeat(60 * 12) { d.step(1f / 60f) }
        assertEquals("shards should not live forever", 0, d.liveCount)
        for (f in d.vertices) assertEquals("a dead shard left geometry behind", 0f, f, 0f)
    }

    @Test
    fun `shards come to rest on the ground rather than falling through it`() {
        val d = Debris()
        d.burst(Vec3(0f, 2f, 0f), Vec3(0f, 1f, 0f), 20f)
        repeat(60 * 2) { d.step(1f / 60f) }
        var i = 1
        while (i < d.vertices.size) {
            assertTrue("a shard fell to y=${d.vertices[i]}", d.vertices[i] > -0.3f)
            i += Mesh.FLOATS_PER_VERTEX
        }
    }

    @Test
    fun `the budget is fixed however many bursts it takes`() {
        val d = Debris()
        repeat(40) { d.burst(Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f), 50f) }
        assertTrue("the buffer must not grow", d.liveCount <= Debris.MAX_SHARDS)
        assertEquals(Debris.MAX_SHARDS * 6, d.indices.size)
    }

    @Test
    fun `a crash throws debris of its own`() {
        val w = Wreck(
            CarMesh.build(),
            Wreck.Pose(0.0, 0.0, 0.0, 0.0, 28.0, 0.0),
            Wreck.Impact(0.0, 2.6, 0.5, 0.0, -1.0, 28.0)
        )
        assertTrue("hitting something should throw carbon", w.debris.liveCount > 0)
    }
}
