/**
 * Procedural race tracks.
 *
 * A track is a Catmull-Rom curve through generated control points, sampled into
 * an array of frames { pos, tangent, right, distance }. Everything else — the
 * road mesh, kerbs, barriers, checkpoints and collision — is derived from those
 * frames, so the physics and the visuals can never disagree about where the
 * track is.
 *
 * Difficulty rises across levels: corners get tighter and more frequent, the
 * road narrows, the lap gets longer, and the fuel margin shrinks.
 */
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

/** Deterministic PRNG so a level number always yields the same circuit. */
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const LEVELS = [
  // Tuned against tools/autopilot.mjs: `corners` and `minRadius` set the
  // difficulty (measured by the autopilot's average speed, which falls from
  // ~248 km/h on level 1 to ~151 km/h on level 6), and `fuel` is the autopilot's
  // measured consumption times a margin that tightens as the levels go on.
  { name: 'Fiorano Shakedown', seed: 101, corners:  6, length: 1500, minRadius: 100, width: 15.0, fuel: 2.45 },
  { name: 'Monza Sprint',      seed: 202, corners: 10, length: 1900, minRadius:  72, width: 14.0, fuel: 2.40 },
  { name: 'Suzuka Esses',      seed: 303, corners: 14, length: 2300, minRadius:  58, width: 13.0, fuel: 2.50 },
  { name: 'Monaco Tight',      seed: 404, corners: 18, length: 2600, minRadius:  46, width: 11.5, fuel: 2.05 },
  { name: 'Spa Endurance',     seed: 505, corners: 26, length: 3000, minRadius:  38, width: 11.0, fuel: 2.40 },
  { name: 'The Gauntlet',      seed: 623, corners: 40, length: 3600, minRadius:  26, width: 10.0, fuel: 2.20 }
];

export function levelConfig(index) {
  if (index < LEVELS.length) return { ...LEVELS[index], index };
  // Beyond the hand-tuned set, keep escalating forever.
  const over = index - LEVELS.length + 1;
  const last = LEVELS[LEVELS.length - 1];
  return {
    ...last,
    index,
    name: `The Gauntlet +${over}`,
    seed: 700 + over * 37,
    corners: last.corners + over * 4,
    length: last.length + over * 220,
    minRadius: Math.max(22, last.minRadius - over),
    width: Math.max(9, last.width - over * 0.25),
    fuel: last.fuel + over * 0.16
  };
}

/**
 * Lay out control points as a closed loop: an irregular ring whose radial
 * variation drives how severe the corners are.
 */
function controlPoints(cfg) {
  const rnd = mulberry32(cfg.seed);
  const n = cfg.corners + 4;
  const pts = [];
  const baseRadius = cfg.length / (2 * Math.PI);
  for (let i = 0; i < n; i++) {
    const t = i / n;
    const angle = t * Math.PI * 2 + (rnd() - 0.5) * (Math.PI * 2 / n) * 0.55;
    const severity = 0.20 + 0.42 * (1 - cfg.minRadius / 100);
    const r = baseRadius * (1 + (rnd() - 0.5) * 2 * severity);
    pts.push(new THREE.Vector3(Math.cos(angle) * r, 0, Math.sin(angle) * r));
  }
  return pts;
}

/** Sample the curve into evenly spaced frames with a right-hand vector. */
function buildFrames(curve, spacing = 3) {
  const approxLength = curve.getLength();
  const count = Math.max(64, Math.round(approxLength / spacing));
  const frames = [];
  const up = new THREE.Vector3(0, 1, 0);
  let distance = 0;
  let prev = null;
  for (let i = 0; i <= count; i++) {
    const t = i / count;
    const pos = curve.getPointAt(t);
    const tangent = curve.getTangentAt(t).normalize();
    const right = new THREE.Vector3().crossVectors(tangent, up).normalize().negate();
    if (prev) distance += pos.distanceTo(prev);
    frames.push({ pos, tangent, right, distance, t });
    prev = pos;
  }
  return frames;
}

