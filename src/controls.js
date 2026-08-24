/**
 * Input: the phone itself is the steering wheel.
 *
 * Steering comes from the *screen roll* angle, derived by projecting the
 * gravity vector into the screen plane. Doing it via gravity (rather than
 * reading `gamma` directly) means it keeps working whichever way the phone is
 * tilted toward or away from you, and it survives the screen-orientation
 * change when you rotate into landscape.
 *
 * The neutral position is calibrated to wherever the player is actually
 * holding the phone, so there is no assumption about which way up it is.
 *
 * Throttle and brake are screen halves: right = gas, left = brake. Both are
 * multi-touch aware, so trail-braking with two thumbs works.
 *
 * Desktop keeps a keyboard fallback (arrows / WASD) so the game is testable
 * without a phone.
 */

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const wrapPi = a => Math.atan2(Math.sin(a), Math.cos(a));

export class Controls {
  constructor(opts = {}) {
    this.throttle = 0;
    this.brake = 0;
    this.steer = 0;

    this.rawRoll = 0;         // measured screen roll (rad)
    this.neutral = 0;         // calibrated centre (rad)
    this.hasOrientation = false;
    this.permissionNeeded = typeof DeviceOrientationEvent !== 'undefined' &&
      typeof DeviceOrientationEvent.requestPermission === 'function';

    // Tuning
    this.fullLock = opts.fullLock ?? 0.62;   // rad of phone rotation for full steering lock (~35°)
    this.deadzone = opts.deadzone ?? 0.035;  // rad
    this.smoothing = opts.smoothing ?? 18;   // higher = snappier
    this.invert = false;

    this._touches = new Map();   // pointerId -> 'throttle' | 'brake'
    this._keys = new Set();
    this._targetThrottle = 0;
    this._targetBrake = 0;

    this._onOrientation = this._onOrientation.bind(this);
  }

  /* --------------------------------------------------------- attach / detach */

  attach(element) {
    this.element = element;

    element.addEventListener('pointerdown', e => this._pointerDown(e));
    element.addEventListener('pointerup', e => this._pointerUp(e));
    element.addEventListener('pointercancel', e => this._pointerUp(e));
    element.addEventListener('pointerleave', e => this._pointerUp(e));
    element.addEventListener('contextmenu', e => e.preventDefault());

    addEventListener('keydown', e => {
      if (['ArrowUp','ArrowDown','ArrowLeft','ArrowRight',' '].includes(e.key)) e.preventDefault();
      this._keys.add(e.key.toLowerCase());
    });
    addEventListener('keyup', e => this._keys.delete(e.key.toLowerCase()));
    addEventListener('blur', () => { this._keys.clear(); this._touches.clear(); });

    addEventListener('deviceorientation', this._onOrientation);
    return this;
  }

  /** iOS 13+ requires a user gesture before orientation data flows. */
  async requestPermission() {
    if (!this.permissionNeeded) return true;
    try {
      const state = await DeviceOrientationEvent.requestPermission();
      return state === 'granted';
    } catch {
      return false;
    }
  }

  /** Zero the steering at the phone's current attitude. */
  calibrate() {
    this.neutral = this.rawRoll;
  }

  /* ------------------------------------------------------------- orientation */

  _onOrientation(e) {
    if (e.beta === null || e.gamma === null) return;
    this.hasOrientation = true;

    const b = e.beta * Math.PI / 180;
    const g = e.gamma * Math.PI / 180;

    // Gravity in device coordinates (down-vector), from the Z-X'-Y'' convention.
    let gx = Math.cos(b) * Math.sin(g);
    let gy = -Math.sin(b);

    // Rotate into the *screen's* frame so landscape behaves like portrait.
    const angle = ((screen.orientation && screen.orientation.angle) || window.orientation || 0) * Math.PI / 180;
    const ca = Math.cos(angle), sa = Math.sin(angle);
    const sx = gx * ca + gy * sa;
    const sy = -gx * sa + gy * ca;

    // Gravity in the screen frame points along +y (screen-down), so the angle
    // between it and screen-down is the wheel angle. Verified end to end by
    // tools/tilt-check.mjs.
    this.rawRoll = Math.atan2(sx, sy);
  }

  /* ------------------------------------------------------------------ touch */

  _zoneFor(e) {
    const rect = this.element.getBoundingClientRect();
    return (e.clientX - rect.left) > rect.width / 2 ? 'throttle' : 'brake';
  }

  _pointerDown(e) {
    if (e.target.closest && e.target.closest('[data-ui]')) return;   // let buttons work
    this.element.setPointerCapture?.(e.pointerId);
    this._touches.set(e.pointerId, this._zoneFor(e));
  }

  _pointerUp(e) {
    this._touches.delete(e.pointerId);
  }

  /* ----------------------------------------------------------------- update */

  update(dt) {
    const zones = new Set(this._touches.values());
    const k = this._keys;

    const keyThrottle = k.has('arrowup') || k.has('w');
    const keyBrake = k.has('arrowdown') || k.has('s') || k.has(' ');

    this._targetThrottle = (zones.has('throttle') || keyThrottle) ? 1 : 0;
    this._targetBrake = (zones.has('brake') || keyBrake) ? 1 : 0;

    // Pedals ramp rather than snap — a real driver rolls onto the throttle.
    const rate = 6.5;
    this.throttle += clamp(this._targetThrottle - this.throttle, -rate * dt * 2, rate * dt);
    this.brake += clamp(this._targetBrake - this.brake, -rate * dt * 2.5, rate * dt * 2);
    this.throttle = clamp(this.throttle, 0, 1);
    this.brake = clamp(this.brake, 0, 1);

    // Steering: device roll if we have it, otherwise the keyboard.
    let target;
    if (this.hasOrientation) {
      let a = wrapPi(this.rawRoll - this.neutral);
      const sign = Math.sign(a);
      a = sign * Math.max(0, Math.abs(a) - this.deadzone);
      target = clamp(a / (this.fullLock - this.deadzone), -1, 1);
      if (this.invert) target = -target;
      // Slight expo so small corrections at speed are less twitchy.
      target = Math.sign(target) * Math.pow(Math.abs(target), 1.35);
    } else {
      const left = k.has('arrowleft') || k.has('a');
      const right = k.has('arrowright') || k.has('d');
      target = (right ? 1 : 0) - (left ? 1 : 0);
    }

    const blend = 1 - Math.exp(-this.smoothing * dt);
    this.steer += (target - this.steer) * blend;

    return this;
  }

  get state() {
    return { throttle: this.throttle, brake: this.brake, steer: this.steer };
  }
}
