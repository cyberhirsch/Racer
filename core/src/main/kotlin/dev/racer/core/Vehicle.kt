package dev.racer.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Vehicle dynamics for the F1 car.
 *
 * A dynamic bicycle model: two virtual tyres (front axle, rear axle) carrying
 * lateral forces from a simplified Pacejka magic-formula curve, longitudinal
 * force from an engine torque curve through a gearbox, load transfer from
 * acceleration and aerodynamic downforce, and a friction ellipse coupling the
 * two directions so grip spent cornering is not available for accelerating.
 *
 * Units are SI throughout: metres, seconds, kilograms, newtons, radians.
 * World axes match the renderer: +X right, +Y up, +Z forward at zero yaw.
 */
object Spec {
    const val MASS = 798.0            // kg, car + driver at the minimum weight
    const val YAW_INERTIA = 1100.0    // kg*m^2
    const val A = 1.72                // CoG -> front axle (m)
    const val B = 1.66                // CoG -> rear axle (m)
    const val CG_HEIGHT = 0.30        // m
    const val WHEEL_RADIUS = 0.36

    // Tyre model (simplified Pacejka). b = stiffness, c = shape, d = peak factor.
    val TYRE_FRONT = Tyre(b = 11.0, c = 1.55, d = 1.72, e = 0.96)
    val TYRE_REAR = Tyre(b = 10.0, c = 1.60, d = 1.85, e = 0.96)
    const val ROLLING_RESISTANCE = 0.014

    // Powertrain
    const val MAX_TORQUE = 660.0      // N*m at the crank; ~735 kW at the peak
    const val TORQUE_PEAK_RPM = 10500.0
    const val REDLINE = 14500.0
    const val IDLE_RPM = 4000.0
    const val FINAL_DRIVE = 3.4

    /**
     * Total ratios run ~19.7:1 in first down to ~5.85:1 in eighth, putting the
     * redline at roughly 100 km/h in first and 335 km/h in eighth.
     */
    val GEARS = doubleArrayOf(5.80, 4.35, 3.55, 3.00, 2.60, 2.28, 1.98, 1.72)
    const val SHIFT_UP_RPM = 13800.0
    const val SHIFT_DOWN_RPM = 8800.0
    const val SHIFT_TIME = 0.05       // s of torque cut per shift
    const val DRIVELINE_EFFICIENCY = 0.93

    // Brakes
    const val MAX_BRAKE_TORQUE = 5200.0
    const val BRAKE_BIAS = 0.58       // fraction to the front axle

    // Aero
    const val FRONTAL_AREA = 1.5
    const val DRAG_COEFFICIENT = 1.02
    const val LIFT_COEFFICIENT = 3.1  // negative lift, i.e. downforce
    const val AERO_BALANCE = 0.45     // fraction of downforce on the front axle
    const val AIR_DENSITY = 1.225

    const val MAX_STEER = 0.30        // rad at the road wheels (~17 deg) at parking speeds

    // Fuel: kg per joule of crank work at ~32% thermal efficiency, plus an idle draw.
    const val FUEL_PER_JOULE = 1.0 / 43.0e6 / 0.32
    const val FUEL_IDLE_RATE = 0.0025 // kg/s
}

data class Tyre(val b: Double, val c: Double, val d: Double, val e: Double)

/** Driver input for one physics step. */
data class Input(val throttle: Double = 0.0, val brake: Double = 0.0, val steer: Double = 0.0)

internal fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v

/** Simplified Pacejka lateral force coefficient for a slip angle (rad). */
fun pacejka(alpha: Double, t: Tyre): Double {
    val bx = t.b * alpha
    return t.d * sin(t.c * atan(bx - t.e * (bx - atan(bx))))
}

/** Crank torque (N*m) available at a given rpm, wide open throttle. */
fun engineTorque(rpm: Double): Double {
    if (rpm < 500 || rpm > Spec.REDLINE) return 0.0
    val x = rpm / Spec.TORQUE_PEAK_RPM
    val over = max(0.0, x - 1.0)
    val shape = 1.0 - 0.55 * (x - 1.0) * (x - 1.0) - 0.22 * over * over * over * 6.0
    return Spec.MAX_TORQUE * clamp(shape, 0.0, 1.05)
}

