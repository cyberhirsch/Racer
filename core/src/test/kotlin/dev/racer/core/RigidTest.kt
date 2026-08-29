package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The rigid-body solver, checked against what mechanics says should happen
 * rather than against the numbers this implementation happens to produce.
 */
class RigidTest {

    private class Box(mass: Float, val half: Vec3) : Rigid.Movable {
        override val inertia = Rigid.Inertia(mass, half)
        override var position = Vec3(0f, 0f, 0f)
        override var orientation = Quat.identity()
        override var velocity = Vec3(0f, 0f, 0f)
        override var spin = Vec3(0f, 0f, 0f)
    }

    @Test
    fun `a long thin body is easier to spin about its long axis`() {
        val i = Rigid.Inertia(10f, Vec3(1.0f, 0.05f, 0.1f))
        assertTrue(
            "spinning a wing about its span should be the cheap direction: ${i.invLocal}",
            i.invLocal.x > i.invLocal.y && i.invLocal.x > i.invLocal.z
        )
    }

    @Test
    fun `an impulse through the centre of mass does not spin anything`() {
        val b = Box(10f, Vec3(0.5f, 0.5f, 0.5f))
        b.velocity = Vec3(0f, -4f, 0f)
        Rigid.resolve(b, at = b.position, normal = Vec3(0f, 1f, 0f), restitution = 0.5f, friction = 0f)
        assertEquals("it should bounce at half the speed it arrived", 2.0, b.velocity.y.toDouble(), 1e-4)
        assertEquals("a central blow must not spin it", 0.0, b.spin.length().toDouble(), 1e-6)
    }

    /** The whole reason for the rewrite: corners tip things over. */
    @Test
    fun `an impulse on a corner spins the body`() {
        val b = Box(10f, Vec3(0.5f, 0.1f, 0.5f))
        b.velocity = Vec3(0f, -5f, 0f)
        Rigid.resolve(
            b,
            at = b.position + Vec3(0.5f, -0.1f, 0f),
            normal = Vec3(0f, 1f, 0f),
            restitution = 0.4f, friction = 0.5f
        )
        println("landing on one edge gives spin ${b.spin}")
        assertTrue("landing on an edge should tip it over", abs(b.spin.z) > 0.5f)
    }

    @Test
    fun `a contact never pulls a body back down into the ground`() {
        val b = Box(10f, Vec3(0.5f, 0.5f, 0.5f))
        b.velocity = Vec3(0f, 3f, 0f)
        val j = Rigid.resolve(b, b.position, Vec3(0f, 1f, 0f), restitution = 0.5f, friction = 0.5f)
        assertEquals("a body already leaving the ground needs no impulse", 0f, j, 0f)
        assertEquals(3.0, b.velocity.y.toDouble(), 1e-6)
    }

    @Test
    fun `friction slows a sliding body but never reverses it`() {
        val b = Box(10f, Vec3(0.5f, 0.5f, 0.5f))
        b.velocity = Vec3(6f, -2f, 0f)
        Rigid.resolve(b, b.position + Vec3(0f, -0.5f, 0f), Vec3(0f, 1f, 0f), restitution = 0.2f, friction = 0.6f)
        println("sliding at 6 m/s, left at %.2f m/s".format(b.velocity.x))
        assertTrue("friction should have scrubbed some speed off", b.velocity.x < 6f)
        assertTrue("friction must never throw it backwards", b.velocity.x > 0f)
    }

    @Test
    fun `a heavy body shoulders a light one aside`() {
        val heavy = Box(200f, Vec3(0.4f, 0.4f, 0.4f))
        val light = Box(5f, Vec3(0.4f, 0.4f, 0.4f))
        heavy.velocity = Vec3(10f, 0f, 0f)
        light.position = Vec3(0.8f, 0f, 0f)
        Rigid.resolvePair(
            heavy, light, at = Vec3(0.4f, 0f, 0f), normal = Vec3(1f, 0f, 0f),
            restitution = 0.3f, friction = 0.3f
        )
        println("heavy left at %.2f m/s, light thrown to %.2f m/s".format(heavy.velocity.x, light.velocity.x))
        assertTrue("the light one should be thrown clear", light.velocity.x > heavy.velocity.x)
        assertTrue("the heavy one should barely notice", heavy.velocity.x > 7f)
    }

