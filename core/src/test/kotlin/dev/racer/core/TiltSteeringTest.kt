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
     * upright facing the player and rolled **clockwise** (from the player's
     * point of view) by `roll`.
     *
     * The one physical convention this whole file rests on, stated once so it
     * can be checked by eye:
     *
     *   Android's gravity vector points *away* from the ground, so a phone held
     *   upright in its natural orientation reads about (0, +9.81, 0). Roll it
     *   clockwise by t and that vector moves to (+9.81 sin t, +9.81 cos t) in
     *   device axes — the vector appears to swing to the right of the screen,
     *   because the screen has turned left underneath it.
     *
     * Getting this backwards is exactly the bug that shipped: the steering came
     * out mirrored, and the test agreed with it, because the test asserted the
     * code against the same assumption. Pitching about the device x-axis tips
     * gravity out of the screen plane, scaling the in-plane part without
     * turning it.
     */
    private fun gravityFor(roll: Double, pitch: Double = 0.0): Pair<Double, Double> {
        val inPlane = cos(pitch)
        return Pair(sin(roll) * 9.81 * inPlane, cos(roll) * 9.81 * inPlane)
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

    /**
     * A wheel turned clockwise steers right. This is the assertion that was
     * wrong on the device, so it is stated in plain terms rather than left
     * implicit in a round-trip.
     */
    @Test
    fun `rolling the phone clockwise steers right`() {
        val t = TiltSteering()
        val (nx, ny) = gravityFor(0.0)
        t.onGravity(nx, ny, 0); t.calibrate()

        val (cx, cy) = gravityFor(0.35)          // 20 degrees clockwise
        t.onGravity(cx, cy, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        println("clockwise 20 deg -> steer %+.3f".format(t.steer))
        assertTrue("clockwise should steer right, got ${t.steer}", t.steer > 0.1)

        val (ax, ay) = gravityFor(-0.35)         // 20 degrees anticlockwise
        t.onGravity(ax, ay, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        println("anticlockwise 20 deg -> steer %+.3f".format(t.steer))
        assertTrue("anticlockwise should steer left, got ${t.steer}", t.steer < -0.1)
    }

    /**
     * The camera has to turn back by the amount the phone turned, so the view
     * roll opposes the phone's rotation. The horizon is also not a preference:
     * inverting the steering must not flip it.
     */
    @Test
    fun `the view roll opposes the phone and ignores the invert setting`() {
        val t = TiltSteering()
        val (nx, ny) = gravityFor(0.0)
        t.onGravity(nx, ny, 0); t.calibrate()

        val (gx, gy) = gravityFor(0.4)
        t.onGravity(gx, gy, 0)
        assertEquals(0.4, t.rollFromNeutral, 1e-9)
        assertEquals("the view must roll against the phone, not with it",
            -0.4, t.viewRoll, 1e-9)

        t.invert = true
        assertEquals("inverting the steering must not roll the horizon the other way",
            -0.4, t.viewRoll, 1e-9)
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

    /**
     * A race starts level, whatever the phone was doing when it started.
     *
     * Nothing calibrates on the player's behalf any more: a phone picked up at
     * an angle used to make that angle the new straight-ahead, so the same
     * corner wanted a different wheel position from one race to the next and
     * the horizon sat on a slant that never came off.
     */
    @Test
    fun `a fresh wheel is centred on true level, not on how the phone is held`() {
        val t = TiltSteering()
        assertEquals("an uncalibrated wheel must be centred on level", 0.0, t.neutral, 0.0)

        // Picked up already tilted twenty degrees: that is twenty degrees of
        // steering, not a new centre.
        val (gx, gy) = gravityFor(0.35)
        t.onGravity(gx, gy, 0)
        assertEquals(0.35, t.rollFromNeutral, 1e-9)
        repeat(400) { t.update(1.0 / 60.0) }
        assertTrue("holding it tilted should steer, not re-centre", t.steer > 0.2)
    }

    @Test
    fun `held level, a fresh wheel is straight`() {
        val t = TiltSteering()
        val (gx, gy) = gravityFor(0.0)
        t.onGravity(gx, gy, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        assertEquals(0.0, t.steer, 1e-6)
        assertEquals(0.0, t.viewRoll, 1e-9)
    }

    /** The escape hatch, and the way back from it. */
    @Test
    fun `centring adopts the current hold and levelling out undoes it`() {
        val t = TiltSteering()
        val (gx, gy) = gravityFor(0.4)
        t.onGravity(gx, gy, 0)

        t.calibrate()
        assertEquals("centred here, this is now straight ahead", 0.0, t.rollFromNeutral, 1e-9)

        t.levelOut()
        assertEquals("levelling out puts the centre back to flat", 0.4, t.rollFromNeutral, 1e-9)
    }
}