/** Signed curvature at each frame, used for kerbs and for the speed model. */
function curvatures(frames) {
  const k = new Float32Array(frames.length);
  for (let i = 1; i < frames.length - 1; i++) {
    const a = frames[i - 1].tangent, b = frames[i + 1].tangent;
    const cross = a.x * b.z - a.z * b.x;
    const ds = frames[i + 1].distance - frames[i - 1].distance || 1;
    k[i] = Math.asin(Math.max(-1, Math.min(1, cross))) / ds;
  }
  k[0] = k[1]; k[k.length - 1] = k[k.length - 2];
  return k;
}

/**
 * Build a circuit that actually obeys the level's stated minimum radius.
 *
 * The raw control points can produce hairpins far tighter than the car can
 * physically negotiate, which just pinballs you off the barriers. So: sample,
 * measure the sharpest corner, and if it is too tight, relax the control points
 * toward their neighbours and try again.
 */
function fitCircuit(cfg) {
  let pts = controlPoints(cfg);
  const maxCurvature = 1 / cfg.minRadius;
  let curve, frames, curvature;

  const sample = () => {
    curve = new THREE.CatmullRomCurve3(pts, true, 'catmullrom', 0.5);
    frames = buildFrames(curve, 3);
    curvature = curvatures(frames);
    return frames[frames.length - 1].distance;
  };

  for (let pass = 0; pass < 30; pass++) {
    // Restore the intended lap distance first, so the curvature check below
    // always applies to the geometry we will actually race on. (Scaling up
    // relaxes curvature, so doing it afterwards would wash the corners out.)
    const measured = sample();
    const scale = cfg.length / measured;
    if (Math.abs(scale - 1) > 0.005) {
      pts = pts.map(p => new THREE.Vector3(p.x * scale, 0, p.z * scale));
      sample();
    }

    let worst = 0;
    for (let i = 0; i < curvature.length; i++) worst = Math.max(worst, Math.abs(curvature[i]));
    if (worst <= maxCurvature) break;

    // Laplacian relaxation: pull each point toward the midpoint of its
    // neighbours, which rounds off the hairpins without unravelling the shape.
    const n = pts.length;
    pts = pts.map((p, i) => {
      const a = pts[(i - 1 + n) % n], b = pts[(i + 1) % n];
      return new THREE.Vector3(
        p.x + ((a.x + b.x) / 2 - p.x) * 0.18,
        0,
        p.z + ((a.z + b.z) / 2 - p.z) * 0.18
      );
    });
  }

  let tightest = Infinity;
  for (let i = 0; i < curvature.length; i++) {
    if (Math.abs(curvature[i]) > 1e-6) tightest = Math.min(tightest, 1 / Math.abs(curvature[i]));
  }
  return { curve, frames, curvature, tightestRadius: tightest };
}

export class Track {
  constructor(cfg) {
    this.cfg = cfg;
    this.halfWidth = cfg.width / 2;
    this.wallOffset = this.halfWidth + 3.0;   // barriers sit back from the tarmac

    const { curve, frames, curvature, tightestRadius } = fitCircuit(cfg);
    this.tightestRadius = tightestRadius;
    this.curve = curve;
    this.frames = frames;
    this.curvature = curvature;
    this.length = frames[frames.length - 1].distance;

    // Checkpoints every ~1/8 of the lap; the last one is the finish line.
    this.checkpointCount = 8;
    this.checkpoints = [];
    for (let i = 1; i <= this.checkpointCount; i++) {
      const idx = Math.min(this.frames.length - 1,
        Math.round((i / this.checkpointCount) * (this.frames.length - 1)));
      this.checkpoints.push({ index: idx, distance: this.frames[idx].distance, passed: false });
    }

    this.startFrame = this.frames[0];
    this.mesh = this._buildMesh();
  }

  /** Yaw (radians, renderer convention) that points along the track at a frame. */
  headingAt(index) {
    const t = this.frames[index].tangent;
    return Math.atan2(t.x, t.z);
  }

  get startPose() {
    return { x: this.startFrame.pos.x, z: this.startFrame.pos.z, yaw: this.headingAt(0) };
  }

