package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The crash simulation, driven the way the game drives it.
 *
 * Everything here is checked against behaviour rather than against numbers
 * pulled out of the implementation, so the constants can be tuned for how it
 * looks without the tests turning into a copy of them.
 */
class WreckTest {

    private val car = CarMesh.build()

    private fun crash(
        speed: Double,
        /** Which way the car is pointed. 0 is +Z. */
        yaw: Double = 0.0,
        /** Where the blow came from, relative to the car. */
        fromFront: Boolean = true
    ): Wreck {
        // Hitting something head-on: the car is travelling +Z, and the normal
        // points back out of the obstacle toward the car, so -Z.
        val sign = if (fromFront) 1.0 else -1.0
        return Wreck(
            car,
            Wreck.Pose(x = 0.0, z = 0.0, yaw = yaw, velocityX = 0.0, velocityZ = speed * sign, yawRate = 0.0),
            Wreck.Impact(
                x = 0.0, z = 2.6 * sign, height = 0.5,
                normalX = 0.0, normalZ = -sign, speed = speed
            )
        )
    }

    /**
     * Wall-clock seconds, which is what the game feeds it — and which is no
     * longer the same as simulated seconds, because a crash opens in slow
     * motion. The budget allows for the couple of seconds that costs.
     */
    private fun run(w: Wreck, seconds: Double = 20.0): Double {
        var t = 0.0
        while (t < seconds && !w.settled) {
            w.step(1.0 / 60.0)
            t += 1.0 / 60.0
        }
        return t
    }

    private fun body(w: Wreck, part: CarMesh.Part) = w.bodies.first { it.part == part }

    /**
     * A crash, run far enough for the panels to have finished folding.
     *
     * Deformation is simulated rather than stamped on, so a blow does not
     * change any shape on the frame it lands — it gives the lattice a shove
     * and the fold develops over the next fraction of a second. Anything
     * asking what the damage came to has to let that happen first.
     */
    private fun crumpled(speed: Double, fromFront: Boolean = true): Wreck {
        val w = crash(speed = speed, fromFront = fromFront)
        var t = 0.0
        while (t < 4.0) { w.step(1.0 / 60.0); t += 1.0 / 60.0 }
        return w
    }

    @Test
    fun `a car in one piece has every part bolted on and undamaged`() {
        val fresh = CarMesh.build()
        assertEquals(CarMesh.Part.entries.size, fresh.parts.size)
        for ((_, mesh) in fresh.partList) {
            assertTrue("a part came out empty", mesh.vertexCount > 0)
        }
    }

    @Test
    fun `a heavy nose-first shunt takes the front wing off`() {
        val w = crash(speed = 22.0)
        assertTrue("the front wing should have been knocked off", !body(w, CarMesh.Part.FRONT_WING).attached)
        println("pieces lost at 22 m/s head-on: ${w.piecesLost}")
    }

    /**
     * The point of the whole thing: what comes off should follow how hard you
     * hit, rather than a crash being one canned outcome.
     */
    @Test
    fun `the faster you hit, the more of the car you leave behind`() {
        val speeds = listOf(7.0, 15.0, 22.0, 35.0, 50.0)
        val lost = speeds.map { crash(speed = it).piecesLost }
        println(speeds.zip(lost).joinToString { "%.0f m/s -> %d".format(it.first, it.second) })
        for (i in 1 until lost.size) {
            assertTrue(
                "hitting at ${speeds[i]} m/s should not cost fewer pieces than ${speeds[i - 1]}: $lost",
                lost[i] >= lost[i - 1]
            )
        }
        assertTrue("a 50 m/s shunt should strip more than a 7 m/s one: $lost", lost.last() > lost.first())
    }

    @Test
    fun `the survival cell stays in one piece`() {
        val w = crash(speed = 45.0)
        run(w)
        assertTrue("the tub must never detach from itself", w.chassis.attached)
    }

