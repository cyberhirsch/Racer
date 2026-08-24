package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Round-trip test for the tilt-steering maths.
 *
 * Rather than guessing sensor values, this computes the gravity vector a phone
 * would actually report when held at a known roll in a known screen
 * orientation, feeds it through the same projection the game uses, and checks
 * the steering angle that comes back out.
 */
class TiltSteeringTest {

    /**
     * Gravity as Android reports it, in device coordinates, for a phone held
     * upright facing the player and rotated by `roll` about the screen normal.
     *
     * Android's gravity vector points away from the ground: a phone held
     * upright in its natural orientation reads roughly (0, +9.81, 0).
     */
    private fun gravityFor(roll: Double, pitch: Double = 0.0): Pair<Double, Double> {
        // Upright and unrolled, gravity is +y in device axes. Rolling the phone
        // about the screen normal rotates that vector within the screen plane;
        // pitching about the device x-axis tips it out of the plane, which
        // scales the in-plane part without turning it.
        val inPlane = cos(pitch)
        return Pair(-sin(roll) * 9.81 * inPlane, cos(roll) * 9.81 * inPlane)
    }

    private fun recovered(roll: Double, displayRotation: Int, pitch: Double = 0.0): Double {
        val t = TiltSteering()
        // Calibrate at the attitude the player is holding — exactly what the
        // game does when the countdown starts.
        val (nx, ny) = gravityFor(0.0)
        t.onGravity(nx, ny, displayRotation)
        t.calibrate()

        val (gx, gy) = gravityFor(roll, pitch)
        t.onGravity(gx, gy, displayRotation)
        return t.rollFromNeutral
    }

    @Test
    fun `recovers the roll angle exactly in every screen orientation`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            var worst = 0.0
            var prev = Double.NEGATIVE_INFINITY
            var monotonic = true
            var deg = -40
            while (deg <= 40) {
                val roll = deg * PI / 180
                val got = recovered(roll, rotation)
                worst = maxOf(worst, abs(got - roll))
                if (got < prev) monotonic = false
                prev = got
                deg += 10
            }
            println("rotation %3d: worst error %.4f deg, monotonic=%s"
                .format(rotation, worst * 180 / PI, monotonic))
            assertTrue("rotation $rotation was not monotonic", monotonic)
            assertEquals("rotation $rotation", 0.0, worst, 1e-9)
        }
    }

    /**
     * The reason for projecting gravity instead of reading one sensor axis:
     * tilting the phone toward or away from you must not fake a steering input.
     */
    @Test
    fun `is immune to pitching the phone toward or away from the player`() {
        for (pitchDeg in listOf(-40, -20, 0, 20, 40)) {
            val got = recovered(0.0, 90, pitchDeg * PI / 180)
            println("pitch %4d deg -> steering %.4f deg".format(pitchDeg, got * 180 / PI))
            assertEquals("pitch $pitchDeg", 0.0, got, 1e-9)
        }
    }

    @Test
    fun `applies a deadzone and saturates at full lock`() {
        val t = TiltSteering()
        t.onGravity(0.0, 9.81, 0)
        t.calibrate()

        // Inside the deadzone: no steering at all.
        val (sx, sy) = gravityFor(0.02)
        t.onGravity(sx, sy, 0)
        repeat(200) { t.update(1.0 / 60.0) }
        assertEquals("deadzone leaked", 0.0, t.steer, 1e-6)

        // Well past full lock: saturated, not beyond 1.
        val (bx, by) = gravityFor(1.2)
        t.onGravity(bx, by, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        assertEquals("did not reach full lock", 1.0, t.steer, 1e-3)
    }

    @Test
    fun `invert flips the steering direction`() {
        val t = TiltSteering()
        t.onGravity(0.0, 9.81, 0); t.calibrate()
        val (gx, gy) = gravityFor(0.4)
        t.onGravity(gx, gy, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        val normal = t.steer
        t.invert = true
        repeat(400) { t.update(1.0 / 60.0) }
        assertEquals(-normal, t.steer, 1e-3)
    }

    @Test
    fun `falls back to keyboard steering with no sensor`() {
        val t = TiltSteering()
        repeat(400) { t.update(1.0 / 60.0, keyboardSteer = 1.0) }
        assertEquals(1.0, t.steer, 1e-3)
    }
}
