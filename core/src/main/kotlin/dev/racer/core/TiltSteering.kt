package dev.racer.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
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
    fun onGravity(gx: Double, gy: Double, gz: Double, displayRotationDegrees: Int) {
        hasSensor = true
        // Rotate the in-plane gravity components into the *screen's* frame, so
        // landscape behaves exactly like portrait.
        val a = displayRotationDegrees * PI / 180.0
        val ca = cos(a); val sa = sin(a)
        val sx = gx * ca + gy * sa
        val sy = -gx * sa + gy * ca

        val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
        if (magnitude < 1e-3) return

        // How far the wheel is turned: the angle by which the screen's own
        // left-right axis has dipped away from horizontal.
        //
        // This used to be atan2 of the two in-screen components, which is the
        // rotation of the picture on the glass — and which is only the same
        // thing as the wheel angle when the phone is held bolt upright. Held
        // at any normal angle it reads far more than the phone has actually
        // moved, because it divides by a component that shrinks as the phone
        // is tilted back: at twenty degrees off flat a five degree twitch came
        // out as fourteen, full lock arrived after twelve degrees of real
        // movement, and laid flat on a table it read ninety degrees of
        // steering off a vector that has no rotation in it at all. Steering
        // and horizon were both unusable, and no amount of calibrating the
        // neutral could help, because the fault is in the gain and not the
        // offset.
        //
        // Measured against the whole gravity vector instead, the answer is the
        // angle the phone has actually been turned through, at every hold
        // angle from flat to vertical. Same sign as before: the wheel still
        // turns the way it did.
        rawRoll = asin((sx / magnitude).coerceIn(-1.0, 1.0))

        // How much of gravity lies in the plane of the screen — one when the
        // phone is upright, nought when it is flat. See [viewRoll].
        uprightness = (hypot(sx, sy) / magnitude).coerceIn(0.0, 1.0)
    }

    /**
     * How upright the phone is being held, from 0 (flat) to 1 (vertical).
     */
    var uprightness = 0.0
        private set

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
     * Faded out by [uprightness], because how much the picture on the glass
     * turns depends on how the phone is being held. Held upright and turned
     * like a wheel, the image rotates by the full angle and the view has to
     * cancel all of it. Laid flat on a table and rocked side to side, the
     * image does not rotate at all and there is nothing to cancel — and the
     * angle to cancel by is not even well defined, which is what used to put
     * the horizon on its side and leave it there.
     *
     * The sign is the one thing in the file that cannot be derived from the
     * maths alone: it depends on which way round the rendered frame sits on
     * the glass. It was wrong in both directions before settling here, each
     * time corrected against what the phone actually showed.
     */
    val viewRoll: Double get() = -rollFromNeutral * uprightness
}