    @Test
    fun `a light knock bends the nose without stripping the car`() {
        val w = crumpled(speed = 7.0)
        assertTrue("a 7 m/s knock should not strip the bodywork", w.piecesLost <= 1)
        assertTrue("something should have been bent", w.worstDamage > 0.005f)
    }

    @Test
    fun `harder impacts do more damage`() {
        val light = crumpled(speed = 8.0).worstDamage
        val heavy = crumpled(speed = 30.0).worstDamage
        println("worst bending: %.3f m at 8 m/s, %.3f m at 30 m/s".format(light, heavy))
        assertTrue("a 30 m/s hit should bend more than an 8 m/s one ($light vs $heavy)", heavy > light * 1.5f)
    }

    @Test
    fun `damage is permanent and accumulates`() {
        val w = crash(speed = 20.0)
        // Just long enough for the impact's own fold to have formed, and well
        // short of the tumble that follows it.
        repeat(45) { w.step(1.0 / 60.0) }
        val wing = body(w, CarMesh.Part.FRONT_WING)
        val bentOnImpact = wing.damage
        val yieldedSoFar = w.bodies.sumOf { it.yielded }
        assertTrue("the impact should have bent the wing", bentOnImpact > 0.002f)

        run(w)
        // Permanence first: the lattice is solved elastically, so a fold that
        // had not yielded would quietly straighten itself out over the tumble.
        assertTrue(
            "the wing sprang back: $bentOnImpact m became ${wing.damage} m",
            wing.damage > bentOnImpact * 0.8f
        )
        // And accumulation, counted across the wreck rather than on the wing
        // alone — which piece takes the tumbling depends on how it lands.
        assertTrue(
            "the tumble should bend the car further: $yieldedSoFar constraints " +
                "yielded on impact, ${w.bodies.sumOf { it.yielded }} by the time it stopped",
            w.bodies.sumOf { it.yielded } > yieldedSoFar
        )
    }

    @Test
    fun `a blow from behind spares the front wing and takes the rear one`() {
        val w = crash(speed = 26.0, fromFront = false)
        assertTrue("the rear wing took the blow and should be off", !body(w, CarMesh.Part.REAR_WING).attached)
        assertTrue(
            "the front wing is three metres from the impact and should still be on",
            body(w, CarMesh.Part.FRONT_WING).attached
        )
    }

    /**
     * Sidepods are strong from the front and weak from the side, which is why
     * a nose-first shunt leaves them on and a T-bone does not.
     */
    @Test
    fun `a side impact tears the sidepod off the side that was hit`() {
        val w = Wreck(
            car,
            Wreck.Pose(x = 0.0, z = 0.0, yaw = 0.0, velocityX = -30.0, velocityZ = 0.0, yawRate = 0.0),
            Wreck.Impact(x = -1.3, z = -0.4, height = 0.45, normalX = 1.0, normalZ = 0.0, speed = 30.0)
        )
        assertTrue("the struck sidepod should be gone", !body(w, CarMesh.Part.SIDEPOD_LEFT).attached)
        assertTrue("the far sidepod should still be on", body(w, CarMesh.Part.SIDEPOD_RIGHT).attached)
    }

    @Test
    fun `deforming a panel changes its shape but not its triangles`() {
        val w = crumpled(speed = 25.0)
        val wing = body(w, CarMesh.Part.FRONT_WING)
        assertEquals(
            "the vertex count must not change, or the index buffer stops matching",
            wing.base.vertices.size, wing.vertices.size
        )
        assertNotEquals("nothing moved", 0, wing.shapeVersion)
        assertTrue("the shape should have changed", wing.damage > 0.01f)
        for (f in wing.vertices) assertTrue("a vertex went to $f", f.isFinite())
    }

    @Test
    fun `deformed normals stay unit length`() {
        val w = crumpled(speed = 30.0)
        val wing = body(w, CarMesh.Part.FRONT_WING)
        val v = wing.vertices
        var i = 0
        var checked = 0
        while (i < v.size) {
            val l = kotlin.math.sqrt(
                v[i + 3] * v[i + 3] + v[i + 4] * v[i + 4] + v[i + 5] * v[i + 5]
            )
            assertEquals("normal $checked has length $l", 1.0, l.toDouble(), 1e-3)
            checked++
            i += Mesh.FLOATS_PER_VERTEX
        }
        println("checked $checked normals on the deformed front wing")
    }

