package dev.racer.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * You can leave the circuit; you cannot leave the world.
 *
 * With the barriers gone, nothing stopped a car driven straight off the side
 * except the ground running out. The deep grass has to do that job on its own,
 * and it has to do it by slowing the car rather than by standing in its way —
 * so this drives out there at full throttle and watches where it ends up.
 */
class WorldEdgeTest {

    /** Drive flat out, aimed away from the circuit, and report how far it got. */
    private fun runAside(seconds: Double): Pair<Double, Double> {
        val g = Game()
        g.loadLevel(0)
        g.start()
        val t = g.track!!

        // Point the car across the track and hold it there.
        g.vehicle.yaw = t.headingAt(0) + Math.PI / 2
        var lateral = 0.0
        var steps = 0
        while (steps++ < (seconds / Game.STEP).toInt()) {
            g.update(Game.STEP, Input(throttle = 1.0, brake = 0.0, steer = 0.0))
            lateral = abs(t.locate(g.vehicle.x, g.vehicle.z, 0).lateral)
        }
        return lateral to g.vehicle.speed
    }

    @Test
    fun `a car driven straight off the side is stopped by the ground, not by a wall`() {
        val t = Track(Levels.config(0))
        val (lateral, speed) = runAside(40.0)
        println("40 s aimed off the circuit: %.0f m out (deep grass at %.0f, edge at %.0f), %.1f m/s"
            .format(lateral, t.deepGrass, t.edge, speed))

        assertTrue("should have got well off the circuit — leaving it is allowed",
            lateral > t.runoff)
        assertTrue("should never reach the end of the ground, was ${lateral}m out",
            lateral <= t.edge + 0.01)
        assertTrue("should have been slowed to a crawl out there, was ${speed} m/s",
            speed < 8.0)
    }

    @Test
    fun `the deep grass is far enough out that the circuit is unaffected`() {
        val t = Track(Levels.config(0))
        val f = t.frames[50]
        val onTrack = t.surface(f.pos.x, f.pos.z, 50)
        assertTrue("the road itself must be untouched by any of this", onTrack.beyond == 0.0)

        val wide = t.surface(
            f.pos.x + f.right.x * (t.runoff + 5), f.pos.z + f.right.z * (t.runoff + 5), 50
        )
        assertTrue("a normal off-track excursion must not be treated as leaving the world",
            wide.beyond == 0.0)
    }
}
