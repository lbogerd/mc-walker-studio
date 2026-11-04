# Walker spec (`walkers/<id>.json`)

Coordinates are in **blocks**, relative to the **centre of the hull's bottom face**. `+x` is forward
(the walker always walks toward `+x`), `+y` is up, and `z` is sideways (`-z` = left, `+z` = right).

```jsonc
{
  "name": "Quad Strider",
  "description": "One sentence about what the design is for.",
  "author": "who proposed it",
  "color": "#3987e5",                       // lane colour (optional)
  "proposal": {                             // shown in the Design tab
    "summary": "...", "decisions": ["..."], "openQuestions": ["..."]
  },

  "body": {
    "size": [9, 3, 5],        // hull bounding box in blocks [x, y, z]
    "rideHeight": 4.5,        // hull bottom height above the average ground under the legs
    "blocks": 90,             // block count (mass). Default: size x*y*z
    "comOffset": [0, 0, 0],   // centre of mass offset from the hull centre
    "color": "#8d9399", "accent": "#c9a227"
  },

  "legDefaults": { "segments": [3, 3], "knee": "back" },
  "legs": [
    {
      "id": "FL",
      "mount": [3.5, 0, -2.5],     // hip position (Leg Actuator block)
      "neutral": [0, -1],          // [dx, dz] of the foot from the hip at mid-stance
      "segments": [3, 3],          // [upper, lower] or [coxa, upper, lower]; lengths in blocks
      "knee": "back",              // forward | back | up | out | down : direction the knee bends toward
      "phase": 0.25,               // optional: overrides the gait preset (0–1 of the cycle)
      "mirror": true               // also create the mirrored leg on the other side (FL → FR)
    }
  ],

  "gait": {
    "preset": "alternating",   // alternating | wave | ripple | pace | bound | custom
    "duty": 0.55,              // fraction of the cycle each foot is planted
    "stride": 4,               // blocks travelled per cycle
    "stepHeight": 1.4,         // swing clearance above the highest block between footholds
    "swing": "arc",            // arc (smooth) | box (lift, move, drop)
    "bodyAdapt": "pitch",      // level | pitch (hull tilts with the terrain)
    "bodySmoothing": 0.25,     // 0–1 per-tick filter on hull height/tilt (1 = rigid follow)
    "comShift": 0,             // 0–1 hull sway toward the safest support point (needed for crawls)
    "footholdSearch": { "radius": 1 },   // null = place feet blindly; otherwise avoid void and edges
    "maxStepUp": null,         // optional mod limit; larger steps emit a "step-too-high" event
    "cycleTicks": 40           // only used when drive.rpm is absent
  },

  "drive": {
    "rpm": 48,                    // shaft speed driving the legs
    "revsPerCycle": 1,            // shaft revolutions per gait cycle (gearing)
    "stressPerLegPerRpm": 4,      // SU model: rpm × (legs × this + mass × stressPerBlockPerRpm)
    "stressPerBlockPerRpm": 0.05
  },

  "physics": { "support": "static" },          // static | kinematic (controller holds the hull)
  "implementation": {
    "legRepresentation": "renderOnly",         // renderOnly | contraption | subLevel (cost model)
    "footholdProbesPerStep": 27,               // default: 27 with search, 1 without
    "groundChecksPerFootPerTick": 1,
    "syncEveryTicks": 1
  },
  "parts": { "hip": "Leg Actuator", "joint": "Leg Joint", "strut": "Leg Strut", "foot": "Foot Pad" },
  "targets": { "speedMin": 1.5 }               // per-walker overrides of config/targets.json
}
```

## Derived values

- `cycleTicks = 1200 × revsPerCycle / rpm`, so one shaft revolution takes `1200 / rpm` ticks.
- `speed (b/s) = stride / cycleTicks × 20`.
- Gait presets assign phases from the leg layout. Legs are grouped by side (sign of `mount.z`) and ranked
  front to back:
  - **alternating**: neighbours and opposite legs in anti-phase. That gives a trot with 4 legs, a tripod with 6 and a plain walk with 2.
  - **wave**: one leg at a time: left rear→front, then right rear→front. Use `duty ≥ 1 − 1/n`.
  - **ripple**: a rear→front wave on each side, with the two sides half a cycle apart.
  - **pace** / **bound**: pairs by side / by front-vs-rear half.