class Vehicle {
    var x = 0.0; var z = 0.0; var yaw = 0.0
    var vx = 0.0                  // body-frame forward velocity (m/s)
    var vy = 0.0                  // body-frame lateral velocity (m/s), +X = right
    var yawRate = 0.0
    var gear = 0                  // index into Spec.GEARS
    var rpm = Spec.IDLE_RPM
    var steer = 0.0               // current road-wheel angle (rad), rate limited
    var steerLimit = Spec.MAX_STEER
    var wheelSpin = 0.0           // accumulated wheel rotation, for the visual model
    var fuel = 0.0                // kg
    var fuelUsed = 0.0
    var gripScale = 1.0           // reduced when running wide onto the runoff
    var offTrack = false
    var speed = 0.0
    var wheelspin = false
    var lastAx = 0.0
        private set

    private var shiftTimer = 0.0

    /**
     * Scrub off a fraction of the car's motion, as heavy ground does.
     *
     * Not a collision: nothing is reflected and no energy comes back. The car
     * simply loses speed, which is what deep grass does to it.
     */
    fun scrub(fraction: Double) {
        val keep = (1.0 - fraction).coerceIn(0.0, 1.0)
        vx *= keep
        vy *= keep
        yawRate *= keep
        speed = abs(vx)
    }

    fun reset(x: Double = 0.0, z: Double = 0.0, yaw: Double = 0.0) {
        this.x = x; this.z = z; this.yaw = yaw
        vx = 0.0; vy = 0.0; yawRate = 0.0
        gear = 0; rpm = Spec.IDLE_RPM; shiftTimer = 0.0
        steer = 0.0; wheelSpin = 0.0
        fuel = 0.0; fuelUsed = 0.0
        gripScale = 1.0; offTrack = false; speed = 0.0; lastAx = 0.0
    }

    /** Engine rpm implied by road speed in the given gear. */
    fun rpmForSpeed(v: Double, g: Int = gear): Double {
        val wheelRadPerSec = v / Spec.WHEEL_RADIUS
        val r = wheelRadPerSec * Spec.GEARS[g] * Spec.FINAL_DRIVE * 60.0 / (2.0 * PI)
        return clamp(r, Spec.IDLE_RPM, Spec.REDLINE)
    }

