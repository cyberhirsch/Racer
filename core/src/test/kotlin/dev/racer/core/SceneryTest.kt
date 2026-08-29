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

    /**
     * And it leaves a wreck behind, which goes on playing out after the race
     * has been declared over.
     */
    @Test
    fun `a crash leaves a wreck that tumbles and comes to rest`() {
        val g = Game()
        g.loadLevel(0)
        g.start()
        val t = g.track!!
        val tree = t.obstacles.first()
        val f = t.frames[t.locate(tree.x, tree.z, 0).index]
        g.vehicle.x = tree.x - f.right.x * 6.0
        g.vehicle.z = tree.z - f.right.z * 6.0
        g.vehicle.yaw = Math.atan2(f.right.x, f.right.z)
        g.vehicle.vx = 45.0

        var steps = 0
        while (g.state == Game.State.RACING && steps++ < 240) g.update(Game.STEP, Input())
        assertEquals(Game.State.FAILED, g.state)

        val w = g.wreck
        assertTrue("a crash should leave a wreck", w != null)
        w!!
        assertTrue("something should have come off at 45 m/s", w.piecesLost > 0)
        // The panels crumple over the frames after the blow rather than on it,
        // so the bending is worth reading only once the lattices have moved.
        repeat(600) { g.update(Game.STEP, Input()) }
        // Folding, with the pieces' bodily movement taken out of it, so a few
        // centimetres here is a few centimetres of actual crumpled bodywork.
        assertTrue("the car should be bent, worst is ${w.worstDamage} m", w.worstDamage > 0.02f)
        assertTrue(
            "the damage should be permanent, not a panel caught mid-wobble",
            w.bodies.sumOf { it.yielded } > 0
        )

        // The race is over, but the crash is not: the game keeps stepping it.
        var t2 = 0.0
        while (t2 < 15.0 && !w.settled) { g.update(1.0 / 60.0, Input()); t2 += 1.0 / 60.0 }
        assertTrue("the wreck never settled", w.settled)
        println("wreck: %d pieces off, worst bend %.2f m, settled in %.1f s"
            .format(w.piecesLost, w.worstDamage, t2))

        // The camera has to be looking at the wreck, not at the tree it left.
        val cam = g.camera(1.0 / 60.0, 2.0f)
        val toWreck = hypot(
            cam.target.x - w.chassis.position.x.toDouble(),
            cam.target.z - w.chassis.position.z.toDouble()
        )
        assertTrue("the camera is looking $toWreck m away from the wreck", toWreck < 1.0)

        // And starting again clears it.
        g.retry()
        assertTrue("a new race must not start in the old wreck", g.wreck == null)
    }

    /**
     * Every obstacle is solid, wherever it stands on the circuit.
     *
     * The one that shipped: obstacles were filed into frame buckets using the
     * hinted [Track.locate], which only searches forty frames either side of
     * the hint it is given — and it was given nought. So the entire circuit's
     * scenery landed in the eighty frames around the start line, and anywhere
     * else the trees were drawn and the physics could not see them. You could
     * drive through the lot at two hundred, which is exactly what was reported.
     */
    @Test
    fun `an obstacle is solid wherever it stands, not only near the start line`() {
        for (level in 0 until Levels.BUILT_IN.size) {
            val t = Track(Levels.config(level))
            // A spread all the way round, so a bug that only works near the
            // start line cannot pass.
            val sample = (0 until 40).map { t.obstacles[it * t.obstacles.size / 40] }
            for (o in sample) {
                // The hint a car alongside it would be carrying, found the
                // slow honest way so the test cannot inherit the bug.
                var hint = 0
                var best = Double.MAX_VALUE
                for (i in t.frames.indices) {
                    val d = hypot(t.frames[i].pos.x - o.x, t.frames[i].pos.z - o.z)
                    if (d < best) { best = d; hint = i }
                }
                assertTrue(
                    "level ${level + 1}: the obstacle at ${o.x.toInt()},${o.z.toInt()} " +
                        "(frame $hint of ${t.frames.size}) is invisible to the physics",
                    t.obstacleNear(o.x, o.z, hint, 1.6) != null
                )
            }
        }
        println("obstacles all round every circuit are solid")
    }

    /**
     * Leaving the circuit at racing speed should end badly, nearly always.
     *
     * Not always: getting away with one is worth having. But when this read
     * nineteen per cent — four excursions in five touching nothing at all —
     * the game was effectively uncrashable, and that is what "I didn't see
     * collisions" meant.
     */
    @Test
    fun `leaving the circuit at racing speed nearly always ends in a crash`() {
        var crashed = 0
        var tried = 0
        for (level in 0 until Levels.BUILT_IN.size) {
            for (lap in listOf(200, 700, 1260)) {
                for (steer in listOf(-0.65, -0.4, 0.4, 0.65)) {
                    val g = Game(); g.loadLevel(level); g.start()
                    var s = 0.0
                    var hint = 0
                    repeat(lap) {
                        if (g.state != Game.State.RACING) return@repeat
                        val (inp, h) = Autopilot.input(g.vehicle, g.track!!, hint, s, Game.STEP)
                        hint = h; s = inp.steer; g.update(Game.STEP, inp)
                    }
                    if (g.state != Game.State.RACING) continue
                    // Turn it off the circuit, then straight on: a driver who
                    // has lost it, not one holding lock in a circle.
                    var steps = 0
                    while (g.state == Game.State.RACING && steps++ < 1200) {
                        g.update(Game.STEP, Input(throttle = 1.0, steer = if (steps < 48) steer else 0.0))
                    }
                    tried++
                    if (g.state == Game.State.FAILED) crashed++
                }
            }
        }
        val rate = 100.0 * crashed / tried
        println("%d of %d excursions ended in a crash (%.0f%%)".format(crashed, tried, rate))
        assertTrue("only %.0f%% of excursions hit anything".format(rate), rate > 70.0)
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