  /**
   * Locate a world position relative to the track.
   * Walks out from the last known frame, so this is O(few) per call rather than
   * a scan of the whole circuit.
   */
  locate(x, z, hint = 0) {
    const n = this.frames.length;
    let best = hint, bestD2 = Infinity;
    const span = 40;
    for (let o = -span; o <= span; o++) {
      const i = ((hint + o) % n + n) % n;
      const p = this.frames[i].pos;
      const d2 = (p.x - x) * (p.x - x) + (p.z - z) * (p.z - z);
      if (d2 < bestD2) { bestD2 = d2; best = i; }
    }
    const f = this.frames[best];
    const dx = x - f.pos.x, dz = z - f.pos.z;
    const lateral = dx * f.right.x + dz * f.right.z;   // + = right of centreline
    return { index: best, lateral, distance: f.distance, frame: f, curvature: this.curvature[best] };
  }

  /**
   * Grip and barrier response for a car position.
   * Returns { grip, hit } where hit, if present, carries the wall normal.
   */
  surface(x, z, hint) {
    const loc = this.locate(x, z, hint);
    const off = Math.abs(loc.lateral);
    const out = { loc, grip: 1, offTrack: false, hit: null };

    if (off > this.halfWidth) {
      out.offTrack = true;
      // Tarmac -> kerb -> gravel: grip falls away the further you run wide.
      const over = off - this.halfWidth;
      out.grip = Math.max(0.42, 1 - over * 0.28);
    }
    if (off > this.wallOffset) {
      const side = Math.sign(loc.lateral);
      const f = loc.frame;
      out.hit = {
        // Normal points back toward the centreline.
        nx: -f.right.x * side,
        nz: -f.right.z * side,
        penetration: off - this.wallOffset
      };
    }
    return out;
  }

  /* ------------------------------------------------------------- geometry */

  _buildMesh() {
    const group = new THREE.Group();
    const frames = this.frames;
    const hw = this.halfWidth;

    const road = this._ribbon(frames, -hw, hw, 0.02, true);
    road.material = new THREE.MeshStandardMaterial({
      color: 0x2b2e34, roughness: 0.94, metalness: 0.0
    });
    road.receiveShadow = true;
    group.add(road);

    // Painted white edge lines.
    [-1, 1].forEach(side => {
      const line = this._ribbon(frames, side * (hw - 0.45), side * (hw - 0.15), 0.025, false);
      line.material = new THREE.MeshStandardMaterial({ color: 0xe8e8e8, roughness: 0.7 });
      group.add(line);
    });

    // Red/white kerbs, only where the track actually bends.
    group.add(this._kerbs());

    // Run-off apron so the world does not just end at the white line.
    const apron = this._ribbon(frames, -this.wallOffset - 6, this.wallOffset + 6, -0.06, false);
    apron.material = new THREE.MeshStandardMaterial({ color: 0x1c2a1e, roughness: 1.0 });
    apron.receiveShadow = true;
    group.add(apron);

    // Gravel traps between the white line and the barrier.
    [-1, 1].forEach(side => {
      const trap = this._ribbon(frames, side * (hw + 0.2), side * this.wallOffset, 0.0, false);
      trap.material = new THREE.MeshStandardMaterial({ color: 0x6b5a3e, roughness: 1.0 });
      group.add(trap);
    });

    group.add(this._barriers());
    group.add(this._startGantry());
    this.checkpointMeshes = this._checkpointGates();
    this.checkpointMeshes.forEach(m => group.add(m));

    return group;
  }

