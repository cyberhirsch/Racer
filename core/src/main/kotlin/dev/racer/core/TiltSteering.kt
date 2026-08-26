package dev.racer.core

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.PI

/**
 * The phone is the steering wheel.
 *
 * Steering comes from the *screen roll* angle, obtained by projecting the
 * device's gravity vector into the screen plane. Working from gravity (rather
 * than from a single sensor axis) means it is unaffected by how far the phone
 * is tilted toward or away from the player, and it stays correct when the
 * screen rotates into landscape.
 *
 * Neutral is true level — the attitude a spirit level would call flat — not
 * wherever the phone happened to be when the race began. Starting from the
 * player's grip meant the same corner needed a different wheel position from
 * one race to the next, and a phone picked up at an angle put the horizon on a
 * slant that never came off. Level is the one reference that is the same every
 * time.
 *
 * [calibrate] is still there for a player who wants to drive lying down, but
 * nothing calls it on their behalf.
 *
 * This class is deliberately free of Android imports so the maths can be
 * unit-tested on the JVM — see TiltSteeringTest.
 */
class TiltSteering(
    /** Phone rotation for full steering lock (rad). ~35 degrees. */
    var fullLock: Double = 0.62,
    var deadzone: Double = 0.035,
    /** Higher is snappier. */
    var smoothing: Double = 18.0
) {
    /** Raw measured screen roll (rad). */
    var rawRoll = 0.0
        private set

    /**
     * Where the wheel is centred (rad). Zero is level; [calibrate] moves it.
     */
    var neutral = 0.0
        private set

    var invert = false
    var hasSensor = false
        private set

    /** Smoothed steering command in [-1, 1]. */
    var steer = 0.0
        private set

    /**
     * Feed a gravity vector as reported in *device* coordinates (the axes of
     * Android's SENSOR_TYPE_GRAVITY: +x right, +y up, +z out of the screen,
     * relative to the device's natural orientation).
     *
     * @param displayRotationDegrees the display's rotation relative to the
     *   device's natural orientation: 0, 90, 180 or 270.
     */
    fun onGravity(gx: Double, gy: Double, displayRotationDegrees: Int) {
        hasSensor = true
        // Rotate the in-plane gravity components into the *screen's* frame, so
        // landscape behaves exactly like portrait.
        val a = displayRotationDegrees * PI / 180.0
        val ca = cos(a); val sa = sin(a)
        val sx = gx * ca + gy * sa
        val sy = -gx * sa + gy * ca

        // Android's gravity vector points *away* from the ground: held upright
        // in its natural orientation a phone reads about (0, +9.81, 0). So in
        // the screen frame it points "up the screen", and the angle between it
        // and screen-up is the wheel angle.
        //
        // Rolling the phone clockwise (as the player sees it) moves gravity to
        // (+sin, +cos) in screen coordinates, so this reads positive — and
        // positive steer turns the car right, which is what a wheel turned
        // clockwise should do. The sign here was originally the other way
        // round; the unit test could not catch it, because it checked the code
        // against the same assumption the code was built on. See
        // TiltSteeringTest and scripts/smoke-test.sh.
        rawRoll = atan2(sx, sy)
    }

    /**
     * Take the phone's current attitude as the new centre.
     *
     * For playing somewhere that is not upright — lying down, or in a seat
     * that is not level. Nothing calls this automatically: a race always
     * starts from [levelOut].
     */
    fun calibrate() {
        neutral = rawRoll
    }

    /** Put the centre back to true level, where every race starts. */
    fun levelOut() {
        neutral = 0.0
    }

    private fun wrapPi(a: Double) = atan2(sin(a), cos(a))

    /**
     * Advance the smoothed steering value.
     *
     * @param dt seconds since the last update
     * @param keyboardSteer fallback command in [-1, 1] used when no sensor has
     *   reported yet (desktop/emulator without a gravity sensor)
     */
    fun update(dt: Double, keyboardSteer: Double = 0.0): Double {
        val target: Double
        if (hasSensor) {
            var a = wrapPi(rawRoll - neutral)
            a = sign(a) * maxOf(0.0, abs(a) - deadzone)
            var t = clamp(a / (fullLock - deadzone), -1.0, 1.0)
            if (invert) t = -t
            // Slight expo so small corrections at speed are less twitchy.
            target = sign(t) * abs(t).pow(1.35)
        } else {
            target = clamp(keyboardSteer, -1.0, 1.0)
        }
        val blend = 1.0 - Math.exp(-smoothing * dt)
        steer += (target - steer) * blend
        return steer
    }

    /** Current steering angle relative to neutral, for the HUD needle. */
    val rollFromNeutral: Double get() = wrapPi(rawRoll - neutral)

    /**
     * How far to roll the camera so the horizon stays level.
     *
     * The screen is physically rotated by the player, so the rendered image is
     * rotated with it; the view has to turn back by the same amount, which is
     * why this is the negative of the phone's own rotation. Not affected by
     * [invert], which is a steering preference — the horizon is not a matter
     * of taste.
     *
     * The sign here is the one thing in the file that cannot be derived from
     * the maths alone: it depends on which way round the rendered frame sits
     * on the glass. It was wrong in both directions before settling here, each
     * time corrected against what the phone actually showed.
     */
    val viewRoll: Double get() = -rollFromNeutral
}
