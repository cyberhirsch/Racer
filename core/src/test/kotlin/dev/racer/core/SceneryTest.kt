package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Trees and rocks, and what happens when you drive into one.
 *
 * The dangerous mistake here is scenery on the racing line — a circuit that
 * cannot be driven because a tree is growing out of the road. Nothing about
 * the way it looks would tell you; the first sign would be a level nobody can
 * finish. So every obstacle on every level is checked against the whole
 * circuit, not just the stretch it was placed beside.
 */
class SceneryTest {

    @Test
    fun `every level has scenery, and none of it is on the circuit`() {
        for (level in 0 until Levels.BUILT_IN.size) {
            val t = Track(Levels.config(level))
            assertTrue("level ${level + 1} has no scenery at all", t.obstacles.size > 20)

            var closest = Double.MAX_VALUE
            for (o in t.obstacles) {
                // Against the nearest point of the whole circuit, which is the
                // one that matters when a track doubles back on itself.
                var nearest = Double.MAX_VALUE
                for (f in t.frames) {
                    nearest = minOf(nearest, hypot(f.pos.x - o.x, f.pos.z - o.z))
                }
                closest = minOf(closest, nearest - o.radius)
            }
            println("level %d: %d obstacles, nearest %.1f m from the centreline (run-off is %.1f)"
                .format(level + 1, t.obstacles.size, closest, t.runoff))
            assertTrue(
                "level ${level + 1} has scenery ${closest}m from the centreline, inside the run-off",
                closest > t.runoff
            )
        }
    }

    /** Driving into a tree at racing speed is the end of the race. */
    @Test
    fun `hitting something solid at speed ends the race`() {
        val g = Game()
        g.loadLevel(0)
        g.start()
        val t = g.track!!
        val tree = t.obstacles.first()

        // Placed just short of it, aimed at it, travelling quickly.
        val f = t.frames[t.locate(tree.x, tree.z, 0).index]
        g.vehicle.x = tree.x - f.right.x * 6.0
        g.vehicle.z = tree.z - f.right.z * 6.0
        g.vehicle.yaw = Math.atan2(f.right.x, f.right.z)
        g.vehicle.vx = 40.0

        var steps = 0
        while (g.state == Game.State.RACING && steps++ < 240) g.update(Game.STEP, Input())

        println("crashed after $steps steps: ${g.failReason}")
        assertEquals(Game.State.FAILED, g.state)
        assertTrue("the reason should say what was hit, was ${g.failReason}",
            g.failReason?.contains("HIT") == true)
    }

    /** Rolling into one at walking pace should not. */
    @Test
    fun `nudging something solid slowly just stops the car`() {
        val g = Game()
        g.loadLevel(0)
        g.start()
        val t = g.track!!
        val rock = t.obstacles.first()

        val f = t.frames[t.locate(rock.x, rock.z, 0).index]
        g.vehicle.x = rock.x - f.right.x * 3.0
        g.vehicle.z = rock.z - f.right.z * 3.0
        g.vehicle.yaw = Math.atan2(f.right.x, f.right.z)
        g.vehicle.vx = 2.0

        repeat(240) { if (g.state == Game.State.RACING) g.update(Game.STEP, Input()) }

        assertEquals("a slow nudge is not a crash", Game.State.RACING, g.state)
        assertTrue("the car should have been stopped by it", g.vehicle.speed < 3.0)
        val away = hypot(g.vehicle.x - rock.x, g.vehicle.z - rock.z)
        assertTrue("the car should be left outside the thing it hit, was ${away}m", away > rock.radius)
    }

    @Test
    fun `the same level always grows the same scenery`() {
        val a = Track(Levels.config(3)).obstacles
        val b = Track(Levels.config(3)).obstacles
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertTrue(abs(a[i].x - b[i].x) < 1e-9 && abs(a[i].z - b[i].z) < 1e-9)
        }
    }
}
