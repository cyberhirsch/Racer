/**
 * Round-trip test for the tilt-steering maths in src/controls.js.
 *
 * Rather than guessing device angles, this builds the rotation matrix for a
 * phone physically held in landscape and rolled by a known angle, extracts the
 * alpha/beta/gamma the browser would report for that pose, feeds them through
 * the same projection the game uses, and checks the recovered steering angle
 * matches the roll that was applied.
 *
 * Run: node tools/tilt-check.mjs
 */
import * as THREE from 'three';

/** The exact projection used by Controls._onOrientation. */
const wrapPi = a => Math.atan2(Math.sin(a), Math.cos(a));

function screenRoll(betaDeg, gammaDeg, screenAngleDeg) {
  const b = betaDeg * Math.PI / 180;
  const g = gammaDeg * Math.PI / 180;
  let gx = Math.cos(b) * Math.sin(g);
  let gy = -Math.sin(b);
  const a = screenAngleDeg * Math.PI / 180;
  const ca = Math.cos(a), sa = Math.sin(a);
  const sx = gx * ca + gy * sa;
  const sy = -gx * sa + gy * ca;
  return Math.atan2(sx, sy);
}

/** Device pose -> the alpha/beta/gamma a browser reports (Z-X'-Y'' intrinsic). */
function anglesFor(quaternion) {
  const e = new THREE.Euler().setFromQuaternion(quaternion, 'ZXY');
  return { alpha: e.z * 180 / Math.PI, beta: e.x * 180 / Math.PI, gamma: e.y * 180 / Math.PI };
}

/**
 * Phone held facing the user at a base rotation about the screen normal
 * (0 = portrait, +90 deg = landscape), then rolled (steered) by `roll` radians
 * about that same normal, with an optional pitch toward/away from the user.
 */
function pose(roll, pitch = 0, base = Math.PI / 2) {
  const q = new THREE.Quaternion();
  // Start portrait-upright facing the user: rotate +90° about earth X.
  q.setFromAxisAngle(new THREE.Vector3(1, 0, 0), Math.PI / 2);
  // Pitch the screen toward/away from the user, about the device's own X.
  q.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), pitch));
  // Rotate into landscape (90° about the screen normal) and then apply the
  // player's steering roll, both about the device Z axis.
  q.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), base + roll));
  return anglesFor(q);
}

const CASES = [
  { label: 'landscape (home button right)', screenAngle: 90,  base: Math.PI / 2 },
  { label: 'landscape (home button left)',  screenAngle: 270, base: -Math.PI / 2 },
  { label: 'portrait',                      screenAngle: 0,   base: 0 }
];

let pass = true;
for (const c of CASES) {
  let worst = 0, monotonic = true, prev = -Infinity;
  const rows = [];

  // The game never uses the absolute angle: it calibrates a neutral at the
  // attitude the player is holding, then steers on the difference. Test that.
  const n0 = pose(0, 0, c.base);
  const neutral = screenRoll(n0.beta, n0.gamma, c.screenAngle);

  for (let deg = -40; deg <= 40; deg += 10) {
    const roll = deg * Math.PI / 180;
    const { beta, gamma } = pose(roll, 0, c.base);
    const recovered = wrapPi(screenRoll(beta, gamma, c.screenAngle) - neutral);
    const err = Math.abs(recovered - roll) * 180 / Math.PI;
    worst = Math.max(worst, err);
    if (recovered < prev) monotonic = false;
    prev = recovered;
    rows.push(`${String(deg).padStart(4)}->${(recovered * 180 / Math.PI).toFixed(1).padStart(7)}`);
  }
  const ok = worst < 0.5 && monotonic;
  pass = pass && ok;
  console.log(`${c.label.padEnd(32)} ${ok ? 'PASS' : 'FAIL'}  worst err ${worst.toFixed(3)}deg`);
  console.log(`  ${rows.join('  ')}`);
}

// Pitch independence: tilting the phone toward you must not fake a steering
// input. This is the whole reason for projecting gravity instead of reading
// `gamma` directly.
console.log('\npitch independence in landscape (roll held at 0):');
let pitchWorst = 0;
for (const pitchDeg of [-40, -20, 0, 20, 40]) {
  const n = pose(0, 0, Math.PI / 2);
  const neutral = screenRoll(n.beta, n.gamma, 90);
  const { beta, gamma } = pose(0, pitchDeg * Math.PI / 180, Math.PI / 2);
  const r = wrapPi(screenRoll(beta, gamma, 90) - neutral) * 180 / Math.PI;
  pitchWorst = Math.max(pitchWorst, Math.abs(r));
  console.log(`  pitch ${String(pitchDeg).padStart(4)}deg -> steering ${r.toFixed(2)}deg`);
}
const pitchOk = pitchWorst < 0.5;
pass = pass && pitchOk;
console.log(`  ${pitchOk ? 'PASS' : 'FAIL'} (worst ${pitchWorst.toFixed(3)}deg)`);

console.log('\n' + (pass ? 'PASS' : 'FAIL'));
process.exit(pass ? 0 : 1);
