# Walker spec (`walkers/<id>.json`)

Coordinates are in **blocks**, relative to the **centre of the hull's bottom face**. `+x` is forward
(every course starts with the walker at the origin heading `+x`), `+y` is up, and `z` is sideways
(`-z` = left, `+z` = right). The hull turns about `+y` through this origin; positive yaw turns left.

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
    "cycleTicks": 40,          // only used when drive.rpm is absent
    "accel": 2,                // b/s²: max change of ground speed per second (speeding up and braking)
    "settleRate": 0.5          // 0–1: slowest gait clock rate (fraction of full) while slow or settling
  },

  "drive": {
    "rpm": 48,                    // shaft speed driving the legs
    "revsPerCycle": 1,            // shaft revolutions per gait cycle (gearing)
    "stressPerLegPerRpm": 4,      // SU model: rpm × (legs × this + mass × stressPerBlockPerRpm)
    "stressPerBlockPerRpm": 0.05
  },

  "physics": {
    "stabilizer": null,                        // or { "maxTorque": 160, "stressPerTorque": 2 } (Gyro Stabilizer, D6)
    "suspension": { "frequency": 2, "damping": 0.7 }  // how the real hull follows the planned pose (D1)
  },
  "implementation": {
    "legRepresentation": "contraption",        // contraption (default, D2) | renderOnly | subLevel (cost model)
    "footholdProbesPerStep": 27,               // default: 27 with search, 1 without
    "groundChecksPerFootPerTick": 1,
    "syncEveryTicks": 1
  },
  "parts": { "hip": "Leg Actuator", "joint": "Leg Joint", "strut": "Leg Strut", "foot": "Foot Pad" },
  "targets": { "speedMin": 1.5 }               // per-walker overrides of config/targets.json
}
```

## Physics

### `physics.stabilizer` (Gyro Stabilizer, decision D6)

`null` means no stabilizer: the walker must be statically stable (stability margin ≥ `stabilityMarginMin`).
Otherwise:

| field | unit | default | |
|---|---|---|---|
| `maxTorque` | mass·b | required | Largest righting torque the block can apply. |
| `stressPerTorque` | SU per mass·b | `2` | Create stress drawn per unit of torque applied (placeholder until the block exists). |

**mass·b** is the torque of one block of mass on one block of lever arm. Mass is the walker's `mass`
(hull blocks plus leg blocks from the bill of materials). Each tick the hull needs

```
requiredTorque = mass × max(0, −margin)
```

where `margin` is the signed stability margin (how far the centre of mass is outside the support polygon
when negative). Stability passes when `margin ≥ stabilityMarginMin` at every tick, **or** a stabilizer is
fitted and `peak requiredTorque ≤ maxTorque` (stabilizer load ≤ 100%). The stabilizer's stress is
`min(peak requiredTorque, maxTorque) × stressPerTorque` and is **included in the Stress total** (also shown
on its own as "Stabilizer stress"). Size `maxTorque` from the "Peak righting torque" metric on the
courses you care about, with some headroom.

**Migration from `physics.support`.** The field was removed. `"support": "static"` becomes
`"stabilizer": null`. `"support": "kinematic"` becomes `"stabilizer": { "maxTorque": …, "stressPerTorque": … }`.
A spec that still has `physics.support` is rejected with an error that says this.

### `physics.suspension` (decision D1)

In the mod the legs push the hull like spring-dampers, so the real hull lags the pose the gait planner
wants. The sandbox models the hull's height, pitch and roll as a damped second-order follower of the
planned values:

```
ÿ = ωn² (y_planned − y) − 2 ζ ωn ẏ,    ωn = 2π × frequency
```

| field | unit | default | |
|---|---|---|---|
| `frequency` | Hz | `2` | Natural frequency. Higher = stiffer legs, less lag, more bob passed to the deck. |
| `damping` | ratio | `0.7` | 1 = critically damped, below 1 overshoots. |

The spring gains in the mod scale with hull mass (as Offroad's wheel mounts do), so frequency and damping
ratio, not raw stiffness, are the per-walker settings. The legs hang off the *actual* hull, so lag shows up
as extra reach and foot slip. "Hull tracking error" is the peak height gap in blocks. Horizontal position and
yaw follow the plan exactly.

## Driving: courses and commands

Courses (`src/sim/terrain.js` → `SCENARIOS`) may define a command timeline of piecewise-constant
segments `{ ticks, throttle, steer }` (throttle and steer are both in [−1, 1]; positive steer turns left).
Courses without one drive at full throttle forward.

- `vTop = stride / cycleTicks` (top speed at the drive RPM, D7). Throttle sets the target ground speed
  `throttle × vTop`; negative throttle reverses on the same scale.
- `turnRadius = max over legs of |(mount.x + neutral.dx, mount.z + neutral.dz)|`, the farthest neutral foot
  from the yaw axis. `maxYawRate = vTop / turnRadius`, so at full steer in place the outermost foot moves
  at top speed and takes a full stride per cycle. Steer sets the target yaw rate `steer × maxYawRate`.
- When curving, the target is scaled down (keeping the turn radius) until no foot moves over the ground
  faster than `vTop`. That's why inner and outer legs take different strides, and why full throttle with
  full steer is slower than either alone.
- Speed and yaw rate move toward their targets at most `accel` b/s² and `accel / turnRadius` rad/s².
- The gait clock Φ (cycles) advances at `max(activity, settleRate) / cycleTicks` per tick, with
  `activity = max(|v| / vTop, |ω| / maxYawRate, fastest foot speed / vTop)`. When the commands and the
  velocity are both zero, Φ runs on at `settleRate` until every foot is planted (the rest stance) and
  then freezes. If the gait has no point where all feet are down (e.g. alternating with `duty ≤ 0.5`) the
  spec gets a warning and the walker freezes with the most feet down.

## Derived values

- `cycleTicks = 1200 × revsPerCycle / rpm`, so one shaft revolution takes `1200 / rpm` ticks.
- `speed (b/s) = stride / cycleTicks × 20`: the **top speed** at full throttle. The speed check against
  `speedMin` uses it, so courses that stand or turn in place don't fail it. "Achieved speed" is the
  average over a course.
- `maxYawRate (rad/s) = speed / turnRadius` (see above).
- Gait presets assign phases from the leg layout. Legs are grouped by side (sign of `mount.z`) and ranked
  front to back:
  - **alternating**: neighbours and opposite legs in anti-phase. That gives a trot with 4 legs, a tripod with 6 and a plain walk with 2.
  - **wave**: one leg at a time: left rear→front, then right rear→front. Use `duty ≥ 1 − 1/n`.
  - **ripple**: a rear→front wave on each side, with the two sides half a cycle apart.
  - **pace** / **bound**: pairs by side / by front-vs-rear half.