  /** A flat ribbon between two lateral offsets along the whole circuit. */
  _ribbon(frames, fromOffset, toOffset, y, closed) {
    const pos = [], uv = [], idx = [];
    for (let i = 0; i < frames.length; i++) {
      const f = frames[i];
      pos.push(f.pos.x + f.right.x * fromOffset, y, f.pos.z + f.right.z * fromOffset);
      pos.push(f.pos.x + f.right.x * toOffset,   y, f.pos.z + f.right.z * toOffset);
      const v = f.distance / 8;
      uv.push(0, v, 1, v);
    }
    for (let i = 0; i < frames.length - 1; i++) {
      const a = i * 2, b = a + 1, c = a + 2, d = a + 3;
      idx.push(a, c, b, b, c, d);
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setIndex(idx);
    g.computeVertexNormals();
    return new THREE.Mesh(g, new THREE.MeshStandardMaterial());
  }

  /**
   * Kerbs on the inside of every corner.
   *
   * Each stripe is a transformed box baked into one of two merged geometries
   * (red and white). A long circuit has several hundred stripes, and leaving
   * them as individual meshes costs several hundred draw calls per frame plus
   * the same again for the shadow pass — enough to halve the frame rate on a
   * phone. Merged, it is two.
   */
  _kerbs() {
    const group = new THREE.Group();
    const box = new THREE.BoxGeometry(1, 1, 1);
    const parts = [[], []];
    const m = new THREE.Matrix4(), q = new THREE.Quaternion();
    const up = new THREE.Vector3(0, 1, 0);
    let stripe = 0;

    for (let i = 0; i < this.frames.length - 1; i += 2) {
      const k = this.curvature[i];
      if (Math.abs(k) < 0.004) continue;              // straight enough: no kerb
      const side = k > 0 ? 1 : -1;                    // kerb on the inside of the turn
      const f = this.frames[i], f2 = this.frames[i + 2] || this.frames[i + 1];
      const seg = f.pos.distanceTo(f2.pos);
      const off = side * (this.halfWidth + 0.55);
      q.setFromAxisAngle(up, Math.atan2(f.tangent.x, f.tangent.z));
      m.compose(
        new THREE.Vector3(f.pos.x + f.right.x * off, 0.05, f.pos.z + f.right.z * off),
        q,
        new THREE.Vector3(1.1, 0.10, Math.max(seg, 1.5))
      );
      parts[stripe++ % 2].push(box.clone().applyMatrix4(m));
    }

    const colours = [0xcc2222, 0xededed];
    parts.forEach((list, i) => {
      if (!list.length) return;
      const merged = mergeGeometries(list);
      list.forEach(g => g.dispose());
      const mesh = new THREE.Mesh(merged, new THREE.MeshStandardMaterial({ color: colours[i], roughness: 0.8 }));
      mesh.receiveShadow = true;
      group.add(mesh);
    });
    box.dispose();
    return group;
  }

  /** Armco down both sides, merged into one rail mesh and one post mesh. */
  _barriers() {
    const group = new THREE.Group();
    const box = new THREE.BoxGeometry(1, 1, 1);
    const rails = [], posts = [];
    const m = new THREE.Matrix4(), q = new THREE.Quaternion();
    const up = new THREE.Vector3(0, 1, 0);

    for (const side of [-1, 1]) {
      for (let i = 0; i < this.frames.length - 1; i += 3) {
        const f = this.frames[i], f2 = this.frames[Math.min(i + 3, this.frames.length - 1)];
        const seg = Math.max(f.pos.distanceTo(f2.pos), 2);
        const off = side * this.wallOffset;
        const x = f.pos.x + f.right.x * off, z = f.pos.z + f.right.z * off;
        q.setFromAxisAngle(up, Math.atan2(f.tangent.x, f.tangent.z));

        m.compose(new THREE.Vector3(x, 0.55, z), q, new THREE.Vector3(0.20, 0.55, seg * 1.05));
        rails.push(box.clone().applyMatrix4(m));

        if (i % 12 === 0) {
          m.compose(
            new THREE.Vector3(x + f.right.x * side * 0.2, 0.4, z + f.right.z * side * 0.2),
            q, new THREE.Vector3(0.16, 0.8, 0.16)
          );
          posts.push(box.clone().applyMatrix4(m));
        }
      }
    }

    const build = (list, material) => {
      if (!list.length) return;
      const merged = mergeGeometries(list);
      list.forEach(g => g.dispose());
      const mesh = new THREE.Mesh(merged, material);
      mesh.castShadow = true;
      group.add(mesh);
    };
    build(rails, new THREE.MeshStandardMaterial({ color: 0xb9c0c8, roughness: 0.45, metalness: 0.6 }));
    build(posts, new THREE.MeshStandardMaterial({ color: 0x33383f, roughness: 0.8 }));
    box.dispose();
    return group;
  }

  _startGantry() {
    const g = new THREE.Group();
    const f = this.frames[0];
    const yaw = Math.atan2(f.tangent.x, f.tangent.z);
    const dark = new THREE.MeshStandardMaterial({ color: 0x22262c, roughness: 0.6, metalness: 0.4 });

    // Start/finish line: a checkerboard, merged into two meshes.
    const squares = 16;
    const cell = new THREE.BoxGeometry(this.cfg.width / squares, 0.02, 0.6);
    const tiles = [[], []];
    const m = new THREE.Matrix4();
    const q = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), yaw);
    for (let i = 0; i < squares; i++) {
      for (let j = 0; j < 2; j++) {
        const lat = -this.halfWidth + (i + 0.5) * (this.cfg.width / squares);
        const along = (j - 0.5) * 0.6;
        m.compose(new THREE.Vector3(
          f.pos.x + f.right.x * lat + f.tangent.x * along, 0.04,
          f.pos.z + f.right.z * lat + f.tangent.z * along
        ), q, new THREE.Vector3(1, 1, 1));
        tiles[(i + j) % 2].push(cell.clone().applyMatrix4(m));
      }
    }
    [0x111111, 0xf5f5f5].forEach((colour, i) => {
      const merged = mergeGeometries(tiles[i]);
      tiles[i].forEach(t => t.dispose());
      g.add(new THREE.Mesh(merged, new THREE.MeshStandardMaterial({ color: colour, roughness: 0.8 })));
    });
    cell.dispose();

