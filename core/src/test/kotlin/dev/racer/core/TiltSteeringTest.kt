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
    /**
     * Where the screen's own axes point, in device coordinates, for each
     * display rotation.
     *
     * Straight out of Android's documentation, written as the table it is.
     * This is the whole point of the helper: the previous version built its
     * gravity by inverting the formula under test, so the test and the code
     * shared an assumption and agreed with each other while both were 180
     * degrees out in both landscapes. A test may not derive its expectations
     * from the thing it is testing.
     *
     *     rotation    screen right    screen up
     *          0          +x             +y
     *         90          -y             +x
     *        180          -x             -y
     *        270          +y             -x
     */
    private fun toDeviceAxes(
        right: Double,
        up: Double,
        displayRotation: Int
    ): Pair<Double, Double> = when (displayRotation) {
        0 -> Pair(right, up)
        90 -> Pair(up, -right)
        180 -> Pair(-right, -up)
        270 -> Pair(-up, right)
        else -> throw IllegalArgumentException("not a display rotation: $displayRotation")
    }

    /**
     * Gravity as Android reports it, in device coordinates, for a phone held
     * with the screen's right-hand edge tilted **down** by `roll` and laid
     * back from vertical by `pitch`.
     *
     * The one physical convention everything here rests on, stated once so it
     * can be checked by eye: Android's gravity vector points *away* from the
     * ground, so a phone held upright in its natural orientation reads about
     * (0, +9.81, 0). Tilt the right-hand edge of the screen down and the
     * screen's right axis now points partly downward, so gravity's component
     * along it goes negative.
     *
     * Laying the phone back tips gravity out of the screen plane without
     * turning it: the screen's right axis stays horizontal however far back it
     * goes, so the sideways component does not shrink with pitch. The previous
     * version scaled it as though it did, which made the maths look right at
     * every hold angle when it was right at only one.
     */
    private fun gravityFor(
        roll: Double,
        pitch: Double = 0.0,
        displayRotation: Int = 0
    ): Triple<Double, Double, Double> {
        val g = 9.81
        val right = -sin(roll) * g
        val up = cos(pitch) * cos(roll) * g
        val outOfScreen = sin(pitch) * cos(roll) * g
        val (gx, gy) = toDeviceAxes(right, up, displayRotation)
        return Triple(gx, gy, outOfScreen)
    }

    private fun recovered(roll: Double, displayRotation: Int, pitch: Double = 0.0): Double {
        val t = TiltSteering()
        // Calibrate at the attitude the player is holding — exactly what the
        // game does when the countdown starts.
        val (nx, ny, nxnyz) = gravityFor(0.0, displayRotation = displayRotation)
        t.onGravity(nx, ny, nxnyz, displayRotation)
        t.calibrate()

        val (gx, gy, gxgyz) = gravityFor(roll, pitch, displayRotation)
        t.onGravity(gx, gy, gxgyz, displayRotation)
        return t.rollFromNeutral
    }

    @Test
    fun `recovers the roll angle exactly in every screen orientation`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            var worst = 0.0
            var prev = Double.POSITIVE_INFINITY
            var monotonic = true
            var deg = -40
            while (deg <= 40) {
                val roll = deg * PI / 180
                // Tilting the right-hand edge down is a left turn, so the
                // wheel angle comes back as the negative of the tilt.
                val got = recovered(roll, rotation)
                worst = maxOf(worst, abs(got + roll))
                if (got > prev) monotonic = false
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

    /**
     * Held level in landscape, the phone must read as upright.
     *
     * The check that catches a display-rotation mapping turned the wrong way
     * round. Getting that backwards is exactly 180 degrees out at 90 and 270
     * and correct at 0 and 180, so a phone in either landscape reads as tipped
     * past flat — which switches the horizon levelling off entirely and
     * mirrors the steering, and is what shipped.
     */
    @Test
    fun `held level, the phone reads as upright in every display rotation`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val t = TiltSteering()
            val (gx, gy, gz) = gravityFor(0.0, displayRotation = rotation)
            t.onGravity(gx, gy, gz, rotation)
            assertEquals(
                "at display rotation $rotation a level phone read uprightness ${t.uprightness}",
                1.0, t.uprightness, 1e-9
            )
            assertEquals("and the wheel must be straight", 0.0, t.rollFromNeutral, 1e-9)
            assertEquals("and the horizon flat", 0.0, t.viewRoll, 1e-9)
        }
        println("level reads upright and straight in all four display rotations")
    }

    /**
     * The bug that shipped, stated as the thing it broke.
     *
     * Nobody plays with the phone standing bolt upright; it is held somewhere
     * between flat and vertical. The steering has to read the same angle
     * whatever that hold is, or it is a different game every time you shift in
     * your seat. It did not: it divided by a component that shrinks as the
     * phone is tilted back, so the gain climbed without limit as the hold
     * approached flat — at twenty degrees off flat a five degree twitch read
     * as fourteen, and at ten degrees off flat it read twenty-seven.
     */
    @Test
    fun `reads the same steering angle at every hold angle`() {
        for (pitchDeg in listOf(2, 5, 10, 20, 45, 70, 90)) {
            for (rollDeg in listOf(-30, -10, -5, 5, 10, 30)) {
                val t = TiltSteering()
                val (gx, gy, gz) = gravityFor(
                    rollDeg * PI / 180,
                    // Held `pitchDeg` off flat, so pitch away from vertical.
                    (90 - pitchDeg) * PI / 180
                )
                t.onGravity(gx, gy, gz, 0)
                val got = t.rollFromNeutral * 180 / PI
                assertEquals(
                    "held $pitchDeg deg off flat, a $rollDeg deg tilt read as $got",
                    -rollDeg.toDouble(), got, 1e-6
                )
            }
        }
        println("the wheel reads true from two degrees off flat to bolt upright")
    }

    /**
     * Laid flat there is no rotation in the gravity vector to read at all, and
     * the old maths read ninety degrees off it: full lock, permanently, with
     * the horizon on its side to match.
     */
    @Test
    fun `laid flat on a table the wheel is straight and the horizon is level`() {
        val t = TiltSteering()
        t.onGravity(0.0, 0.0, 9.81, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        assertEquals("a phone lying flat is not steering", 0.0, t.steer, 1e-6)
        assertEquals("a phone lying flat has no horizon to cancel", 0.0, t.viewRoll, 1e-9)
    }

    /**
     * The horizon has to be levelled against how far the *picture* turned, not
     * how far the phone turned. They are only the same angle when the phone is
     * bolt upright, and the difference is not small: held twenty degrees off
     * flat, a ten degree turn of the phone swings the horizon twenty-seven
     * degrees across the screen, because a screen nearly parallel to the
     * ground sweeps the horizon across itself fast.
     *
     * Cancelling the phone's angle instead left most of that on the screen, so
     * the horizon still swung with the phone — which from the driving seat is
     * indistinguishable from cancelling it backwards, and was reported as an
     * inverted horizon.
     */
    @Test
    fun `the horizon is levelled by however far the picture turned`() {
        for (pitchOffFlat in listOf(90, 60, 45, 30, 20)) {
            for (rollDeg in listOf(-20, -10, 10, 20)) {
                val t = TiltSteering()
                val roll = rollDeg * PI / 180
                val (gx, gy, gz) = gravityFor(roll, (90 - pitchOffFlat) * PI / 180)
                t.onGravity(gx, gy, gz, 0)

                // What the horizon actually does on the glass: the angle
                // gravity makes inside the plane of the screen.
                val onGlass = kotlin.math.atan2(gx, gy)
                assertEquals(
                    "held $pitchOffFlat deg off flat with $rollDeg deg of wheel, the view " +
                        "rolled ${t.viewRoll * 180 / PI} where the picture turned " +
                        "${onGlass * 180 / PI}",
                    -onGlass, t.viewRoll, 1e-6
                )
            }
        }
        println("the horizon is cancelled exactly, from ten degrees off flat to upright")
    }

    /**
     * Held flat there is no gravity in the plane of the screen, so the angle to
     * cancel is not defined — and rocking a nearly flat phone barely turns the
     * picture in the player's eyes anyway. Cancelling noise there threw the
     * horizon around and left it on its side.
     *
     * The band this fades over has to be narrow. Twenty degrees off flat is a
     * normal way to hold a phone, and the previous attempt at this faded most
     * of the correction away by then, which is what left the horizon swinging
     * with the phone.
     */
    @Test
    fun `the horizon correction fades out only when the phone is nearly flat`() {
        fun correctionAt(pitchOffFlatDeg: Double): Double {
            val t = TiltSteering()
            val (gx, gy, gz) = gravityFor(10 * PI / 180, (90 - pitchOffFlatDeg) * PI / 180)
            t.onGravity(gx, gy, gz, 0)
            return t.viewRoll / -kotlin.math.atan2(gx, gy)
        }
        val holds = listOf(45.0, 20.0, 10.0, 5.0, 2.0, 0.5)
        val applied = holds.map { correctionAt(it) }
        println(holds.zip(applied).joinToString { "%.1f deg off flat -> %.2f".format(it.first, it.second) })

        assertEquals("a normal hold must get the whole correction", 1.0, correctionAt(45.0), 1e-9)
        assertEquals("so must twenty degrees off flat", 1.0, correctionAt(20.0), 1e-9)
        for (i in 1 until applied.size) {
            assertTrue(
                "the correction must not grow as the phone is laid flatter: $applied",
                applied[i] <= applied[i - 1] + 1e-9
            )
        }
        assertTrue("flat on the table, none of it should be applied", applied.last() < 0.05)
    }

    /**
     * Tipped past flat — screen facing up with the top edge below the bottom —
     * the picture's rotation is a real number and a useless one: level reads as
     * a hundred and eighty degrees of horizon to cancel, which slams the view
     * against its roll limit and pins it there. The emulator's sensor frame
     * sits permanently in this attitude, which is how it was found.
     */
    @Test
    fun `tipped past flat, the horizon is left alone rather than thrown over`() {
        val t = TiltSteering()
        // Gravity up the screen's *down* axis: past flat, and otherwise level.
        t.onGravity(0.0, -9.81, 0.0, 0)
        assertEquals("past flat is not upright", 0.0, t.uprightness, 1e-9)
        assertEquals("the horizon must be left where it is", 0.0, t.viewRoll, 1e-9)
        assertEquals("and it is not steering either", 0.0, t.rollFromNeutral, 1e-9)
    }

    @Test
    fun `applies a deadzone and saturates at full lock`() {
        val t = TiltSteering()
        t.onGravity(0.0, 9.81, 0.0, 0)
        t.calibrate()

        // Inside the deadzone: no steering at all.
        val (sx, sy, sxsyz) = gravityFor(0.02)
        t.onGravity(sx, sy, sxsyz, 0)
        repeat(200) { t.update(1.0 / 60.0) }
        assertEquals("deadzone leaked", 0.0, t.steer, 1e-6)

        // Well past full lock: saturated, not beyond 1.
        val (bx, by, bxbyz) = gravityFor(1.2)
        t.onGravity(bx, by, bxbyz, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        assertEquals("did not reach full lock", -1.0, t.steer, 1e-3)
    }

    /**
     * Tilting the screen's right-hand edge down steers left.
     *
     * The direction the game is played with, stated in plain terms rather than
     * left implicit in a round trip, because it has been wrong on the device
     * twice. INVERT is there for anyone who wants the other way round.
     */
    @Test
    fun `tilting the right-hand edge down steers left`() {
        val t = TiltSteering()
        val (nx, ny, nxnyz) = gravityFor(0.0)
        t.onGravity(nx, ny, nxnyz, 0); t.calibrate()

        val (cx, cy, cxcyz) = gravityFor(0.35)          // right edge down 20 deg
        t.onGravity(cx, cy, cxcyz, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        println("right edge down 20 deg -> steer %+.3f".format(t.steer))
        assertTrue("right edge down should steer left, got ${t.steer}", t.steer < -0.1)

        val (ax, ay, axayz) = gravityFor(-0.35)         // left edge down 20 deg
        t.onGravity(ax, ay, axayz, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        println("left edge down 20 deg -> steer %+.3f".format(t.steer))
        assertTrue("left edge down should steer right, got ${t.steer}", t.steer > 0.1)
    }

    /**
     * The camera has to turn back by the amount the phone turned, so the view
     * roll opposes the phone's rotation. The horizon is also not a preference:
     * inverting the steering must not flip it.
     */
    @Test
    fun `the view roll opposes the phone and ignores the invert setting`() {
        val t = TiltSteering()
        val (nx, ny, nxnyz) = gravityFor(0.0)
        t.onGravity(nx, ny, nxnyz, 0); t.calibrate()

        val (gx, gy, gxgyz) = gravityFor(0.4)
        t.onGravity(gx, gy, gxgyz, 0)
        assertEquals(-0.4, t.rollFromNeutral, 1e-9)
        assertEquals("the view must roll against the phone, not with it",
            0.4, t.viewRoll, 1e-9)

        t.invert = true
        assertEquals("inverting the steering must not roll the horizon the other way",
            0.4, t.viewRoll, 1e-9)
    }

    @Test
    fun `invert flips the steering direction`() {
        val t = TiltSteering()
        t.onGravity(0.0, 9.81, 0.0, 0); t.calibrate()
        val (gx, gy, gxgyz) = gravityFor(0.4)
        t.onGravity(gx, gy, gxgyz, 0)
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
        val (gx, gy, gxgyz) = gravityFor(0.35)
        t.onGravity(gx, gy, gxgyz, 0)
        assertEquals(-0.35, t.rollFromNeutral, 1e-9)
        repeat(400) { t.update(1.0 / 60.0) }
        assertTrue("holding it tilted should steer, not re-centre", t.steer < -0.2)
    }

    @Test
    fun `held level, a fresh wheel is straight`() {
        val t = TiltSteering()
        val (gx, gy, gxgyz) = gravityFor(0.0)
        t.onGravity(gx, gy, gxgyz, 0)
        repeat(400) { t.update(1.0 / 60.0) }
        assertEquals(0.0, t.steer, 1e-6)
        assertEquals(0.0, t.viewRoll, 1e-9)
    }

    /** The escape hatch, and the way back from it. */
    @Test
    fun `centring adopts the current hold and levelling out undoes it`() {
        val t = TiltSteering()
        val (gx, gy, gxgyz) = gravityFor(0.4)
        t.onGravity(gx, gy, gxgyz, 0)

        t.calibrate()
        assertEquals("centred here, this is now straight ahead", 0.0, t.rollFromNeutral, 1e-9)

        t.levelOut()
        assertEquals("levelling out puts the centre back to flat", -0.4, t.rollFromNeutral, 1e-9)
    }
}
