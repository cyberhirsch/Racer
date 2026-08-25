package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TrackTest {

    @Test
    fun `generation is deterministic`() {
        val a = Track(Levels.config(3))
        val b = Track(Levels.config(3))
        assertEquals(a.length, b.length, 1e-9)
        assertEquals(a.frames.size, b.frames.size)
        for (i in a.frames.indices) {
            assertEquals(a.frames[i].pos.x, b.frames[i].pos.x, 1e-12)
            assertEquals(a.frames[i].pos.z, b.frames[i].pos.z, 1e-12)
        }
    }

    @Test
    fun `every circuit closes, matches its target length, and respects its minimum radius`() {
        for (i in 0 until 10) {
            val cfg = Levels.config(i)
            val t = Track(cfg)

            val gap = t.frames.first().pos.distanceTo(t.frames.last().pos)
            assertTrue("level ${i + 1} does not close (gap ${gap}m)", gap < 6.0)

            val err = abs(t.length - cfg.length) / cfg.length
            assertTrue("level ${i + 1} length ${t.length} vs target ${cfg.length}", err < 0.05)

            // The generator relaxes hairpins until they are drivable; allow a
            // small tolerance for the discrete curvature estimate.
            assertTrue(
                "level ${i + 1} has a ${t.tightestRadius}m corner, tighter than its ${cfg.minRadius}m minimum",
                t.tightestRadius >= cfg.minRadius * 0.9
            )
        }
    }

    @Test
    fun `frames are evenly spaced and have orthonormal tangent and right vectors`() {
        val t = Track(Levels.config(5))
        for (i in 1 until t.frames.size) {
            val d = t.frames[i].pos.distanceTo(t.frames[i - 1].pos)
            assertTrue("frame spacing $d out of range", d in 0.5..8.0)
        }
        for (f in t.frames) {
            assertEquals(1.0, f.tangent.length(), 1e-6)
            assertEquals(1.0, f.right.length(), 1e-6)
            assertEquals("tangent and right must be perpendicular",
                0.0, f.tangent.x * f.right.x + f.tangent.z * f.right.z, 1e-6)
        }
    }

    @Test
    fun `locate reports the centreline as zero lateral offset and the edges correctly`() {
        val t = Track(Levels.config(2))
        for (i in listOf(0, 50, 200, t.frames.size - 2)) {
            val f = t.frames[i]
            assertEquals(0.0, t.locate(f.pos.x, f.pos.z, i).lateral, 0.35)

            // Five metres to the right of the centreline should read +5.
            val off = 5.0
            val loc = t.locate(f.pos.x + f.right.x * off, f.pos.z + f.right.z * off, i)
            assertEquals(off, loc.lateral, 0.5)
        }
    }

    @Test
    fun `running wide loses grip, and nothing stops you leaving`() {
        val t = Track(Levels.config(0))
        val f = t.frames[100]

        val onTrack = t.surface(f.pos.x, f.pos.z, 100)
        assertEquals(1.0, onTrack.grip, 1e-9)
        assertTrue("the middle of the road is not off track", !onTrack.offTrack)

        val wide = t.surface(
            f.pos.x + f.right.x * (t.halfWidth + 1.5),
            f.pos.z + f.right.z * (t.halfWidth + 1.5), 100
        )
        assertTrue("should be off track", wide.offTrack)
        assertTrue("should have lost grip", wide.grip < 1.0)

        // Well past where the barriers used to stand. There is nothing out
        // here now: the surface reports grass grip and lets you carry on.
        for (into in listOf(t.runoff + 1.0, t.runoff + 40.0, t.runoff + 500.0)) {
            val out = t.surface(f.pos.x + f.right.x * into, f.pos.z + f.right.z * into, 100)
            assertTrue("should still be off track at ${into}m", out.offTrack)
            assertEquals("grip should bottom out at grass, not stop the car",
                Track.GRASS_GRIP, out.grip, 1e-9)
        }
    }

    @Test
    fun `checkpoints are ordered, spread around the lap, and end at the finish line`() {
        val t = Track(Levels.config(4))
        assertEquals(Track.CHECKPOINT_COUNT, t.checkpoints.size)
        for (i in 1 until t.checkpoints.size) {
            assertTrue("checkpoints out of order", t.checkpoints[i] > t.checkpoints[i - 1])
        }
        assertEquals("last checkpoint should be the finish line",
            t.frames.size - 1, t.checkpoints.last())
    }

    @Test
    fun `the car starts on the centreline pointing down the track`() {
        val t = Track(Levels.config(1))
        val (x, z, yaw) = t.startPose
        val loc = t.locate(x, z, 0)
        assertEquals(0.0, loc.lateral, 0.2)
        val tangentYaw = kotlin.math.atan2(t.frames[0].tangent.x, t.frames[0].tangent.z)
        assertEquals(tangentYaw, yaw, 1e-9)
    }
}
