# What the sandbox models (and what it doesn't)

The goal is quick, *comparable* answers in the early design phase. It is not a replacement for testing in
game. Every number comes from `src/sim/`, which is shared by the page and `npm run check`.

## Walking loop (per tick, 20 TPS)

1. **Body path.** The hull moves along +x at constant speed `stride / cycleTicks`. Legs never push it, so
   it doesn't slow down or speed up.
2. **Leg phase.** `φ = frac(t / cycle + phase)`. The foot is planted while `φ < duty` and swings otherwise.
3. **Footholds.** One per step, planned at the column under the neutral foot position at mid-stance. Feet
   stand on block tops (integer heights). With `footholdSearch`, a small grid around the nominal point is
   probed. Void is rejected, block edges are penalised and the closest valid point wins (white footprints
   in the view).
4. **Swing.** The foot moves from the old foothold to the new one along an arc or box profile. The apex
   clears the highest column between the two by `stepHeight`. Any part of the swing inside a block is a
   `toe-stub`.
5. **Hull height and tilt.** These follow the mean ground height under all legs (`rideHeight` above it),
   low-pass filtered by `bodySmoothing`. With `bodyAdapt: "pitch"` the hull also pitches and rolls to a
   least-squares fit of that ground.
6. **Sway (`comShift`).** The hull is shifted toward the point that maximises the worst support margin over
   the next swing window (coarse-to-fine grid search). A crawl gait needs this to be statically stable.
7. **IK.** An optional horizontal coxa, then analytic two-bone IK with a knee pole hint. If the target is out
   of reach the foot is clamped. The gap is reported as foot slip and the leg turns red.

## Measurements

- **Stability margin**: signed distance from the centre of mass (projected onto the ground) to the edge of
  the convex hull of planted feet. Fewer than 3 feet is always ≤ 0.
- **Bob**: peak-to-peak hull height after removing a one-cycle moving average (the terrain trend).
- **Events**: `overreach`, `overcompress`, `toe-stub`, `knee-clip`, `void-step`, `body-scrape`,
  `foot-clash` (neighbouring feet closer than 0.6 b), `step-too-high`. The ones that count as collisions:
  toe-stub, knee-clip, body-scrape, void-step, foot-clash, step-too-high.
- **Reference solver µs/tick**: measured time of this JavaScript implementation. It gives a rough idea of
  how complex the algorithm is. It is not the server cost.
- **Server cost**: a linear model in `config/cost-model.json`. It counts hull blocks, legs, segments,
  foothold probes and ground checks, with per-representation costs for legs drawn as render-only,
  contraptions or Sable sub-levels. **Placeholder until calibrated.**
- **Stress**: `rpm × (legs × stressPerLegPerRpm + mass × stressPerBlockPerRpm)`. Also a placeholder formula.

## Not modelled

- Turning, strafing, reversing, acceleration and braking.
- Real dynamics: tipping, momentum, Sable collision response, players or entities riding the hull.
- Leg-vs-leg (except foot-clash) and leg-vs-hull intersection.
- Non-block terrain (slabs, stairs blocks, fluids) and blocks being broken or placed.

## Calibrating against the real mod

1. Build one reference walker in the sandbox world and run `/spark profiler --timeout 60` while it walks.
2. Divide the walker-related self-time by the number of ticks. Repeat with a different leg count or
   representation to separate per-leg and per-segment cost.
3. Fit the coefficients in `config/cost-model.json` so `npm run check` matches, then set `"calibrated": true`.
   From then on, server targets fail instead of warn.
4. Do the same for stress with Create's stressometer/goggles.