    /**
     * Advance one step. Callers should use a small fixed step (1/120 s) so the
     * behaviour does not depend on the frame rate.
     */
    fun step(dt: Double, input: Input) {
        val throttle = clamp(input.throttle, 0.0, 1.0)
        val brake = clamp(input.brake, 0.0, 1.0)

        speed = hypot(vx, vy)

        // --- aero first: the steering limit depends on current downforce -----
        val q = 0.5 * Spec.AIR_DENSITY * Spec.FRONTAL_AREA * vx * vx
        val downforce = q * Spec.LIFT_COEFFICIENT
        val weight = Spec.MASS * 9.81
        val wheelbase = Spec.A + Spec.B

        // Cap the commanded steering at the angle that asks for exactly as much
        // lateral acceleration as the tyres can supply at this speed. Without
        // this, full lock at 200 km/h demands well over 10 g, the car simply
        // spins, and the rotating-frame integration diverges. It also matches
        // how a real car behaves: at speed you only ever use a few degrees.
        val aMax = Spec.TYRE_REAR.d * (weight + downforce) / Spec.MASS * gripScale
        steerLimit = min(Spec.MAX_STEER, atan(wheelbase * aMax / max(speed * speed, 1.0)))
        val targetSteer = clamp(input.steer, -1.0, 1.0) * steerLimit
        val steerRate = 3.6   // rad/s at the road wheels
        steer += clamp(targetSteer - steer, -steerRate * dt, steerRate * dt)

        // --- vertical loads with longitudinal transfer -----------------------
        val drag = q * Spec.DRAG_COEFFICIENT * sign(vx)
        val transfer = Spec.MASS * lastAx * Spec.CG_HEIGHT / wheelbase
        val fzF = max(weight * Spec.B / wheelbase + downforce * Spec.AERO_BALANCE - transfer, 200.0)
        val fzR = max(weight * Spec.A / wheelbase + downforce * (1 - Spec.AERO_BALANCE) + transfer, 200.0)

        // --- slip angles ------------------------------------------------------
        // Guard the low-speed singularity: below ~2 m/s slip angles are
        // meaningless and the model blows up, so fade the lateral forces in.
        val vxSafe = max(abs(vx), 1.2)
        val alphaF = atan((vy + Spec.A * yawRate) / vxSafe) - steer
        val alphaR = atan((vy - Spec.B * yawRate) / vxSafe)
        val lowSpeedFade = clamp((speed - 0.4) / 1.6, 0.0, 1.0)

        val fyF = -pacejka(alphaF, Spec.TYRE_FRONT) * fzF * lowSpeedFade * gripScale
        val fyR = -pacejka(alphaR, Spec.TYRE_REAR) * fzR * lowSpeedFade * gripScale

        // --- powertrain -------------------------------------------------------
        shiftTimer = max(0.0, shiftTimer - dt)
        rpm = rpmForSpeed(abs(vx))
        if (shiftTimer == 0.0) {
            if (rpm > Spec.SHIFT_UP_RPM && gear < Spec.GEARS.size - 1) {
                gear++; shiftTimer = Spec.SHIFT_TIME
            } else if (rpm < Spec.SHIFT_DOWN_RPM && gear > 0) {
                gear--; shiftTimer = Spec.SHIFT_TIME
            }
        }

        val cut = if (shiftTimer > 0) 0.0 else 1.0
        val crankTorque = engineTorque(rpm) * throttle * cut
        val wheelTorque = crankTorque * Spec.GEARS[gear] * Spec.FINAL_DRIVE * Spec.DRIVELINE_EFFICIENCY
        var fxR = wheelTorque / Spec.WHEEL_RADIUS

        // Traction limit at the driven axle (friction ellipse).
        val maxRear = Spec.TYRE_REAR.d * fzR * gripScale
        val rearLatUse = clamp(abs(fyR) / maxRear, 0.0, 1.0)
        val rearLongCap = maxRear * sqrt(max(0.0, 1.0 - rearLatUse * rearLatUse))
        wheelspin = abs(fxR) > rearLongCap
        if (wheelspin) fxR = sign(fxR) * rearLongCap

        // --- brakes -----------------------------------------------------------
        var fxF = 0.0
        if (brake > 0.001) {
            val total = Spec.MAX_BRAKE_TORQUE * brake / Spec.WHEEL_RADIUS
            val bf = total * Spec.BRAKE_BIAS
            val br = total * (1 - Spec.BRAKE_BIAS)
            val maxFront = Spec.TYRE_FRONT.d * fzF * gripScale
            val frontLatUse = clamp(abs(fyF) / maxFront, 0.0, 1.0)
            val frontCap = maxFront * sqrt(max(0.0, 1.0 - frontLatUse * frontLatUse))

            val dir = if (vx >= 0) -1.0 else 1.0
            fxF = dir * min(bf, frontCap)
            fxR += dir * min(br, max(0.0, rearLongCap - abs(fxR)))

            // Stop cleanly instead of jittering backwards at a standstill.
            if (abs(vx) < 0.35 && throttle < 0.02) {
                vx = 0.0; fxF = 0.0; fxR = 0.0
            }
        }

        // --- resistances and net forces ---------------------------------------
        val rolling = Spec.ROLLING_RESISTANCE * (fzF + fzR) * sign(vx)
        val fx = fxR + fxF - drag - rolling + fyF * sin(-steer)
        val fy = fyF * cos(steer) + fyR

        // --- rigid body integration (body frame, semi-implicit Euler) ---------
        val axBody = fx / Spec.MASS + yawRate * vy
        val ayBody = fy / Spec.MASS - yawRate * vx
        val yawAccel = (Spec.A * fyF * cos(steer) - Spec.B * fyR) / Spec.YAW_INERTIA
        lastAx = axBody

        vx += axBody * dt
        vy += ayBody * dt
        yawRate += yawAccel * dt

        // Backstop: a fully crossed-up slide is legitimate, an unbounded one is
        // a numerical artefact of integrating the rotating frame explicitly.
        // Clamp to values a real car can reach so a spin stays a spin.
        yawRate = clamp(yawRate, -4.5, 4.5)
        vy = clamp(vy, -60.0, 60.0)
        vx = clamp(vx, -30.0, 120.0)

        if (speed < 1.0) yawRate *= Math.pow(0.02, dt)

        // --- world-frame pose --------------------------------------------------
        val s = sin(yaw); val c = cos(yaw)
        x += (vx * s + vy * c) * dt
        z += (vx * c - vy * s) * dt
        yaw += yawRate * dt

        wheelSpin += (vx / Spec.WHEEL_RADIUS) * dt

        // --- fuel ---------------------------------------------------------------
        // Burn is proportional to the actual crank work done, plus an idle draw.
        val crankPower = max(0.0, crankTorque) * (rpm * 2.0 * PI / 60.0)
        val burn = (crankPower * Spec.FUEL_PER_JOULE + Spec.FUEL_IDLE_RATE) * dt
        fuel = max(0.0, fuel - burn)
        fuelUsed += burn
    }
}