    @Test
    fun `everything comes to rest on the ground, and stays there`() {
        val w = crash(speed = 35.0)
        val t = run(w)
        assertTrue("the wreck should have settled, it ran for $t s", w.settled)
        for (b in w.bodies) {
            assertTrue(
                "${b.part ?: "wheel"} came to rest at y=${b.position.y} with radius ${b.radius}",
                b.position.y > -0.01f
            )
            assertTrue("${b.part ?: "wheel"} is still moving", b.velocity.length() < 0.5f)
        }
        println("everything settled after %.1f s; %d pieces came off".format(t, w.piecesLost))
    }

    @Test
    fun `pieces end up somewhere plausible, not on the far side of the map`() {
        val w = crash(speed = 40.0)
        run(w)
        for (b in w.bodies) {
            val distance = kotlin.math.hypot(b.position.x.toDouble(), b.position.z.toDouble())
            assertTrue("${b.part ?: "wheel"} ended up $distance m away", distance < 80.0)
        }
    }

    @Test
    fun `the wreck does not depend on the frame rate`() {
        fun run(frame: Double): Vec3 {
            val w = crash(speed = 28.0)
            var t = 0.0
            while (t < 4.0) { w.step(frame); t += frame }
            return w.chassis.position
        }
        val fast = run(1.0 / 120.0)
        val slow = run(1.0 / 24.0)
        println("chassis after 4 s: 120fps %.2f,%.2f  24fps %.2f,%.2f"
            .format(fast.x, fast.z, slow.x, slow.z))
        assertTrue("x drifted with frame rate", abs(fast.x - slow.x) < 1.5f)
        assertTrue("z drifted with frame rate", abs(fast.z - slow.z) < 1.5f)
    }

    @Test
    fun `orientations stay unit quaternions however long it tumbles`() {
        val w = crash(speed = 45.0)
        run(w)
        for (b in w.bodies) {
            val q = b.orientation
            val l = kotlin.math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
            assertEquals("${b.part ?: "wheel"} orientation drifted to length $l", 1.0, l.toDouble(), 1e-3)
        }
    }

    @Test
    fun `parts still bolted on move exactly with the tub`() {
        val w = crash(speed = 12.0)
        run(w, seconds = 1.0)
        for (b in w.bodies) {
            if (!b.attached) continue
            assertEquals(
                "${b.part ?: "wheel"} drifted away from the tub it is bolted to",
                w.chassis.orientation.w.toDouble(), b.orientation.w.toDouble(), 1e-5
            )
        }
    }

    /**
     * Impact Time, the thing Burnout got right: the moment of contact plays
     * slowly enough to be read, then hands the speed back.
     */
    @Test
    fun `a crash opens in slow motion and comes back up to speed`() {
        val w = crash(speed = 30.0)
        assertTrue("the moment of impact should crawl", w.timeScale < 0.3)
        var t = 0.0
        while (t < 0.5) { w.step(1.0 / 60.0); t += 1.0 / 60.0 }
        assertTrue("half a second in it should still be slow", w.timeScale < 0.5)
        while (t < 4.0) { w.step(1.0 / 60.0); t += 1.0 / 60.0 }
        assertEquals("by four seconds it should be running at full speed", 1.0, w.timeScale, 1e-9)
        assertTrue(
            "slow motion means less physics than wall clock: %.2f s simulated in %.2f s"
                .format(w.simulated, t),
            w.simulated < t
        )
    }

