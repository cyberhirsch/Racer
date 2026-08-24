package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The car is supposed to behave like a real Formula 1 car, so these tests pin
 * the headline performance figures against published real-world numbers. If a
 * change to the model breaks one of these, the car no longer drives like an F1
 * car and the levels' fuel budgets are invalid too.
 */
class VehicleTest {
    private val h = 1.0 / 120.0

    private fun run(seconds: Double, input: Input, setup: (Vehicle) -> Unit = {}): Vehicle {
        val v = Vehicle()
        v.fuel = 100.0
        setup(v)
        repeat((seconds / h).toInt()) { v.step(h, input) }
        return v
    }

    @Test
    fun `accelerates like an F1 car`() {
        // Real: 0-100 km/h in ~2.6 s, 0-200 in ~4.5 s.
        val v = Vehicle().apply { fuel = 100.0 }
        var t = 0.0
        var to100 = -1.0
        var to200 = -1.0
        while (t < 15) {
            v.step(h, Input(throttle = 1.0))
            t += h
            if (to100 < 0 && v.speed * 3.6 >= 100) to100 = t
            if (to200 < 0 && v.speed * 3.6 >= 200) to200 = t
        }
        println("0-100: %.2fs  0-200: %.2fs".format(to100, to200))
        assertTrue("0-100 km/h was ${to100}s", to100 in 1.8..3.6)
        assertTrue("0-200 km/h was ${to200}s", to200 in 3.5..5.8)
    }

    @Test
    fun `reaches a realistic top speed`() {
        val v = run(60.0, Input(throttle = 1.0))
        val kmh = v.speed * 3.6
        println("top speed: %.1f km/h in gear %d".format(kmh, v.gear + 1))
        assertTrue("top speed was $kmh km/h", kmh in 300.0..360.0)
        assertEquals("should be in top gear", Spec.GEARS.size - 1, v.gear)
    }

    @Test
    fun `brakes like an F1 car`() {
        // Real: 200-0 km/h in roughly 2.6-3.0 s.
        val v = Vehicle().apply { fuel = 100.0; vx = 200 / 3.6; gear = 6 }
        var t = 0.0
        while (abs(v.vx) > 1.0 && t < 15) { v.step(h, Input(brake = 1.0)); t += h }
        println("200-0 braking: %.2fs".format(t))
        assertTrue("200-0 took ${t}s", t in 2.2..3.6)
    }

    @Test
    fun `generates realistic cornering grip`() {
        val v = Vehicle().apply { fuel = 100.0; vx = 55.0 }
        repeat(600) { v.step(h, Input(throttle = 0.55, steer = 1.0)) }
        val latG = abs(v.yawRate * v.vx / 9.81)
        val radius = abs(v.vx / v.yawRate)
        println("at 55 m/s: %.2f g, radius %.0f m".format(latG, radius))
        assertTrue("lateral grip was $latG g", latG in 2.0..5.0)
    }

    /**
     * Full lock at speed asks for far more grip than exists. The car should
     * spin — but the simulation must stay finite, which it did not before the
     * steering limit was added.
     */
    @Test
    fun `stays finite when provoked into a spin`() {
        val v = Vehicle().apply { fuel = 1000.0; vx = 80.0 }
        repeat(4000) { v.step(h, Input(throttle = 1.0, steer = 1.0)) }
        assertTrue("vx went non-finite", v.vx.isFinite())
        assertTrue("vy went non-finite", v.vy.isFinite())
        assertTrue("yawRate went non-finite", v.yawRate.isFinite())
        assertTrue("position went non-finite", v.x.isFinite() && v.z.isFinite())
        assertTrue("yaw rate exploded: ${v.yawRate}", abs(v.yawRate) <= 4.6)
    }

    @Test
    fun `settles at rest instead of drifting`() {
        val v = Vehicle().apply { fuel = 100.0 }
        repeat(2000) { i -> v.step(h, Input(steer = kotlin.math.sin(i / 50.0))) }
        assertEquals(0.0, v.x, 1e-6)
        assertEquals(0.0, v.z, 1e-6)
        assertEquals(0.0, v.yaw, 1e-6)
        assertFalse(v.offTrack)
    }

    /**
     * The whole game hinges on this: if full throttle cost the same as part
     * throttle, managing fuel would be meaningless.
     */
    @Test
    fun `part throttle uses much less fuel than full throttle`() {
        val flat = run(60.0, Input(throttle = 1.0)).fuelUsed
        val lift = run(60.0, Input(throttle = 0.45)).fuelUsed
        println("60s of fuel: full %.2f kg vs 45%% throttle %.2f kg".format(flat, lift))
        assertTrue("full throttle used $flat kg, part throttle $lift kg", lift < flat * 0.75)
    }

    @Test
    fun `engine torque peaks in the right place and dies past the redline`() {
        assertTrue(engineTorque(Spec.TORQUE_PEAK_RPM) >= engineTorque(6000.0))
        assertTrue(engineTorque(Spec.TORQUE_PEAK_RPM) >= engineTorque(14000.0))
        assertEquals(0.0, engineTorque(Spec.REDLINE + 100), 0.0)
    }

    @Test
    fun `a barrier impact kills speed rather than adding it`() {
        val v = Vehicle().apply { fuel = 100.0; vx = 50.0 }
        v.step(h, Input())
        val before = v.speed
        // Wall on the right, normal pointing back left toward the centreline.
        v.collide(-1.0, 0.0, 0.1)
        val after = kotlin.math.hypot(v.vx, v.vy)
        println("impact: %.1f -> %.1f m/s".format(before, after))
        assertTrue("speed rose after impact", after <= before + 1e-6)
    }
}
