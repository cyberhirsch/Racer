# Ferrari F1 — 3D Model

An interactive 3D model of a Ferrari-style Formula 1 car, built procedurally with
[Three.js](https://threejs.org). No modelling software, no asset downloads — every
surface is generated in code.

## Run it

```bash
python3 -m http.server 8000   # any static server works
# open http://localhost:8000
```

Three.js is vendored under `vendor/three/`, so the page runs fully offline.

## Controls

| Action | Input |
| --- | --- |
| Orbit | drag |
| Zoom | scroll |
| Pan | right-drag |

Checkboxes toggle auto-rotate, wheel spin, and a wireframe view.

## What's modelled

- **Monocoque** — lofted from superellipse cross-sections along the car's length,
  giving the narrow nose, wide cockpit section and tapering gearbox in one mesh.
- **Aero** — front wing (main plane + three stacked flaps, endplates, footplates,
  strakes), rear wing with beam wing and endplates, floor edges and a stepped
  diffuser.
- **Sidepods** — box volumes deformed per-vertex into a coke-bottle rear, with
  inlets, undercut floor edge, bargeboards and shoulder winglets.
- **Cockpit** — halo (swept tube), headrest, airbox, engine cover, shark fin,
  exhaust and rain light.
- **Wheels** — crowned tyre tread, sidewall bands, spoked rims, centre lock nuts,
  brake ducts, and per-corner wishbone suspension aimed at each hub.

Materials use physical clearcoat paint for the bodywork and rough carbon for the
aero parts, lit by a key/rim/fill rig with soft shadows and a small generated
studio environment for reflections.