    /**
     * Momentum is the one thing a contact solver must not invent, and the
     * easiest thing to get wrong once leverage is in the denominator.
     */
    @Test
    fun `two bodies colliding conserve momentum`() {
        val a = Box(30f, Vec3(0.3f, 0.3f, 0.3f))
        val b = Box(12f, Vec3(0.3f, 0.3f, 0.3f))
        a.velocity = Vec3(8f, 1f, -2f)
        b.velocity = Vec3(-3f, 0f, 1f)
        b.position = Vec3(0.6f, 0f, 0f)
        fun momentum() = a.velocity * 30f + b.velocity * 12f
        val before = momentum()
        Rigid.resolvePair(a, b, Vec3(0.3f, 0f, 0f), Vec3(1f, 0f, 0f), restitution = 0.4f, friction = 0.4f)
        val after = momentum()
        assertEquals("x momentum", before.x.toDouble(), after.x.toDouble(), 1e-2)
        assertEquals("y momentum", before.y.toDouble(), after.y.toDouble(), 1e-2)
        assertEquals("z momentum", before.z.toDouble(), after.z.toDouble(), 1e-2)
    }

    @Test
    fun `a contact never adds energy`() {
        val b = Box(20f, Vec3(0.4f, 0.2f, 0.6f))
        b.orientation = Quat.axisAngle(Vec3(1f, 1f, 0.3f).normalized(), 0.7f)
        b.velocity = Vec3(2f, -7f, 1f)
        b.spin = Vec3(0.5f, -1f, 2f)
        fun energy(): Float {
            val local = b.orientation.inverse().rotate(b.spin)
            val ix = 1f / b.inertia.invLocal.x
            val iy = 1f / b.inertia.invLocal.y
            val iz = 1f / b.inertia.invLocal.z
            return 0.5f * 20f * b.velocity.dot(b.velocity) +
                0.5f * (ix * local.x * local.x + iy * local.y * local.y + iz * local.z * local.z)
        }
        val before = energy()
        Rigid.resolve(b, b.position + Vec3(0.2f, -0.3f, 0.1f), Vec3(0f, 1f, 0f), restitution = 0.5f, friction = 0.5f)
        val after = energy()
        println("energy %.1f J before the bounce, %.1f J after".format(before, after))
        assertTrue("a bounce with restitution below one must lose energy", after <= before + 1e-2f)
    }

    @Test
    fun `the support distance matches the box however it is turned`() {
        val half = Vec3(1.0f, 0.05f, 0.2f)
        val flat = Rigid.support(half, Quat.identity(), Vec3(0f, -1f, 0f))
        val onEnd = Rigid.support(half, Quat.axisAngle(Vec3(0f, 0f, 1f), (Math.PI / 2).toFloat()), Vec3(0f, -1f, 0f))
        println("a wing reaches %.2f m below lying flat, %.2f m stood on its endplate".format(flat, onEnd))
        assertEquals(0.05, flat.toDouble(), 1e-4)
        assertEquals(1.0, onEnd.toDouble(), 1e-4)
    }

    @Test
    fun `a box has eight corners, all of them on it`() {
        val b = Box(10f, Vec3(0.5f, 0.25f, 1f))
        b.position = Vec3(3f, 2f, -1f)
        b.orientation = Quat.axisAngle(Vec3(0f, 1f, 0f), 0.9f)
        val out = Array(8) { Vec3(0f, 0f, 0f) }
        Rigid.corners(b, b.half, Vec3(0f, 0f, 0f), out)
        assertEquals("corners should be distinct", 8, out.map { "${it.x},${it.y},${it.z}" }.toSet().size)
        val expected = b.half.length()
        for (c in out) {
            assertEquals("every corner is a half-diagonal from the centre", expected.toDouble(),
                (c - b.position).length().toDouble(), 1e-4)
        }
    }
}
