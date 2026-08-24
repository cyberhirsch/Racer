/**
 * Vehicle dynamics for the F1 car.
 *
 * A dynamic bicycle model: two virtual tyres (front axle, rear axle) carrying
 * lateral forces from a simplified Pacejka magic-formula curve, longitudinal
 * force from an engine torque curve through a gearbox, load transfer from
 * acceleration and aerodynamic downforce, and a friction ellipse coupling the
 * two directions so you cannot brake and corner at full grip simultaneously.
 *
 * Units are SI throughout: metres, seconds, kilograms, newtons, radians.
 * World axes match the renderer: +X right, +Y up, +Z forward at zero yaw.
 */

export const SPEC = {
  mass: 798,              // kg, car + driver at minimum weight
  yawInertia: 1100,       // kg·m²
  a: 1.72,                // CoG -> front axle (m)
  b: 1.66,                // CoG -> rear axle (m)
  cgHeight: 0.30,         // m
  wheelRadius: 0.36,

  // Tyre model (simplified Pacejka). B = stiffness, C = shape, D = peak factor.
  tyre: {
    front: { B: 11.0, C: 1.55, D: 1.72, E: 0.96 },
    rear:  { B: 10.0, C: 1.60, D: 1.85, E: 0.96 }
  },
  rollingResistance: 0.014,

  // Powertrain
  maxTorque: 660,         // N·m at the crank (hybrid-assisted); ~735 kW at the peak
  torquePeakRpm: 10500,
  redline: 14500,
  idleRpm: 4000,
  finalDrive: 3.4,
  // Total ratios run ~19.7:1 in first down to ~5.85:1 in eighth, which puts the
  // redline at roughly 100 km/h in first and 335 km/h in eighth.
  gears: [5.80, 4.35, 3.55, 3.00, 2.60, 2.28, 1.98, 1.72],
  shiftUpRpm: 13800,
  shiftDownRpm: 8800,
  shiftTime: 0.05,        // s of torque cut per shift
  drivelineEfficiency: 0.93,

  // Brakes
  maxBrakeTorque: 5200,   // N·m total
  brakeBias: 0.58,        // fraction to the front axle

  // Aero
  frontalArea: 1.5,
  dragCoefficient: 1.02,
  liftCoefficient: 3.1,   // negative lift, i.e. downforce
  aeroBalance: 0.45,      // fraction of downforce on the front axle
  airDensity: 1.225,

  // Steering
  maxSteer: 0.30,         // rad at the road wheels (~17°) at parking speeds

  // Fuel
  fuelPerJoule: 1.0 / 43.0e6 / 0.32,   // kg per joule of crank work at ~32% thermal efficiency
  fuelIdleRate: 0.0025                 // kg/s burned just keeping the engine alive
};

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

/** Simplified Pacejka lateral force coefficient for a slip angle (rad). */
function pacejka(alpha, t) {
  const Bx = t.B * alpha;
  return t.D * Math.sin(t.C * Math.atan(Bx - t.E * (Bx - Math.atan(Bx))));
}

/** Crank torque (N·m) available at a given rpm, wide open throttle. */
function engineTorque(rpm) {
  if (rpm < 500 || rpm > SPEC.redline) return 0;
  const x = rpm / SPEC.torquePeakRpm;
  // Smooth hump: full torque at the peak, tapering either side.
  const shape = 1 - 0.55 * Math.pow(x - 1, 2) - 0.22 * Math.pow(Math.max(0, x - 1), 3) * 6;
  return SPEC.maxTorque * clamp(shape, 0, 1.05);
}

export class Vehicle {
  constructor() {
    this.reset();
  }

  reset(x = 0, z = 0, yaw = 0) {
    this.x = x; this.z = z; this.yaw = yaw;
    this.vx = 0;          // body-frame forward velocity (m/s)
    this.vy = 0;          // body-frame lateral velocity (m/s), +X = right
    this.yawRate = 0;
    this.gear = 0;        // index into SPEC.gears
    this.rpm = SPEC.idleRpm;
    this.shiftTimer = 0;
    this.steer = 0;       // current road-wheel angle (rad), rate limited
    this.wheelSpin = 0;   // accumulated wheel rotation for the visual model
    this.fuel = 0;        // kg, set by the game
    this.fuelUsed = 0;
    this.offTrack = false;
    this.gripScale = 1;   // reduced when running wide onto the runoff
    this.lastSlip = 0;    // peak |slip angle| last step, for tyre-squeal audio/FX
    this.driveForce = 0;
    this.speed = 0;
  }