    // Overhead gantry.
    for (const side of [-1, 1]) {
      const leg = new THREE.Mesh(new THREE.BoxGeometry(0.4, 7, 0.4), dark);
      leg.position.set(
        f.pos.x + f.right.x * side * (this.halfWidth + 1.5), 3.5,
        f.pos.z + f.right.z * side * (this.halfWidth + 1.5)
      );
      leg.castShadow = true;
      g.add(leg);
    }
    const beam = new THREE.Mesh(new THREE.BoxGeometry(this.cfg.width + 3.6, 1.0, 0.6), dark);
    beam.position.set(f.pos.x, 7.0, f.pos.z);
    beam.rotation.y = yaw;
    beam.castShadow = true;
    g.add(beam);
    return g;
  }

  _checkpointGates() {
    return this.checkpoints.map((cp, i) => {
      const f = this.frames[cp.index];
      const yaw = Math.atan2(f.tangent.x, f.tangent.z);
      const gate = new THREE.Group();
      const isFinish = i === this.checkpoints.length - 1;
      const mat = new THREE.MeshBasicMaterial({
        color: isFinish ? 0x00ff88 : 0x33aaff, transparent: true, opacity: 0.30,
        side: THREE.DoubleSide, depthWrite: false
      });
      const pane = new THREE.Mesh(new THREE.PlaneGeometry(this.cfg.width, 5), mat);
      pane.position.set(f.pos.x, 2.5, f.pos.z);
      pane.rotation.y = yaw;
      gate.add(pane);

      for (const side of [-1, 1]) {
        const pole = new THREE.Mesh(
          new THREE.CylinderGeometry(0.16, 0.16, 5.2, 10),
          new THREE.MeshStandardMaterial({ color: isFinish ? 0x00ff88 : 0x33aaff, emissive: isFinish ? 0x005522 : 0x001e33 })
        );
        pole.position.set(
          f.pos.x + f.right.x * side * this.halfWidth, 2.6,
          f.pos.z + f.right.z * side * this.halfWidth
        );
        gate.add(pole);
      }
      gate.userData.checkpoint = cp;
      return gate;
    });
  }

  dispose() {
    this.mesh.traverse(o => {
      if (o.geometry) o.geometry.dispose();
      if (o.material) (Array.isArray(o.material) ? o.material : [o.material]).forEach(m => m.dispose());
    });
  }
}
