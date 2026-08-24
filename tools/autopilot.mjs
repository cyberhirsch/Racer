/**
 * Headless autopilot. Drives each generated circuit with a simple but
 * competent racing driver so we can check that every level is actually
 * completable, and measure how much fuel a clean run consumes.
 *
 * Run: node tools/autopilot.mjs [levelCount]
 *
 * The driver is deliberately not perfect — it follows the centreline rather
 * than a racing line — so the fuel it burns is a fair "decent human" budget.
 */
import { Vehicle, SPEC } from '../src/physics.js';
import { Track, levelConfig } from '../src/track.js';

const H = 1 / 120;

/** Pure-pursuit steering plus a curvature-derived speed target. */
function drive(v, track, hint) {
  const loc = track.locate(v.x, v.z, hint);
  const n = track.frames.length - 1;

  // Look ahead a distance that scales with speed.
  const lookAhead = 8 + v.speed * 0.55;
  const step = track.length / n;
  const aheadIdx = (loc.index + Math.round(lookAhead / step)) % n;
  const target = track.frames[aheadIdx].pos;

  // Steering: angle to the aim point, in the car's frame.
  const dx = target.x - v.x, dz = target.z - v.z;
  const local = Math.atan2(dx, dz) - v.yaw;
  const err = Math.atan2(Math.sin(local), Math.cos(local));
  const steer = Math.max(-1, Math.min(1, err / (v.steerLimit || SPEC.maxSteer) * 0.9));

  // Speed target: worst curvature over the next few hundred metres sets the
  // braking point, via v = sqrt(a_lat_max / |k|).
  let worst = 0;
  const scan = Math.round((30 + v.speed * 2.2) / step);
  for (let i = 0; i < scan; i++) {
    worst = Math.max(worst, Math.abs(track.curvature[(loc.index + i) % n]));
  }
  const q = 0.5 * SPEC.airDensity * SPEC.frontalArea * v.speed * v.speed;
  const aLat = SPEC.tyre.rear.D * (SPEC.mass * 9.81 + q * SPEC.liftCoefficient) / SPEC.mass;
  const vTarget = worst > 1e-5 ? Math.sqrt(aLat / worst) : 110;

  // Keep it near the centreline if it has drifted wide.
  const correction = Math.max(-0.35, Math.min(0.35, -loc.lateral * 0.06));

  const dv = vTarget - v.speed;
  return {
    steer: Math.max(-1, Math.min(1, steer + correction)),
    throttle: dv > 1 ? Math.min(1, dv / 6) : 0,
    brake: dv < -1.5 ? Math.min(1, -dv / 12) : 0,
    hint: loc.index
  };
}

export function simulate(levelIndex, { maxSeconds = 400, fuel = 1e6 } = {}) {
  const cfg = levelConfig(levelIndex);
  const track = new Track(cfg);
  const v = new Vehicle();
  const pose = track.startPose;
  v.reset(pose.x, pose.z, pose.yaw);
  v.fuel = fuel;

  let hint = 0, t = 0, next = 0, hits = 0, offTrackTime = 0, topSpeed = 0;
  while (t < maxSeconds && next < track.checkpoints.length) {
    const input = drive(v, track, hint);
    hint = input.hint;

    const surf = track.surface(v.x, v.z, hint);
    v.gripScale = surf.grip;
    if (surf.offTrack) offTrackTime += H;
    v.step(H, input);
    if (surf.hit) { v.collide(surf.hit.nx, surf.hit.nz, surf.hit.penetration + 0.02); hits++; }

    const cp = track.checkpoints[next];
    const loc = track.locate(v.x, v.z, hint);
    if (Math.abs(loc.index - cp.index) < 12) next++;

    topSpeed = Math.max(topSpeed, v.speed);
    t += H;
    if (v.fuel <= 0) break;
  }

  return {
    name: cfg.name, cfg, track,
    finished: next >= track.checkpoints.length,
    time: t, fuel: v.fuelUsed, hits, offTrackTime, topSpeed,
    trackLength: track.length,
    tightestRadius: track.tightestRadius,
    avgSpeed: track.length / t
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const count = Number(process.argv[2] || 6);
  console.log('lvl  track                 len   time    avg     top    fuel    hits  off   minR');
  for (let i = 0; i < count; i++) {
    const r = simulate(i);
    console.log(
      String(i + 1).padEnd(4),
      r.name.padEnd(21),
      `${Math.round(r.trackLength)}m`.padStart(5),
      (r.finished ? `${r.time.toFixed(1)}s` : 'DNF').padStart(7),
      `${Math.round(r.avgSpeed * 3.6)}`.padStart(5),
      `${Math.round(r.topSpeed * 3.6)}`.padStart(6),
      `${r.fuel.toFixed(2)}kg`.padStart(8),
      String(r.hits).padStart(5),
      `${r.offTrackTime.toFixed(1)}s`.padStart(6),
      `R${Math.round(r.tightestRadius)}m`.padStart(7)
    );
  }
}
