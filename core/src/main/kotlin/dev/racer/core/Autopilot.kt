package dev.racer.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.roundToInt

/**
 * A headless racing driver.
 *
 * Used by the test suite to prove every generated circuit is actually
 * completable, and to measure how much fuel a clean run consumes — which is
 * where the levels' fuel budgets come from.
 *
 * It follows the centreline rather than a racing line, so it is a fair "decent
 * human" reference rather than a perfect lap.
 */
object Autopilot {

    class Result(
        val cfg: LevelConfig,
        val track: Track,
        val finished: Boolean,
        val time: Double,
        val fuelUsed: Double,
        val wallHits: Int,
        val offTrackTime: Double,
        val topSpeed: Double
    ) {
        val avgSpeed: Double get() = if (time > 0) track.length / time else 0.0
    }

    private const val H = 1.0 / 120.0

    /** How far ahead to look for corners when picking a braking point (m). */
    private const val LOOKAHEAD_METRES = 350.0

    /**
     * Deceleration assumed available for braking (m/s^2).
     *
     * Deliberately pessimistic: it must hold at the *end* of the braking zone,
     * where the car is slow and has almost no downforce, so the usable figure
     * is roughly the mechanical grip limit (1.85 g) rather than the much larger
     * number available at 300 km/h. Assuming the high-speed value makes the
     * driver brake late and arrive at the apex too fast.
     */
    var brakeDecel = 8.0

    private const val TOP_SPEED_TARGET = 110.0

    /** Take corners under the theoretical limit, like a human. */
    var cornerSpeedMargin = 0.76

    // Exposed as vars so the tuning sweep in the tests can search them.
    var headingGain = 0.8
    var crossTrackGain = 0.9
    var crossTrackSoften = 8.0

    /** How fast the driver may move the steering command, per second. */
    private const val STEER_RATE = 3.0

    /**
     * One control step: Stanley steering plus a braking-distance speed target.
     */
    fun input(v: Vehicle, track: Track, hint: Int, previousSteer: Double = 0.0, dt: Double = H): Pair<Input, Int> {
        val loc = track.locate(v.x, v.z, hint)
        val n = track.frames.size - 1
        val step = track.length / n

        // --- how much grip is available right now ----------------------------
        val q = 0.5 * Spec.AIR_DENSITY * Spec.FRONTAL_AREA * v.speed * v.speed
        val aLat = Spec.TYRE_REAR.d * (Spec.MASS * 9.81 + q * Spec.LIFT_COEFFICIENT) / Spec.MASS

        // --- steering ---------------------------------------------------------
        // Stanley control: the lock the corner itself needs (feedforward), plus
        // corrections on heading error and on cross-track error, the latter
        // softened by speed so it is gentle at 300 km/h and firm at walking pace.
        val steerLimit = if (v.steerLimit > 1e-6) v.steerLimit else Spec.MAX_STEER

        // Look a little ahead so the wheel is already turning as the corner
        // arrives rather than after it has started.
        val leadFrames = ((2.0 + v.speed * 0.25) / step).roundToInt()
        val kAhead = track.curvature[(loc.index + leadFrames) % n]

        // Curvature is measured as -dYaw/ds (see Track.curvatures), so the lock
        // the corner requires is atan(wheelbase * -k).
        val feedForward = atan(-(Spec.A + Spec.B) * kAhead)

        val trackYaw = atan2(loc.frame.tangent.x, loc.frame.tangent.z)
        val headingError = wrapPi(trackYaw - v.yaw)
        // Car right of the centreline (lateral > 0) needs left lock.
        val crossTrack = -atan(crossTrackGain * loc.lateral / (v.speed + crossTrackSoften))

        val delta = feedForward + headingGain * headingError + crossTrack

        // The physics maps full input to the grip-limited steering angle, which
        // at 240 km/h is about two degrees — so a small angle error saturates
        // the input. Rate limiting the command stops that saturation arriving
        // as an instant lock-to-lock flick, which snaps the rear loose.
        val wanted = clamp(delta / steerLimit, -1.0, 1.0)
        val steer = previousSteer + clamp(wanted - previousSteer, -STEER_RATE * dt, STEER_RATE * dt)

        // --- speed ------------------------------------------------------------
        // The speed allowed *here* is whatever can still be shed in time to
        // arrive at each corner ahead at its own limit:
        //   v <= sqrt(v_corner^2 + 2 * a_brake * distance)
        // Taking the minimum over the lookahead gives a real braking point.
        val scan = (LOOKAHEAD_METRES / step).roundToInt()
        var vTarget = TOP_SPEED_TARGET
        for (i in 0 until scan) {
            val k = abs(track.curvature[(loc.index + i) % n])
            if (k < 1e-5) continue
            val vCorner = cornerSpeed(k) * cornerSpeedMargin
            val allowed = sqrt(vCorner * vCorner + 2 * brakeDecel * (i * step))
            vTarget = min(vTarget, allowed)
        }

        val dv = vTarget - v.speed
        return Input(
            throttle = if (dv > 1) min(1.0, dv / 6) else 0.0,
            brake = if (dv < -1.5) min(1.0, -dv / 12) else 0.0,
            steer = steer
        ) to loc.index
    }


