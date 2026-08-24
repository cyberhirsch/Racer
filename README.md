# Racer F1

A 3D Formula 1 racing game for the phone. **Your phone is the steering wheel** —
hold it in landscape and rotate it like a wheel. The horizon on screen stays
level; only the car turns.

Everything is procedural and self-contained: the car, the circuits and the
physics are all generated in code, and Three.js is vendored, so the whole thing
runs offline from any static file server with no build step.

```bash
npm start          # or: python3 -m http.server 8000
# open http://localhost:8000
```

## Controls

| | Phone | Desktop |
| --- | --- | --- |
| **Steer** | rotate the phone like a wheel | ← → / A D |
| **Gas** | touch and hold the **right** half of the screen | ↑ / W |
| **Brake** | touch and hold the **left** half | ↓ / S / space |

Both pedals are multi-touch aware, so you can trail-brake with two thumbs.
**CENTRE** re-zeroes the steering at whatever attitude you are holding the
phone; **INVERT** flips the steering direction.

## The goal

Fuel is the clock. Each level gives you a tank measured in kilograms, and you
have to reach the finish before it runs dry. Fuel burns in proportion to the
actual crank work the engine does, so full throttle everywhere will not make it
— you have to lift and coast, and brake in a straight line rather than scrubbing
speed off in the corner.

Eight checkpoints mark the route; the last one is the finish line.

## Levels

Six hand-tuned circuits, then an endless run of progressively harder ones. The
difficulty knobs are corner count, minimum corner radius, track width and the
fuel margin. Measured with the autopilot (see below), average speed falls from
~248 km/h on level 1 to ~151 km/h on level 6:

| # | Track | Length | Tightest corner | Width | Fuel |
| --- | --- | --- | --- | --- | --- |
| 1 | Fiorano Shakedown | 1500 m | 105 m | 15.0 m | 2.45 kg |
| 2 | Monza Sprint | 1900 m | 85 m | 14.0 m | 2.40 kg |
| 3 | Suzuka Esses | 2300 m | 58 m | 13.0 m | 2.50 kg |
| 4 | Monaco Tight | 2600 m | 51 m | 11.5 m | 2.05 kg |
| 5 | Spa Endurance | 3000 m | 39 m | 11.0 m | 2.40 kg |
| 6 | The Gauntlet | 3600 m | 27 m | 10.0 m | 2.20 kg |

Best times are saved per level in `localStorage`.

## How it works

### `src/physics.js` — vehicle dynamics

A dynamic bicycle model, integrated at a fixed 120 Hz independently of the frame
rate:

- **Tyres** use a simplified Pacejka magic formula for lateral force, per axle,
  from the slip angle.
- **Load transfer** shifts vertical load between the axles under acceleration
  and braking, and aerodynamic downforce adds to both — so the car has far more
  grip at 300 km/h than at 50.
- **A friction ellipse** couples longitudinal and lateral force, so grip spent
  cornering is not available for accelerating or braking.
- **The powertrain** is a torque curve through an 8-speed gearbox with automatic
  shifts and a brief torque cut per shift.
- **Steering** is capped at the angle that asks for exactly the lateral
  acceleration the tyres can currently deliver. Without that, full lock at
  200 km/h demands over 10 g, and the car just spins.

The resulting car matches real F1 figures closely:

| | Model | Real F1 |
| --- | --- | --- |
| 0–200 km/h | ~4.4 s | ~4.5 s |
| 200–0 km/h braking | 2.85 s | ~2.9 s |
| Top speed | 334 km/h | ~330 km/h |
| Peak lateral | 3.2 g at 200 km/h | 3–5 g |

### `src/track.js` — circuit generation

Each level is a seeded, deterministic circuit: control points on an irregular
ring, splined, then sampled into frames carrying position, tangent and a
right-hand vector. Everything else — road, kerbs, barriers, checkpoints and all
collision — derives from those frames, so the physics and the visuals cannot
disagree about where the track is.

Generated corners can come out tighter than the car can physically negotiate, so
the generator measures the sharpest corner and relaxes the control points until
every corner respects the level's stated minimum radius, rescaling to hold the
target lap distance.

Kerbs, barriers and the start line are merged into single geometries. Left as
individual meshes they cost well over a thousand draw calls per frame, plus the
same again for the shadow pass.

### `src/controls.js` — tilt steering

Steering comes from the **screen roll** angle, obtained by projecting the gravity
vector into the screen plane, rather than by reading `gamma` directly. That means
it is unaffected by how far you tilt the phone toward or away from you, and it
survives the screen-orientation change when you rotate into landscape. Neutral
is calibrated to wherever you are actually holding the phone.

### `src/car.js` — the car

The Ferrari-style F1 model: a monocoque lofted from superellipse cross-sections,
front and rear wings, sidepods deformed per-vertex into a coke-bottle rear, a
stepped diffuser, halo, engine cover, and wheels with per-corner wishbones.

## Tests

```bash
npm i          # tools need three; the game itself does not
npm test
```

- **`tools/tilt-check.mjs`** builds the rotation matrix for a phone held at a
  known roll, extracts the alpha/beta/gamma a browser would report, and checks
  the steering angle the game recovers. Exact in both landscape orientations and
  in portrait, and provably immune to pitch.
- **`tools/autopilot.mjs`** drives every circuit headlessly with a pure-pursuit
  driver. It verifies each level is completable without hitting a barrier, and
  its measured fuel consumption is what the levels' fuel budgets are set from.

## Performance

`?quality=low|medium|high` overrides the automatic tier (phones default to
medium: smaller shadow map, capped pixel ratio). Low disables shadows and
antialiasing.
