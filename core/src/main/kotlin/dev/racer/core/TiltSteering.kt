package dev.racer.core

import kotlin.math.asin
import kotlin.math.atan2
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
    /** How far the wheel is turned (rad). See [onGravity]. */
    var rawRoll = 0.0
        private set

    /**
     * How far the picture on the glass is turned (rad).
     *
     * A different angle from [rawRoll], and the distinction matters. The wheel
     * angle is how far the phone has been physically turned. This is how far
     * the image appears to turn as a result, which depends on how the phone is
     * being held: turn an upright phone ten degrees and the image turns ten,
     * turn one held twenty degrees off flat by the same ten and the image
     * turns twenty-seven, because a screen that is nearly parallel to the
     * ground swings the horizon across itself far faster.
     *
     * The horizon is levelled against this one. Using the wheel angle for both
     * left most of the tip uncancelled at any normal hold, so the horizon
     * still swung with the phone — which is indistinguishable, from the
     * driving seat, from cancelling it the wrong way round.
     */
    var screenRoll = 0.0
        private set

    /**
     * Where the wheel is centred (rad). Zero is level; [calibrate] moves it.
     */
    var neutral = 0.0
        private set

    /** The picture's rotation that counts as level. Moved by [calibrate]. */
    var screenNeutral = 0.0
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
        // How upright the phone is being held: how far the screen's own up
        // axis points up.
        //
        // Deliberately not the total amount of gravity in the screen plane —
        // turning the wheel puts gravity in that plane too, so a phone laid
        // flat on a table and rolled ten degrees would count as a third of the
        // way upright and get a third of the horizon correction, on a screen
        // where the correct answer is none.
        //
        // And signed, not absolute: tipped past flat, so the top of the screen
        // is lower than the bottom, this goes negative and clamps to nought.
        // The picture's rotation is a perfectly good number there and looks
        // terrible — a level phone one degree past flat reads as a hundred and
        // eighty degrees of horizon to cancel, which slams the view against
        // its roll limit and holds it there. Nobody is reading the screen from
        // that attitude anyway.
        uprightness = (sy / magnitude).coerceIn(0.0, 1.0)

        // The rotation of the picture on the glass: the angle gravity makes
        // within the plane of the screen. Meaningless when the phone is flat,
        // because then there is no gravity in that plane to take an angle
        // from — which is what [uprightness] is for.
        screenRoll = atan2(sx, sy)
    }

    /**
     * How upright the phone is being held, from 0 (flat on a table) to 1
     * (screen vertical). Turning the wheel barely changes it.
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
        screenNeutral = screenRoll
    }

    /** Put the centre back to true level, where every race starts. */
    fun levelOut() {
        neutral = 0.0
        screenNeutral = 0.0
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
     * The screen is physically rotated by the player, so the drawn horizon is
     * rotated with it in the player's eyes; the view has to turn back by the
     * same amount. Not affected by [invert], which is a steering preference —
     * the horizon is not a matter of taste.
     *
     * Measured against [screenRoll] rather than the wheel angle, because those
     * are not the same angle at any hold but bolt upright. Cancelling the
     * wheel angle instead left this much of the tip still on the screen:
     *
     *     held 45 deg off flat, a 10 deg turn tips the picture 14; 7 cancelled
     *     held 20 deg off flat, a 10 deg turn tips the picture 27; 4 cancelled
     *
     * — so the horizon still swung with the phone, which from the driving seat
     * is indistinguishable from cancelling it the wrong way round, and was
     * duly reported as inverted.
     *
     * The fade is only for the genuinely degenerate case. Laid flat there is
     * no gravity in the plane of the screen to take an angle from, so the
     * angle is noise and cancelling it would throw the horizon around; it also
     * does not need cancelling, because rocking a flat phone barely turns the
     * picture in the player's eyes at all. The band is narrow on purpose:
     * twenty degrees off flat is a normal way to hold a phone and gets the
     * full correction.
     *
     * The sign is the one thing in the file that cannot be derived from the
     * maths alone: it depends on which way round the rendered frame sits on
     * the glass. It was wrong in both directions before settling here, each
     * time corrected against what the phone actually showed.
     */
    val viewRoll: Double get() = -wrapPi(screenRoll - screenNeutral) * horizonFade

    /**
     * One where the picture's rotation is worth cancelling, nought where it is
     * not defined, with a short ramp between.
     */
    private val horizonFade: Double
        get() {
            val t = ((uprightness - FLAT_ENOUGH) / (UPRIGHT_ENOUGH - FLAT_ENOUGH)).coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }

    private companion object {
        /** Below this, the phone is flat and the angle to cancel is noise. */
        const val FLAT_ENOUGH = 0.05

        /** Above this, cancel the whole rotation. About fifteen degrees off flat. */
        const val UPRIGHT_ENOUGH = 0.25
    }
}
