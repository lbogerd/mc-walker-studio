# Design decisions

Settled decisions for the walker mod (`mod/`) and the studio. Build on these; don't reopen them without
the project owner. Each entry says what we chose, why, and what it means for the code.

Decided 2026-11-04.

## D1: The hull is moved by legs acting as active suspension

The hull stays an ordinary physical Sable sub-level. While a foot is planted, it pushes on the hull at its
hip like a spring-damper with friction, pulling the hull toward the pose the gait planner wants. Offroad's
wheel mount (`WheelMountBlockEntity` in `reference/simulated-project`) does the same thing with
`applyImpulseAtPoint`.

- **Why:** collisions, other ships, riders and tipping all behave like the rest of Aeronautics, and the
  studio's stability metrics become real constraints.
- **Not chosen:** a scripted kinematic pose (ignores physics, so the hull can push through other ships),
  and "kinematic first, physics later" (the switch would rewrite the controller).
- **Consequences:**
  - The studio's planned hull pose becomes the *target* of a controller, not the actual pose. The studio
    should model the gap (spring/damper tracking error) eventually.
  - Spring, damper and friction gains need tuning per hull mass. Scale them by mass the way Offroad does.

## D2: Legs are Create contraptions

Each leg is a moving Create contraption. Sable already adds contraptions to its physics world as kinematic
bodies (`KinematicContraption`, plus the Sable mixin on `AbstractContraptionEntity`), so legs collide with
and push other sub-levels and entities.

- **Why:** legs that physically interact, at medium cost, using machinery Create and Sable already support.
- **Not chosen:** render-only legs (no collisions) and legs as sub-levels (cost, and joints between
  sub-levels are unstable).
- **Consequences:**
  - The studio walkers should use `implementation.legRepresentation: "contraption"`.
  - Calibrate the `contraption` row of `config/cost-model.json` first.
  - We depend on Create's contraption internals. Read `reference/create` before relying on behaviour.

## D3: Players build legs out of blocks

Leg Actuator (hip) → Leg Struts / Leg Joints → Foot Pad, built in the world. On assembly the mod walks
each chain from the actuator and measures the mount point, segment lengths (2 or 3 segments) and knee
direction. This produces the same leg data as `walkers/*.json` (`mount`, `neutral`, `segments`, `knee`).

- **Why:** the most room for player expression, and a 1:1 mapping onto the studio's spec.
- **Not chosen:** a single configurable leg block (every walker looks the same), and blocks plus a tuning
  GUI (more UI work; gait tuning can arrive later as an extra).

## D4: v1 covers forward/back, turning and start/stop

Walk forward and backward, turn on the spot and while walking, and start and stop smoothly. Strafing and
omnidirectional gaits come later.

- **Consequences:** the studio sim needs these *before* the Java port: yaw rate, turning gaits (inner and
  outer legs take different strides), acceleration and braking, and settling into a stance when stopped.
  Add test courses for them, e.g. a turn in place and a stop.

## D5: Controls reuse Simulated's Steering Wheel and Throttle Lever

The Walker Controller reads Simulated's Steering Wheel and Throttle Lever when they're on the same hull,
including Linked Controller bindings. We build no control UI.

- **Consequences:**
  - We depend on Simulated's control blocks, which ship inside the Aeronautics bundle, so the mod must
    *compile* against them. Extract the bundle's nested jars into `compileOnly` dependencies
    (see `mod/README.md`).
  - Read how they output their values in `reference/simulated-project` (`content/blocks/steering_wheel`,
    `content/blocks/throttle_lever`).

## D6: Balance comes from a Gyro Stabilizer block that costs stress

No built-in balance help. A Gyro Stabilizer block on the hull applies a capped righting torque and draws
Create stress while it works. Statically stable designs (crawl, ripple, tripod with enough legs) don't need
one; trots and bipeds do.

- **Why:** keeps fast-versus-stable a design trade-off the player pays for.
- **Consequences:**
  - The studio's `physics.support: "kinematic"` gets replaced by a stabilizer model: max torque, and stress
    per unit of torque. Stability metrics should report whether the stabilizer can hold the walker, not
    just warn.
  - Simulated has gyroscopic blocks to look at for patterns.

## D7: Shaft RPM sets top speed; the throttle scales from 0 up to it

As in the studio: the RPM driving the Leg Actuators sets the gait cycle (`1200 × revsPerCycle / rpm`
ticks) and so the top speed and the stress. The throttle sets the fraction of top speed, including
stopping. Reverse uses the same speed scale.

## D8: Shared minimum speed stays 2.5 b/s

`config/targets.json` keeps `speedMin: 2.5`. That pushes good designs toward faster gaits, which (per D6)
need a stabilizer. A walker that deliberately trades speed for stability overrides the target in its own
`targets` block and explains why in `proposal.decisions`.

## Follow-up work these decisions create

1. Studio: add turning, reverse, start/stop and acceleration (D4), plus courses for them. *Done: command
   timelines, the `turn` / `startstop` / `reverse` courses, `gait.accel`.*
2. Studio: replace `physics.support` with the stabilizer model (D6), and model suspension tracking error (D1).
   *Done: `physics.stabilizer`, `physics.suspension`.*
3. Studio: switch the example walkers to `legRepresentation: "contraption"` (D2). *Done; it's also the default.*
4. Mod: compile against Simulated's nested jars (D5). *Done: `extractNestedJars` in `mod/build.gradle`;
   `SimulatedControls` reads the Steering Wheel and Throttle Lever (game test `readsSimulatedControls`).*
5. Mod spike: a game test that assembles a hull sub-level and holds it up with spring impulses at four
   points (D1). This is the riskiest piece, so it comes first. *Done, works: the hull holds at 3.02 b (target 3.0)
   level, 0.6° under a heavy corner load, and recovers from a 4 m/s shove to within 0.001 b. See
   `mod/README.md`, "Spike findings".*
6. Mod spike: a Create contraption leg attached to a moving sub-level (D2). *Done, works: a bearing-driven
   arm assembled inside the hull's plot is a colliding Sable kinematic body that follows the hull, and it
   knocks a free block 5.2 b (control without the leg: 0.002 b). When the hull is moved and turned under an
   existing leg, the leg's collision follows it.*
