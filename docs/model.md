# What the sandbox models (and what it doesn't)

The goal is quick, *comparable* answers in the early design phase. It is not a replacement for testing in
game. Every number comes from `src/sim/`, which is shared by the page and `npm run check`.

## Walking loop (20 TPS)

**Pass 1, path.** The course's command timeline (throttle, steer; see `docs/walker-spec.md`) is integrated
tick by tick into a planned hull path (x, z, yaw) and a gait clock Φ. Top speed is `stride / cycleTicks`
and the max yaw rate is `top speed / turnRadius`. Commands are scaled so no foot moves over the ground
faster than top speed, then rate-limited by `gait.accel`. Φ advances at `max(activity, settleRate) /
cycleTicks`. With zero commands and zero velocity it runs on until every foot is planted, then freezes:
the walker settles into a stance instead of stopping mid-swing. Legs never push the hull, so this path is
followed exactly in x, z and yaw.

**Pass 2, per tick:**

1. **Leg phase.** `φ = frac(Φ + phase)`. The foot is planted while `φ < duty` and swings otherwise. At full
   speed `Φ = t / cycleTicks`, as before.
2. **Footholds.** One per step, at the column under the neutral foot position at mid-stance. Planning is
   **causal**, like a live controller: the foothold for a stance is decided on the tick the leg lifts off
   for the swing before it, from the state known then. The planner holds the command given on that tick,
   integrates forward with the same acceleration limits until Φ reaches mid-stance, and places the foot
   under that predicted hull pose (position and heading). Steering after liftoff therefore only affects
   later steps. Identical inputs up to a tick produce identical simulations up to that tick (tested).
   Turning in place steps the feet around the hull, and reversing steps them backwards. Feet stand on
   block tops (integer heights). With `footholdSearch`, a small grid around the nominal point (aligned with
   the hull's heading) is probed. Void is rejected, block edges are penalised and the closest valid point
   wins (white footprints in the view).
3. **Swing.** The foot moves from the old foothold to the new one along an arc or box profile. The apex
   clears the highest column between the two by `stepHeight`. Any part of the swing inside a block is a
   `toe-stub`.
4. **Planned hull height and tilt.** These follow the mean ground height under all legs (`rideHeight`
   above it), low-pass filtered by `bodySmoothing`. With `bodyAdapt: "pitch"` the hull also pitches and
   rolls to a least-squares fit of that ground.
5. **Sway (`comShift`).** The hull is shifted toward the point that maximises the worst support margin over
   the next swing window (coarse-to-fine grid search). A crawl gait needs this to be statically stable. The
   look-ahead is causal too: it extrapolates Φ at the current rate and uses provisional footholds, based on
   the current state, for steps that haven't been decided yet.
6. **Actual hull (suspension, D1).** The real hull is pushed by the legs, so its height, pitch and roll
   follow the planned values as a damped spring (`physics.suspension`: frequency, damping ratio; 8
   backward-Euler sub-steps per tick, which stay stable for any accepted frequency and damping). The legs are solved from this actual pose, so lag shows up as extra reach or slip.
7. **IK.** An optional horizontal coxa, then analytic two-bone IK with a knee pole hint. If the target is out
   of reach the foot is clamped. The gap is reported as foot slip and the leg turns red.

## Measurements

- **Stability margin**: signed distance from the centre of mass (projected onto the ground) to the edge of
  the convex hull of planted feet. Fewer than 3 feet is always ≤ 0.
- **Righting torque / Gyro Stabilizer (D6)**: `mass × max(0, −margin)` per tick, in mass·b. Stability passes
  if the margin meets its target or the fitted stabilizer's `maxTorque` covers the peak. The stabilizer's
  stress (`min(peak, maxTorque) × stressPerTorque`) is added to the Stress total.
- **Hull tracking error**: peak |actual − planned| hull height (suspension lag).
- **Speeds**: *top speed* (`stride / cycle`, checked against `speedMin`), *achieved speed* (path length ÷
  run time), *max turn rate* (design) and *peak yaw rate* (achieved on the course).
- **Joint speed**: measured relative to the hull. Hip yaw is the coxa direction (3-segment legs) or the
  leg plane's orientation (2-segment legs), so hull turning and pitching don't count as joint motion.
- **Peak hull acceleration**: the planned horizontal hull path (path + sway) is run through the walker's own
  suspension follower (the plan interpolated across sub-steps), and its peak acceleration is what planted feet
  must push the real hull with. Feet can only push so hard sideways (friction). The mod's walking game tests
  calibrate it: a plan needing 12 b/s² was tracked within 0.16 b, one needing 30 b/s² could not. The
  `hullAccelMax` target of 20 b/s² is provisional. Diagnostic only; it doesn't change the simulated pose.
- **Bob**: peak-to-peak hull height after removing a one-cycle moving average (the terrain trend).
- **Events**: `overreach`, `overcompress`, `toe-stub`, `knee-clip`, `void-step`, `body-scrape`,
  `foot-clash` (neighbouring feet closer than 0.6 b), `step-too-high`. The ones that count as collisions:
  toe-stub, knee-clip, body-scrape, void-step, foot-clash, step-too-high.
- **Reference solver µs/tick**: measured time of this JavaScript implementation. It gives a rough idea of
  how complex the algorithm is. It is not the server cost.
- **Server cost**: a linear model in `config/cost-model.json`. It counts hull blocks, legs, segments,
  foothold probes and ground checks, with per-representation costs for legs drawn as render-only,
  contraptions or Sable sub-levels. **Placeholder until calibrated.**
- **Stress**: `rpm × (legs × stressPerLegPerRpm + mass × stressPerBlockPerRpm)` for the drive, plus the
  stabilizer's peak stress. Also a placeholder formula.

## Not modelled

- Strafing and omnidirectional gaits (out of v1 scope, D4).
- Real dynamics: tipping, momentum, Sable collision response, players or entities riding the hull. The
  suspension follower only covers height, pitch and roll. The hull never lags in x, z or yaw, and leg
  forces don't depend on load.
- Gait changes with speed: the duty factor and stride shape stay fixed, and a slow walk just takes shorter
  steps at a slower clock (never below `settleRate`).
- What the stabilizer does to the hull: torque demand is measured, but its torque doesn't move anything.
- Leg-vs-leg (except foot-clash) and leg-vs-hull intersection.
- Non-block terrain (slabs, stairs blocks, fluids) and blocks being broken or placed.

## Calibrating against the real mod

1. Build one reference walker in the sandbox world and run `/spark profiler --timeout 60` while it walks.
2. Divide the walker-related self-time by the number of ticks. Repeat with a different leg count or
   representation to separate per-leg and per-segment cost.
3. Fit the coefficients in `config/cost-model.json` so `npm run check` matches, then set `"calibrated": true`.
   From then on, server targets fail instead of warn.
4. Do the same for stress with Create's stressometer/goggles.