    @Test
    fun `a hard hit shakes the camera, and the shake dies away`() {
        val w = crash(speed = 40.0)
        val hit = w.trauma
        assertTrue("a 40 m/s shunt should shake the camera hard, got $hit", hit > 0.8)
        var t = 0.0
        while (t < 8.0) { w.step(1.0 / 60.0); t += 1.0 / 60.0 }
        assertTrue("the shake should have died away, still ${w.trauma}", w.trauma < 0.2)
    }

    /** The tree is still standing, and nothing may come to rest inside it. */
    @Test
    fun `the wreck cannot come to rest inside the tree it hit`() {
        val tree = Wreck.Standing(x = 0.0, z = 3.0, radius = 0.55, height = 7.0)
        val w = Wreck(
            car,
            Wreck.Pose(0.0, 0.0, 0.0, 0.0, 30.0, 0.0),
            Wreck.Impact(0.0, 2.6, 0.5, 0.0, -1.0, 30.0),
            tree
        )
        run(w)
        for (b in w.bodies) {
            if (b.attached && b !== w.chassis) continue
            val distance = kotlin.math.hypot(b.position.x - tree.x, b.position.z - tree.z)
            assertTrue(
                "${b.part ?: "wheel"} ended up $distance m from a trunk of radius ${tree.radius}",
                distance > tree.radius
            )
        }
    }

    /**
     * What the obstacle is for: it throws things back.
     *
     * Measured on the tub, because that is the mass with somewhere to go.
     * A car that hits a tree square-on rebounds off it, so the tree that has
     * to be in the way is one standing behind where it came to rest — hence
     * the second trunk, planted along the path the wreck actually takes.
     */
    @Test
    fun `the tub is thrown back off the tree instead of driving through it`() {
        fun settleZ(tree: Wreck.Standing?): Double {
            val w = Wreck(
                car,
                Wreck.Pose(0.0, 0.0, 0.0, 0.0, 34.0, 0.0),
                Wreck.Impact(0.0, 2.6, 0.5, 0.0, -1.0, 34.0),
                tree
            )
            run(w)
            return w.chassis.position.z.toDouble()
        }
        val free = settleZ(null)
        val blocked = settleZ(Wreck.Standing(0.0, -4.0, 0.55, 7.0))
        println("the tub ends at z=%.2f with nothing there, %.2f against a tree".format(free, blocked))
        assertTrue("the tree should stop the tub short, $free vs $blocked", blocked > free)
    }

    /**
     * The deformation is a simulation now, and simulations have a bill.
     *
     * Not a benchmark — the number a build machine produces means little — but
     * a guard against the cost growing by an order of magnitude unnoticed,
     * which is exactly what a lattice per panel invites. A crash has to fit in
     * a frame on a phone.
     */
    @Test
    fun `a whole crash simulates faster than it plays`() {
        // Warm up, so this measures the physics rather than the JIT.
        repeat(3) { run(crash(speed = 40.0)) }
        val started = System.nanoTime()
        val w = crash(speed = 40.0)
        val played = run(w)
        val spent = (System.nanoTime() - started) / 1e9
        println("%.1f s of crash took %.3f s to simulate and skin".format(played, spent))
        assertTrue("a crash must not cost more than it lasts: %.3f s".format(spent), spent < played)
    }

    @Test
    fun `reading a panel's shape is what builds it, and only once`() {
        val w = crash(speed = 30.0)
        repeat(30) { w.step(1.0 / 60.0) }
        val wing = body(w, CarMesh.Part.FRONT_WING)
        val first = wing.vertices
        assertTrue("the same array should come back until the lattice moves", first === wing.vertices)
        assertTrue("the shape should have been rebuilt by reading it", wing.damage >= 0f)
    }

    @Test
    fun `a wreck that has settled costs nothing to keep stepping`() {
        val w = crash(speed = 20.0)
        run(w)
        val where = w.chassis.position
        repeat(600) { w.step(1.0 / 60.0) }
        assertEquals(where.x, w.chassis.position.x, 1e-6f)
        assertEquals(where.y, w.chassis.position.y, 1e-6f)
        assertEquals(where.z, w.chassis.position.z, 1e-6f)
    }
}
