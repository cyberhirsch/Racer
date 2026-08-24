/**
 * Game shell: scene, camera, HUD and the level flow.
 *
 * The camera deliberately never rolls — its up vector is pinned to world +Y —
 * so however far the player rotates the phone to steer, the horizon on screen
 * stays level. Only the car turns.
 */
import * as THREE from 'three';
import { buildCar } from './car.js';
import { Vehicle, SPEC } from './physics.js';
import { Track, levelConfig, LEVELS } from './track.js';
import { Controls } from './controls.js';

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const $ = id => document.getElementById(id);

export class Game {
  constructor(canvas) {
    this.canvas = canvas;
    this._initRenderer();
    this._initScene();

    this.vehicle = new Vehicle();
    const built = buildCar();
    this.carGroup = built.car;
    this.carWheels = built.wheels;
    this.scene.add(this.carGroup);

    this.controls = new Controls().attach(canvas);
    this.state = 'menu';            // menu | countdown | racing | finished | failed
    this.levelIndex = 0;
    this.trackHint = 0;
    this.best = this._loadBest();

    this._bindUI();
    addEventListener('resize', () => this._resize());
    this._resize();

    this.clock = new THREE.Clock();
    this.accumulator = 0;
    this.renderer.setAnimationLoop(() => this._frame());
  }

  /* ------------------------------------------------------------- rendering */

  /**
   * Quality tiers. Phones get a smaller shadow map and a capped pixel ratio by
   * default; `?quality=low|medium|high` overrides for testing or for a device
   * that guesses wrong.
   */
  _quality() {
    const forced = new URLSearchParams(location.search).get('quality');
    if (['low', 'medium', 'high'].includes(forced)) return forced;
    return matchMedia('(pointer: coarse)').matches ? 'medium' : 'high';
  }

