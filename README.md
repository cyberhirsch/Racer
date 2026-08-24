# Racer F1

A racing game where **your phone is the steering wheel** — hold it in landscape
and rotate it like a wheel. The horizon on screen stays level; only the car
turns.

Everything is procedural: the car, the circuits and the physics are all
generated in code. No 3D assets, no game engine.

It comes in two forms:

| | | |
| --- | --- | --- |
| **Android app** | `app/` + `core/` | Kotlin, OpenGL ES 3.0, Compose HUD |
| **Web version** | `web/` | JavaScript and Three.js, runs in any browser |

The web version is the original prototype and is deployed to GitHub Pages on
every push that touches it — open the repository's **Environments → github-pages**
link, or the URL shown on the Actions run, to play it in a browser. The Android
app is the one that treats the phone as a wheel properly; the web version falls
back to the keyboard on desktop.

## Getting the APK

Every push builds one. Open the **Actions** tab, pick the latest **Build** run,
and download the `racer-release-apk` artifact — it is signed with the debug key,
so it installs directly. Tagging a commit `v*` also attaches the APK to a GitHub
release.

To build locally you need an Android SDK and JDK 17:

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :core:test                # game logic tests, no SDK needed
```

Requires Android 7.0 (API 24) or newer, OpenGL ES 3.0, and a gravity or
accelerometer sensor.

## Controls

| | |
| --- | --- |
| **Steer** | rotate the phone like a steering wheel |
| **Gas** | touch and hold the **right** half of the screen |
| **Brake** | touch and hold the **left** half |

Both are multi-touch aware, so trail-braking with two thumbs works. **CENTRE**
re-zeroes the steering at whatever attitude you are holding the phone;
**INVERT** flips the direction.

## The goal

Fuel is the clock. Each level gives you a tank measured in kilograms, and you
have to reach the finish before it runs dry. Fuel burns in proportion to the
crank work the engine actually does, so full throttle everywhere will not make
it — you have to lift and coast, and brake in a straight line rather than
scrubbing speed off mid-corner.

Eight checkpoints mark the route; the last is the finish line.

## Levels

Six hand-tuned circuits, then an endless run of progressively harder ones.
Difficulty is measured as how fast the reference driver (below) gets round,
which falls from ~237 km/h on level 1 to ~181 km/h on level 6.

| # | Track | Length | Tightest corner | Width | Fuel |
| --- | --- | --- | --- | --- | --- |
| 1 | Fiorano Shakedown | 1500 m | 105 m | 15.0 m | 2.25 kg |
| 2 | Monza Sprint | 1900 m | 84 m | 14.0 m | 2.36 kg |
| 3 | Suzuka Esses | 2300 m | 62 m | 13.0 m | 2.46 kg |
| 4 | Monaco Tight | 2600 m | 53 m | 11.5 m | 2.50 kg |
| 5 | Spa Endurance | 3000 m | 45 m | 11.0 m | 2.25 kg |
| 6 | The Gauntlet | 3600 m | 27 m | 10.0 m | 2.55 kg |

## Project layout

    core/   pure Kotlin/JVM — physics, tracks, meshes, game loop, tilt maths
    app/    Android — OpenGL ES renderer, sensors, Compose HUD
    web/    the JavaScript/Three.js version, deployed to GitHub Pages

The split is deliberate: **all the game logic is free of Android APIs**, so it
is unit-tested on the JVM rather than trusted by eye or checked by hand on a
device. `:app` is only included in the build when an Android SDK is present, so
`./gradlew :core:test` works on any JDK.

### Physics

A dynamic bicycle model, integrated at a fixed 120 Hz independently of the
frame rate:

- **Tyres** use a simplified Pacejka magic formula for lateral force, per axle,
  from the slip angle.
- **Load transfer** shifts vertical load between the axles under acceleration
  and braking; aerodynamic downforce adds to both, so the car has far more grip
  at 300 km/h than at 50.
- **A friction ellipse** couples longitudinal and lateral force, so grip spent
  cornering is not available for accelerating or braking.
- **The powertrain** is a torque curve through an 8-speed gearbox with automatic
  shifts and a brief torque cut per shift.
- **Steering** is capped at the angle that asks for exactly the lateral
  acceleration the tyres can currently deliver. Without that, full lock at
  200 km/h demands over 10 g, the car simply spins, and the rotating-frame
  integration diverges.

The car matches real F1 figures, and the tests hold it there:

| | Model | Real F1 |
| --- | --- | --- |
| 0–100 km/h | 2.47 s | ~2.6 s |
| 0–200 km/h | 4.52 s | ~4.5 s |
| 200–0 km/h braking | 2.85 s | ~2.9 s |
| Top speed | 334 km/h | ~330 km/h |
| Peak lateral | 3.0 g | 3–5 g |

### Tracks

Each level is a seeded, deterministic circuit: control points on an irregular
ring, splined, then sampled into frames carrying position, tangent and a
right-hand vector. The road, kerbs, barriers, checkpoints and *all* collision
derive from those frames, so the physics and the visuals cannot disagree about
where the track is.

Generated corners can come out tighter than the car can physically negotiate,
so the generator measures the sharpest corner and relaxes the control points
until every corner respects the level's stated minimum radius, rescaling to hold
the target lap distance. Circuits that fold back on themselves are rejected and
regenerated.

### Tilt steering

Steering comes from the **screen roll** angle, obtained by projecting the
device's gravity vector into the screen plane. Working from gravity rather than
a single sensor axis means it is unaffected by how far the phone is pitched
toward or away from you, and it stays correct whichever way the screen is
rotated. Neutral is calibrated to wherever you are actually holding the phone.

### Rendering

Geometry is generated in `:core` as plain float arrays; the renderer only
uploads buffers and issues draws. The whole track is one draw call — kerbs and
barriers left as individual objects would be well over a thousand per frame.
Lighting is a directional sun with a Blinn-Phong highlight, hemispheric ambient
and distance fog.

## Tests

```bash
./gradlew :core:test
```

45 tests, run on every push. The notable ones:

- **`VehicleTest`** pins the performance figures in the table above, and proves
  the simulation stays finite when the car is provoked into a spin.
- **`TiltSteeringTest`** builds the gravity vector a phone reports at a known
  roll, feeds it through the game's projection, and checks the angle that comes
  back — exact in all four screen orientations and provably immune to pitch.
- **`AutopilotTest`** drives every circuit with a reference driver, proving each
  is completable without touching a barrier. Its measured fuel consumption is
  where the levels' fuel budgets come from.
- **`MeshContentTest`** checks the car's real-world dimensions, that no
  bodywork clips through the road, and that the triangle budget stays within
  reach of a phone GPU.
- **`GameTest`** plays whole levels: countdown gating, checkpoint order, best
  times, running out of fuel, and that the result does not depend on frame rate.

CI also runs the app on an emulator and the website in a real browser, because
neither the renderer nor the UI can be checked by a unit test. Both start a
race and assert the car actually reaches speed — an earlier smoke test only
checked that the screenshot had a spread of colours, which the *menu* screen
satisfies just as well, and it passed while the app was unusable.

### The web version

    cd web && python3 -m http.server 8000     # then open localhost:8000
    node web/tools/browser-check.mjs          # loads it and plays a race
    node web/tools/autopilot.mjs 8            # drives every circuit headlessly
    node web/tools/tilt-check.mjs             # tilt maths round-trip

The web version needs `npm i three playwright` for its tools; the page itself
has Three.js vendored and needs nothing.