    /**
     * The fastest a corner of curvature [k] can be taken, solved
     * self-consistently for downforce.
     *
     * Grip depends on speed (downforce rises with v^2) and so does the demand
     * (v^2 * k), so the corner speed is the fixed point of
     *     v^2 * k = D * (W + c * v^2) / m
     * which rearranges to v^2 = D * W / (m * k - D * c).
     *
     * Evaluating grip at the *current* speed instead — which is much higher on
     * the approach — overestimates the corner speed badly and puts the car in
     * the barrier. If the denominator is non-positive, downforce grows faster
     * than the demand and the corner is flat out.
     */
    fun cornerSpeed(k: Double): Double {
        val c = 0.5 * Spec.AIR_DENSITY * Spec.FRONTAL_AREA * Spec.LIFT_COEFFICIENT
        val denominator = Spec.MASS * k - Spec.TYRE_REAR.d * c
        if (denominator <= 1e-6) return TOP_SPEED_TARGET
        val vSquared = Spec.TYRE_REAR.d * Spec.MASS * 9.81 / denominator
        return min(TOP_SPEED_TARGET, sqrt(vSquared))
    }

    private fun wrapPi(a: Double) = atan2(sin(a), cos(a))

    fun simulate(levelIndex: Int, maxSeconds: Double = 400.0, fuel: Double = 1e6): Result {
        val cfg = Levels.config(levelIndex)
        val track = Track(cfg)
        val v = Vehicle()
        val (sx, sz, syaw) = track.startPose
        v.reset(sx, sz, syaw)
        v.fuel = fuel

        var hint = 0
        var steer = 0.0
        var t = 0.0
        var next = 0
        var hits = 0
        var offTrackTime = 0.0
        var topSpeed = 0.0

        while (t < maxSeconds && next < track.checkpoints.size) {
            val (inp, newHint) = input(v, track, hint, steer, H)
            hint = newHint
            steer = inp.steer

            val surf = track.surface(v.x, v.z, hint)
            v.gripScale = surf.grip
            if (surf.offTrack) offTrackTime += H
            v.step(H, inp)
            surf.hit?.let { v.collide(it.nx, it.nz, it.penetration + 0.02); hits++ }

            val loc = track.locate(v.x, v.z, hint)
            val n = track.frames.size - 1
            if (abs(wrapIndex(loc.index - track.checkpoints[next], n)) < 12) next++

            topSpeed = max(topSpeed, v.speed)
            t += H
            if (v.fuel <= 0) break
        }

        return Result(cfg, track, next >= track.checkpoints.size, t, v.fuelUsed, hits, offTrackTime, topSpeed)
    }

    /**
     * Compare frame indices the short way round the loop. The finish line sits
     * at index n, which is the same place as index 0, so a plain subtraction
     * would read the finish as a whole lap away just as you cross it.
     */
    fun wrapIndex(d: Int, n: Int): Int {
        var r = d % n
        if (r > n / 2) r -= n
        if (r < -n / 2) r += n
        return r
    }
}