  _initRenderer() {
    const q = this.quality = this._quality();
    const antialias = q !== 'low';

    this.renderer = new THREE.WebGLRenderer({ canvas: this.canvas, antialias, powerPreference: 'high-performance' });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, q === 'high' ? 2 : 1.5));
    this.renderer.shadowMap.enabled = q !== 'low';
    this.renderer.shadowMap.type = q === 'high' ? THREE.PCFSoftShadowMap : THREE.PCFShadowMap;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.0;
    this.shadowSize = q === 'high' ? 2048 : 1024;
  }

  _initScene() {
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x8fb6dd);
    scene.fog = new THREE.Fog(0x8fb6dd, 120, 420);
    this.scene = scene;

    scene.add(new THREE.HemisphereLight(0xbcd8ff, 0x3d4a35, 1.05));

    const sun = new THREE.DirectionalLight(0xfff3e0, 2.5);
    sun.position.set(80, 120, 60);
    sun.castShadow = this.renderer.shadowMap.enabled;
    sun.shadow.mapSize.set(this.shadowSize, this.shadowSize);
    sun.shadow.camera.near = 20;
    sun.shadow.camera.far = 320;
    const d = 45;
    Object.assign(sun.shadow.camera, { left: -d, right: d, top: d, bottom: -d });
    sun.shadow.bias = -0.0006;
    this.sun = sun;
    scene.add(sun);
    scene.add(sun.target);

    // Ground plane well below the track, so there is scenery beyond the run-off.
    const ground = new THREE.Mesh(
      new THREE.PlaneGeometry(4000, 4000),
      new THREE.MeshStandardMaterial({ color: 0x33502f, roughness: 1 })
    );
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.12;
    ground.receiveShadow = true;
    scene.add(ground);

    this._baseFov = 52;
    this.camera = new THREE.PerspectiveCamera(this._baseFov, 1, 0.4, 2000);
    this.camera.position.set(0, 6, -14);

    // Studio-ish environment for the car's clearcoat.
    const pmrem = new THREE.PMREMGenerator(this.renderer);
    const env = new THREE.Scene();
    env.background = new THREE.Color(0x9ec4e8);
    const sky = new THREE.Mesh(new THREE.SphereGeometry(50, 8, 8), new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.BackSide }));
    env.add(sky);
    scene.environment = pmrem.fromScene(env, 0.1).texture;

    this._camPos = new THREE.Vector3(0, 6, -14);
    this._camLook = new THREE.Vector3();
  }

  _resize() {
    const w = innerWidth, h = innerHeight;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;

    // Vertical FOV is what PerspectiveCamera takes, so on a wide, short phone
    // screen a fixed value gives a fish-eye view. Trim it as the aspect widens.
    this._baseFov = clamp(52 - (w / h - 1.6) * 6, 40, 56);
    this.camera.updateProjectionMatrix();
    $('rotate-hint').style.display = (h > w && matchMedia('(pointer: coarse)').matches) ? 'grid' : 'none';
  }

  /* ------------------------------------------------------------------ level */

  loadLevel(index) {
    if (this.track) {
      this.scene.remove(this.track.mesh);
      this.track.dispose();
    }
    this.levelIndex = index;
    this.cfg = levelConfig(index);
    this.track = new Track(this.cfg);
    this.scene.add(this.track.mesh);

    const pose = this.track.startPose;
    this.vehicle.reset(pose.x, pose.z, pose.yaw);
    this.vehicle.fuel = this.cfg.fuel;
    this.startFuel = this.cfg.fuel;
    this.trackHint = 0;
    this.lastDistance = 0;
    this.lapProgress = 0;
    this.nextCheckpoint = 0;
    this.track.checkpoints.forEach(c => (c.passed = false));
    this.track.checkpointMeshes.forEach(m => (m.visible = true));
    this.raceTime = 0;
    this.topSpeed = 0;

    this._syncCar();
    this._snapCamera();

    $('level-name').textContent = this.cfg.name;
    $('level-num').textContent = `LEVEL ${index + 1}`;
    this._updateHud();
  }

  startCountdown() {
    this.state = 'countdown';
    this.countdown = 3.999;
    $('overlay').classList.add('hidden');
    $('hud').classList.remove('hidden');
    this.controls.calibrate();
  }

  /* ------------------------------------------------------------------ frame */

  _frame() {
    const dt = Math.min(this.clock.getDelta(), 0.05);
    this.controls.update(dt);

    if (this.state === 'countdown') {
      this.countdown -= dt;
      const n = Math.ceil(this.countdown - 1);
      $('countdown').textContent = n > 0 ? n : 'GO!';
      $('countdown').classList.remove('hidden');
      if (this.countdown <= 0) {
        this.state = 'racing';
        $('countdown').classList.add('hidden');
      }
    }

    if (this.state === 'racing') {
      // Fixed-step physics at 120 Hz, independent of the render rate.
      this.accumulator += dt;
      const h = 1 / 120;
      let steps = 0;
      while (this.accumulator >= h && steps < 8) {
        const alive = this._physicsStep(h);
        this.accumulator -= h;
        steps++;
        if (!alive) break;
      }
      if (steps === 8) this.accumulator = 0;   // we fell behind; drop the backlog
      this.raceTime += dt;
      this.topSpeed = Math.max(this.topSpeed, this.vehicle.speed);
      this._checkProgress();
    }

    this._syncCar();
    this._updateCamera(dt);
    this._updateHud();
    this.renderer.render(this.scene, this.camera);
  }

  _physicsStep(h) {
    const v = this.vehicle;
    const surf = this.track.surface(v.x, v.z, this.trackHint);
    this.trackHint = surf.loc.index;
    v.gripScale = surf.grip;
    v.offTrack = surf.offTrack;

    v.step(h, this.controls.state);

    if (surf.hit) {
      const impact = v.collide(surf.hit.nx, surf.hit.nz, surf.hit.penetration + 0.02);
      if (impact > 6) this._flashDamage();
    }
    if (v.fuel <= 0) { this._fail('OUT OF FUEL'); return false; }
    return true;
  }

  _checkProgress() {
    const loc = this.track.locate(this.vehicle.x, this.vehicle.z, this.trackHint);
    const n = this.track.frames.length - 1;

    // Compare frame indices the short way round the loop. The finish line sits
    // at index n, which is the same place as index 0, so a plain subtraction
    // would read the finish as being a whole lap away just as you cross it.
    const wrapDiff = (a, b) => {
      let d = (a - b) % n;
      if (d > n / 2) d -= n;
      if (d < -n / 2) d += n;
      return d;
    };

    const cp = this.track.checkpoints[this.nextCheckpoint];
    if (cp && Math.abs(wrapDiff(loc.index, cp.index)) < 12 && !cp.passed) {
      cp.passed = true;
      this.track.checkpointMeshes[this.nextCheckpoint].visible = false;
      this.nextCheckpoint++;
      this._toast(this.nextCheckpoint >= this.track.checkpoints.length
        ? 'FINISH' : `CHECKPOINT ${this.nextCheckpoint}/${this.track.checkpoints.length}`);
      if (this.nextCheckpoint >= this.track.checkpoints.length) this._finish();
    }
    this.lapProgress = clamp(loc.index / n, 0, 1);
  }

  _finish() {
    this.state = 'finished';
    const key = `racer.best.${this.levelIndex}`;
    const prev = this.best[key];
    const isBest = prev === undefined || this.raceTime < prev;
    if (isBest) {
      this.best[key] = this.raceTime;
      try { localStorage.setItem('racer.best', JSON.stringify(this.best)); } catch {}
    }

    $('result-title').textContent = 'TRACK COMPLETE';
    $('result-title').className = 'good';
    $('result-body').innerHTML = `
      <div class="stat"><span>Time</span><b>${this._fmtTime(this.raceTime)}${isBest ? ' <em>NEW BEST</em>' : ''}</b></div>
      <div class="stat"><span>Fuel left</span><b>${this.vehicle.fuel.toFixed(2)} kg</b></div>
      <div class="stat"><span>Top speed</span><b>${Math.round(this.topSpeed * 3.6)} km/h</b></div>`;
    $('result-next').textContent = 'NEXT LEVEL';
    $('result-next').dataset.action = 'next';
    $('result').classList.remove('hidden');
    $('hud').classList.add('hidden');
  }

  _fail(reason) {
    if (this.state !== 'racing') return;
    this.state = 'failed';
    $('result-title').textContent = reason;
    $('result-title').className = 'bad';
    $('result-body').innerHTML = `
      <div class="stat"><span>Progress</span><b>${Math.round(this.lapProgress * 100)}%</b></div>
      <div class="stat"><span>Checkpoints</span><b>${this.nextCheckpoint}/${this.track.checkpoints.length}</b></div>
      <div class="stat"><span>Lift and coast — part throttle burns far less fuel.</span></div>`;
    $('result-next').textContent = 'RETRY';
    $('result-next').dataset.action = 'retry';
    $('result').classList.remove('hidden');
    $('hud').classList.add('hidden');
  }

  /* ----------------------------------------------------------------- visuals */

  _syncCar() {
    const v = this.vehicle;
    this.carGroup.position.set(v.x, 0, v.z);
    this.carGroup.rotation.y = v.yaw;

    // Body roll and pitch: purely cosmetic, driven by the physics state.
    const lat = clamp(v.yawRate * v.vx / 90, -0.06, 0.06);
    const pitch = clamp(-(v._lastAx || 0) / 260, -0.035, 0.035);
    this.carGroup.rotation.z = -lat;
    this.carGroup.rotation.x = pitch;

    for (let i = 0; i < this.carWheels.length; i++) {
      const w = this.carWheels[i];
      w.rotation.x = -v.wheelSpin;
      if (i < 2) w.rotation.y = v.steer;   // front pair steers
    }
  }

  _snapCamera() {
    this._camPos.copy(this._desiredCamera());
    this.camera.position.copy(this._camPos);
  }

  _desiredCamera() {
    const v = this.vehicle;
    // Only a little pull-back with speed: too much and the car shrinks to a
    // dot exactly when it should feel fastest. The speed sensation comes from
    // the FOV creep in _updateCamera instead.
    const back = 7.2 + clamp(v.speed * 0.022, 0, 1.8);
    const height = 2.45 + clamp(v.speed * 0.008, 0, 0.65);
    return new THREE.Vector3(
      v.x - Math.sin(v.yaw) * back,
      height,
      v.z - Math.cos(v.yaw) * back
    );
  }

  _updateCamera(dt) {
    const v = this.vehicle;
    const want = this._desiredCamera();
    // Critically damped follow; faster car = tighter camera.
    const lambda = 4.5 + clamp(v.speed * 0.06, 0, 3);
    const blend = 1 - Math.exp(-lambda * dt);
    this._camPos.lerp(want, blend);
    this.camera.position.copy(this._camPos);

    // Look slightly ahead of the car so corners open up early.
    const lead = 7 + clamp(v.speed * 0.30, 0, 18);
    this._camLook.set(
      v.x + Math.sin(v.yaw) * lead,
      0.9,
      v.z + Math.cos(v.yaw) * lead
    );
    // Up is always world-up: the horizon never rolls, no matter how the player
    // is holding the phone.
    this.camera.up.set(0, 1, 0);
    this.camera.lookAt(this._camLook);

    this.camera.fov = this._baseFov + clamp(v.speed * 0.11, 0, 8);
    this.camera.updateProjectionMatrix();

    // Keep the shadow frustum on the car.
    this.sun.position.set(v.x + 80, 120, v.z + 60);
    this.sun.target.position.set(v.x, 0, v.z);
    this.sun.target.updateMatrixWorld();
  }

  /* --------------------------------------------------------------------- hud */

  _updateHud() {
    const v = this.vehicle;
    $('speed').textContent = Math.round(Math.abs(v.speed) * 3.6);
    $('gear').textContent = v.speed < 0.6 && this.controls.throttle < 0.05 ? 'N' : v.gear + 1;

    const rev = clamp((v.rpm - SPEC.idleRpm) / (SPEC.redline - SPEC.idleRpm), 0, 1);
    $('rev-fill').style.transform = `scaleX(${rev})`;
    $('rev-fill').classList.toggle('redline', v.rpm > SPEC.shiftUpRpm);

    const fuelPct = clamp(v.fuel / (this.startFuel || 1), 0, 1);
    $('fuel-fill').style.transform = `scaleY(${fuelPct})`;
    $('fuel-fill').className = 'fill' + (fuelPct < 0.15 ? ' critical' : fuelPct < 0.35 ? ' low' : '');
    $('fuel-text').textContent = `${v.fuel.toFixed(2)} kg`;

    $('time').textContent = this._fmtTime(this.raceTime || 0);
    $('progress-fill').style.transform = `scaleX(${this.lapProgress || 0})`;
    $('checkpoints').textContent = `${this.nextCheckpoint || 0}/${this.track ? this.track.checkpoints.length : 8}`;

    $('steer-needle').style.transform = `translateX(-50%) rotate(${this.controls.steer * 90}deg)`;
    $('pedal-gas').style.opacity = 0.25 + this.controls.throttle * 0.75;
    $('pedal-brake').style.opacity = 0.25 + this.controls.brake * 0.75;
    $('offtrack').classList.toggle('hidden', !v.offTrack || this.state !== 'racing');
  }

  _fmtTime(t) {
    const m = Math.floor(t / 60), s = t - m * 60;
    return `${m}:${s.toFixed(2).padStart(5, '0')}`;
  }

  _toast(text) {
    const el = $('toast');
    el.textContent = text;
    el.classList.remove('show');
    void el.offsetWidth;      // restart the animation
    el.classList.add('show');
  }

  _flashDamage() {
    const el = $('damage');
    el.classList.remove('show');
    void el.offsetWidth;
    el.classList.add('show');
    if (navigator.vibrate) navigator.vibrate(40);
  }

  _loadBest() {
    try { return JSON.parse(localStorage.getItem('racer.best') || '{}'); } catch { return {}; }
  }

  /* ---------------------------------------------------------------------- ui */

  _bindUI() {
    $('start').addEventListener('click', async () => {
      if (this.controls.permissionNeeded) {
        const ok = await this.controls.requestPermission();
        if (!ok) $('tilt-warning').classList.remove('hidden');
      }
      this.loadLevel(this.levelIndex);
      this.startCountdown();
    });

    $('result-next').addEventListener('click', () => {
      $('result').classList.add('hidden');
      const next = $('result-next').dataset.action === 'next' ? this.levelIndex + 1 : this.levelIndex;
      this.loadLevel(next);
      this.startCountdown();
    });

    $('result-menu').addEventListener('click', () => {
      $('result').classList.add('hidden');
      $('overlay').classList.remove('hidden');
      this.state = 'menu';
      this._buildLevelList();
    });

    $('recenter').addEventListener('click', () => {
      this.controls.calibrate();
      this._toast('STEERING CENTRED');
    });

    $('invert').addEventListener('click', e => {
      this.controls.invert = !this.controls.invert;
      e.currentTarget.classList.toggle('on', this.controls.invert);
    });

    $('restart').addEventListener('click', () => {
      this.loadLevel(this.levelIndex);
      this.startCountdown();
    });

    this._buildLevelList();
  }

  _buildLevelList() {
    const list = $('levels');
    list.innerHTML = '';
    LEVELS.forEach((lv, i) => {
      const best = this.best[`racer.best.${i}`];
      const b = document.createElement('button');
      b.className = 'level' + (i === this.levelIndex ? ' active' : '');
      b.innerHTML = `<span class="n">${i + 1}</span>
        <span class="t">${lv.name}</span>
        <span class="d">${'▰'.repeat(Math.round((i + 1) / LEVELS.length * 5)).padEnd(5, '▱')}</span>
        <span class="b">${best ? this._fmtTime(best) : '—'}</span>`;
      b.addEventListener('click', () => {
        this.levelIndex = i;
        this._buildLevelList();
      });
      list.appendChild(b);
    });
  }
}