  /** Engine rpm implied by road speed in the current gear. */
  rpmForSpeed(v, gear = this.gear) {
    const wheelRadPerSec = v / SPEC.wheelRadius;
    const rpm = wheelRadPerSec * SPEC.gears[gear] * SPEC.finalDrive * 60 / (2 * Math.PI);
    return clamp(rpm, SPEC.idleRpm, SPEC.redline);
  }

  /**
   * Advance one step.
   * @param {number} dt      seconds (caller should sub-step; ~1/120 is plenty)
   * @param {object} input   { throttle 0..1, brake 0..1, steer -1..1 }
   */
  step(dt, input) {
    const throttle = clamp(input.throttle || 0, 0, 1);
    const brake = clamp(input.brake || 0, 0, 1);

    // --- steering ------------------------------------------------------------
    const speed = Math.hypot(this.vx, this.vy);
    this.speed = speed;

    // Aero first, because the steering limit depends on how much grip the
    // downforce is currently generating.
    const q = 0.5 * SPEC.airDensity * SPEC.frontalArea * this.vx * this.vx;
    const downforce = q * SPEC.liftCoefficient;
    const W = SPEC.mass * 9.81;
    const L = SPEC.a + SPEC.b;

    // Cap the commanded steering at the angle that asks for exactly as much
    // lateral acceleration as the tyres can supply at this speed. Without this,
    // full lock at 200 km/h demands well over 10 g, the car simply spins, and
    // the rotating-frame integration diverges. It also matches how a real car
    // behaves: at speed you only ever use a few degrees of lock.
    const aMax = SPEC.tyre.rear.D * (W + downforce) / SPEC.mass * this.gripScale;
    const steerLimit = Math.min(SPEC.maxSteer, Math.atan(L * aMax / Math.max(speed * speed, 1)));
    this.steerLimit = steerLimit;
    const targetSteer = clamp(input.steer || 0, -1, 1) * steerLimit;
    const steerRate = 3.6;   // rad/s at the road wheels
    this.steer += clamp(targetSteer - this.steer, -steerRate * dt, steerRate * dt);

    // --- vertical loads with longitudinal transfer --------------------------
    const drag = q * SPEC.dragCoefficient * Math.sign(this.vx);
    const ax = this._lastAx || 0;
    const transfer = SPEC.mass * ax * SPEC.cgHeight / L;
    let Fzf = W * SPEC.b / L + downforce * SPEC.aeroBalance - transfer;
    let Fzr = W * SPEC.a / L + downforce * (1 - SPEC.aeroBalance) + transfer;
    Fzf = Math.max(Fzf, 200);
    Fzr = Math.max(Fzr, 200);

    // --- slip angles --------------------------------------------------------
    // Guard the low-speed singularity: below ~2 m/s slip angles are meaningless
    // and the model blows up, so fade the lateral tyre forces in.
    const vxSafe = Math.max(Math.abs(this.vx), 1.2) * (this.vx < 0 ? -1 : 1);
    const alphaF = Math.atan((this.vy + SPEC.a * this.yawRate) / Math.abs(vxSafe)) - this.steer;
    const alphaR = Math.atan((this.vy - SPEC.b * this.yawRate) / Math.abs(vxSafe));
    const lowSpeedFade = clamp((speed - 0.4) / 1.6, 0, 1);
    this.lastSlip = Math.max(Math.abs(alphaF), Math.abs(alphaR));

    const grip = this.gripScale;
    let Fyf = -pacejka(alphaF, SPEC.tyre.front) * Fzf * lowSpeedFade * grip;
    let Fyr = -pacejka(alphaR, SPEC.tyre.rear) * Fzr * lowSpeedFade * grip;

    // --- powertrain ---------------------------------------------------------
    this.shiftTimer = Math.max(0, this.shiftTimer - dt);
    this.rpm = this.rpmForSpeed(Math.abs(this.vx));
    if (this.shiftTimer === 0) {
      if (this.rpm > SPEC.shiftUpRpm && this.gear < SPEC.gears.length - 1) {
        this.gear++; this.shiftTimer = SPEC.shiftTime;
      } else if (this.rpm < SPEC.shiftDownRpm && this.gear > 0) {
        this.gear--; this.shiftTimer = SPEC.shiftTime;
      }
    }

    const cut = this.shiftTimer > 0 ? 0 : 1;
    const crankTorque = engineTorque(this.rpm) * throttle * cut;
    const wheelTorque = crankTorque * SPEC.gears[this.gear] * SPEC.finalDrive * SPEC.drivelineEfficiency;
    let Fxr = wheelTorque / SPEC.wheelRadius;

    // Traction limit at the driven axle (friction ellipse: whatever the tyre
    // spends cornering is not available for acceleration).
    const maxRear = SPEC.tyre.rear.D * Fzr * grip;
    const rearLatUse = clamp(Math.abs(Fyr) / maxRear, 0, 1);
    const rearLongCap = maxRear * Math.sqrt(Math.max(0, 1 - rearLatUse * rearLatUse));
    if (Math.abs(Fxr) > rearLongCap) {
      Fxr = Math.sign(Fxr) * rearLongCap;
      this.wheelspin = true;
    } else this.wheelspin = false;

    // --- brakes -------------------------------------------------------------
    let Fxf = 0;
    if (brake > 0.001) {
      const total = SPEC.maxBrakeTorque * brake / SPEC.wheelRadius;
      const bf = total * SPEC.brakeBias;
      const br = total * (1 - SPEC.brakeBias);
      const maxFront = SPEC.tyre.front.D * Fzf * grip;
      const frontLatUse = clamp(Math.abs(Fyf) / maxFront, 0, 1);
      const frontCap = maxFront * Math.sqrt(Math.max(0, 1 - frontLatUse * frontLatUse));

      const dir = this.vx >= 0 ? -1 : 1;
      Fxf = dir * Math.min(bf, frontCap);
      Fxr += dir * Math.min(br, Math.max(0, rearLongCap - Math.abs(Fxr)));

      // Stop cleanly instead of jittering backwards at a standstill.
      if (Math.abs(this.vx) < 0.35 && throttle < 0.02) {
        this.vx = 0; Fxf = 0; Fxr = 0;
      }
    }

    // --- resistances --------------------------------------------------------
    const rolling = SPEC.rollingResistance * (Fzf + Fzr) * Math.sign(this.vx);
    const Fx = Fxr + Fxf - drag - rolling + Fyf * Math.sin(-this.steer);
    const Fy = Fyf * Math.cos(this.steer) + Fyr;
    this.driveForce = Fxr;

    // --- rigid body integration (body frame, semi-implicit Euler) -----------
    const axBody = Fx / SPEC.mass + this.yawRate * this.vy;
    const ayBody = Fy / SPEC.mass - this.yawRate * this.vx;
    const yawAccel = (SPEC.a * Fyf * Math.cos(this.steer) - SPEC.b * Fyr) / SPEC.yawInertia;
    this._lastAx = axBody;

    this.vx += axBody * dt;
    this.vy += ayBody * dt;
    this.yawRate += yawAccel * dt;

    // Backstop: a fully crossed-up slide is legitimate, an unbounded one is a
    // numerical artefact of integrating the rotating frame explicitly. Clamp to
    // values a real car can reach so a spin stays a spin instead of exploding.
    this.yawRate = clamp(this.yawRate, -4.5, 4.5);
    this.vy = clamp(this.vy, -60, 60);
    this.vx = clamp(this.vx, -30, 120);

    // Bleed off yaw rate at a crawl so the car settles instead of pirouetting.
    if (speed < 1.0) this.yawRate *= Math.pow(0.02, dt);

    // --- world-frame pose ---------------------------------------------------
    const sin = Math.sin(this.yaw), cos = Math.cos(this.yaw);
    this.x += (this.vx * sin + this.vy * cos) * dt;
    this.z += (this.vx * cos - this.vy * sin) * dt;
    this.yaw += this.yawRate * dt;

    this.wheelSpin += (this.vx / SPEC.wheelRadius) * dt;

    // --- fuel ---------------------------------------------------------------
    // Burn is proportional to the actual crank work done, plus an idle draw.
    const crankPower = Math.max(0, crankTorque) * (this.rpm * 2 * Math.PI / 60);
    const burn = (crankPower * SPEC.fuelPerJoule + SPEC.fuelIdleRate) * dt;
    this.fuel = Math.max(0, this.fuel - burn);
    this.fuelUsed += burn;
    if (this.fuel <= 0) this.outOfFuel = true;

    return this;
  }

  /** Kill forward motion on a wall hit and bounce the car back onto the track. */
  collide(normalX, normalZ, penetration) {
    // Push out of the wall.
    this.x += normalX * penetration;
    this.z += normalZ * penetration;

    // Split velocity into wall-normal and wall-tangent parts in world space.
    const sin = Math.sin(this.yaw), cos = Math.cos(this.yaw);
    let wvx = this.vx * sin + this.vy * cos;
    let wvz = this.vx * cos - this.vy * sin;
    const vn = wvx * normalX + wvz * normalZ;
    if (vn < 0) {
      // Absorb most of the impact, keep a little scrape along the barrier.
      wvx -= normalX * vn * 1.25;
      wvz -= normalZ * vn * 1.25;
      const scrub = 0.72;
      wvx *= scrub; wvz *= scrub;
    }
    this.vx = wvx * sin + wvz * cos;
    this.vy = wvx * cos - wvz * sin;
    this.yawRate *= 0.4;
    return Math.abs(vn);
  }
}

export { engineTorque, pacejka };
