# Walkers (the Minecraft mod)

NeoForge 1.21.1 mod that turns the designs from Walker Studio (the parent folder) into real walkers on top of
Create, Sable sub-levels and Create: Aeronautics.

| | version |
|---|---|
| Minecraft / NeoForge | 1.21.1 / 21.1.265 (ModDevGradle 2.0.148, Gradle 9.2.1) |
| Java | 21 (`openjdk-21-jdk-headless` on the dev box) |
| Create | 6.0.10 |
| Sable | 2.0.6 (Rapier physics native extracts itself into `<gameDir>/.sable/natives`) |
| Create: Aeronautics | 1.3.2 (bundle: Simulated + Aeronautics + Offroad) |

All three come from Modrinth's maven (`maven.modrinth:<slug>:<version>`). The versions live in `gradle.properties`.
Libraries that Create nests in its jar (Ponder, Flywheel, Registrate) come compile-only from Create's own
maven, at the versions in `reference/create/gradle.properties`.

## Commands (all headless unless noted)

```sh
./gradlew build                 # compile + jar in build/libs/ (also runs the unit tests)
./gradlew test                  # JUnit on the plain JVM, no Minecraft: the walking-loop port vs the studio
./gradlew runGameTestServer     # boot a server, run every @GameTest in the "walkers" namespace, exit 1 on failure
./gradlew runServer             # dedicated server in run/server (accept the EULA in run/server/eula.txt first)
./gradlew runData               # datagen into src/generated/resources
./gradlew runClient             # needs a GPU + display; not set up on the dev box yet
```

Game tests are the main feedback loop for agents: put them in `src/main/java/com/lbogerd/walkers/test`,
annotate the class with `@GameTestHolder(Walkers.MOD_ID)`, and use structure templates from
`src/main/resources/data/walkers/structure/`:
- `empty.nbt` is a 3×3×3 air box.
- `arena.nbt` is a 16×14×16 air box. Tests lay their own floor with `SuspensionGameTests.floor`, because
  structures sit one block above the helper's origin.

Things that make physics tests work (all already set up):
- **Tests run near the world origin** (`mixin/GameTestServerMixin`). Vanilla puts them up to 15 million
  blocks out, where Sable's float32 physics can't resolve blocks and hulls fall through the floor.
- **The test world is wiped before every run** (`cleanGameTestWorld`). Sable saves sub-levels, so hulls from
  earlier runs would otherwise collide with new ones.
- Sable already removes sub-levels inside a test's bounds when the test ends.

## Code so far

- `walker/WalkerControllerBlockEntity`: a Create kinetic block and a Sable `BlockEntitySubLevelActor`.
  - **Walk mode** (see "Walking" below): the walking loop plans the hull's motion and the planted legs push it.
  - **Fixed-hip mode** (used by the suspension tests): every physics tick each hip casts down to the ground.
    Planted hips push the hull with a spring-damper toward `rideHeight`, plus a share of its weight. A
    critically damped horizontal spring holds each hip over where its foot touched down.
  - In both modes the horizontal foot force acts **at the hip**, as a real leg would. Gains scale with the
    hull's effective mass at each hip, like Offroad's wheels. Tuning lives in `walker/Suspension`.
- `walker/GyroStabilizerBlock(Entity)`: the Gyro Stabilizer (D6), a Create stress consumer with a shaft
  through it. On every physics tick it turns the hull's up axis back toward world up (PD at 2 Hz, critically
  damped, scaled by the hull's inertia; pitch and roll only). Torque is capped at `TORQUE_PER_RPM × |RPM|`
  (100 N·m per RPM) and is zero when it isn't spinning or the network is overstressed. Stress impact is
  8 SU per RPM, so more RPM buys more torque and costs more stress. Both constants are placeholders
  until calibrated against the studio's `maxTorque` and `stressPerTorque`.
- `control/SimulatedControls`: reads Simulated's Steering Wheel (`[-1, 1]`) and Throttle Lever (`[0, 1]`).
- `walker/WalkerControllerBlock` has a horizontal `facing`: the hull's forward (+x in specs), set to the way the
  player looks when placing it.
- `leg/`: the leg blocks of decision D3: `leg_actuator` (hip, `facing` = toward the leg), `leg_strut` (`axis`),
  `leg_joint` (`facing` = knee hint for straight legs) and `foot_pad`, plus `LegParts` to classify a block state.
  All five walker blocks are in the "Walkers" creative tab.
- `assembly/LegScanner`: measures block-built legs on any `BlockGetter` before assembly (see "Building legs").
  `scan(level, controller, walkerBlocks)` returns a `LegScan`: the `HullFrame`, the hull / actuator / per-leg
  partition, one `MeasuredLeg` per actuator, errors and warnings. `collect(level, controller, max)` flood-fills a
  walker's blocks. `WalkerSpecJson.toSpecJson(scan, options)` writes a spec the studio loads.
- `sim/`: the studio's walking loop in plain Java (see "Walking loop port" below).
- `walker/WalkDriver`, `WalkFrame`, `WorldTerrain`: walk mode (see "Walking" below).
- Tests: `SuspensionGameTests`, `ContraptionLegGameTests`, `ControlsGameTests`, `SetupGameTests`,
  `LegScannerGameTests`, `GyroStabilizerGameTests`, `WalkingGameTests` (41 game tests), plus JUnit tests for `sim/` (`./gradlew test`).

## Building legs (decision D3)

A leg is a chain of blocks: **Leg Actuator → Leg Struts / Leg Joints → Foot Pad**.

- Put the **Leg Actuator** against the hull; it faces away from the face you click. The chain starts at the
  block in front of it. The actuator stays on the hull (like a Mechanical Bearing); the chain is the leg.
- Struts and joints connect through faces. The chain must not fork, loop, or touch anything except its own
  actuator and, under the Foot Pad, the ground: no other leg, no hull block, no world block.
- **1 Leg Joint** makes a 2-segment leg `[upper, lower]`; **2 joints** make `[coxa, upper, lower]`.

How `LegScanner` measures it (all in hull axes, as in `docs/walker-spec.md`):

| | rule |
|---|---|
| forward (+x) | the Walker Controller's `facing`; +z is to its right, +y up |
| origin | centre of the bottom face of the hull blocks' bounding box (hull = walker blocks that aren't leg parts; actuators left out) |
| pivots | block centres: actuator, each joint in chain order, foot pad |
| `mount` | the actuator's centre |
| `segments` | straight distances between consecutive pivots. A segment is one rigid body, so an L-shaped or staircase run of struts is a bent link measured end to end; straight runs give integers (actuator, 2 struts, joint = 3) |
| `neutral` | foot pad centre minus mount, `[dx, dz]` |
| `knee` | the knee joint's (last joint's) offset from the line base → foot (base = actuator, or the first joint with a coxa). Over ¼ block: the studio hint whose IK pole points closest to the offset. A straight leg: the Leg Joint's `facing` instead (place it looking the way the knee should bend; along the leg it says nothing, and you get a warning) |
| ids | per side (L = −z, R = +z, C = centre line), front to back: `F`,`H` / `F`,`M`,`H` / `F`,`M1`…,`H`, or just `L`/`R` for one leg per side. A right leg that exactly mirrors the left leg of the same rank gets `mirrorOf` and is written as `"mirror": true` |
| `body` | `size` = hull bounding box `[forward, up, sideways]`; `blocks` = hull blocks without actuators (the studio adds hips, joints, struts and feet from its bill of materials); `rideHeight` = hull bottom above the foot points as built, unless overridden |

```
 side view, forward →            measured (hull 8×3×6, forward = east)
 y=8  [hull ............. hull]
 y=7     A(hind)        A(front)  mount  [3.5, -0.5, -2.5]   (front left)
 y=6     s              s
 y=5     s              s         upper  7.5 - 4.5 = 3
 y=4     J→             ←J        knee   "back" (joint faces back; leg is straight)
 y=3     s              s
 y=2     s              s         lower  4.5 - 1.5 = 3
 y=1     F              F         neutral [0, 0], rideHeight as built 6.5
 y=0  ground
```

**The foot point is the Foot Pad's centre**, so in the world the pad's centre sits half a block above the ground the
studio sees (the studio puts the foot point on the ground). The walking loop should aim the pad's centre at
`ground + 0.5`. Legs built straight are at full reach as built; pass a lower `rideHeight` to `WalkerSpecJson.Options`.

**Open a scanned walker in the studio.** `LegScannerGameTests` writes `run/gametest/walker-scans/scanned-quad.json`
and `scanned-hexapod-pair.json`. Copy one to `walkers/<id>.json` and run `npm run check -- <id>` or open
`http://localhost:5177/?walkers=<id>`; or paste it into the page with `await walkerStudio.load('scan', spec)`.

## Walking loop port (`com.lbogerd.walkers.sim`)

`com.lbogerd.walkers.sim` mirrors `src/sim` in the studio. After changing either, run `npm run fixtures`
(in the repo root) and `cd mod && ./gradlew test`. The package uses only the JDK and Gson (no Minecraft,
Sable, Create or JOML), so it runs in plain JUnit.

| Java | mirrors |
|---|---|
| `SimMath` | `math.js` (vectors are `double[3]`, poses are `SimMath.Pose`) |
| `Terrain` | the terrain object: `int column(ix, iz)` + `heightAt(x, z)`; back it with the world in the mod |
| `Scenarios` | `terrain.js`: courses, command timelines, `commandAt`, `makeTerrain(name, seed)` |
| `SpecNormalizer`, `WalkerSpec` | `spec.js`: `normalizeSpec` (same errors and warnings), `restWindows` |
| `PathPlanner` | `planPath` (incremental: one `step(command)` per tick; `predict`, `rateAt`, `timeAtPhase`) |
| `GaitController` | the per-tick part of `simulate` (footholds, swing, body pose, sway, suspension, IK) |
| `Simulate` | `simulate` itself (measurements, events, series, summary) = a `GaitController` fed a timeline |
| `Evaluate` | `stress`, `totalStress`, `stabilizerHolds` from `evaluate.js` (targets and cost model stay JS-only) |

The live controller is causal: it never sees future commands.

```java
WalkerSpec spec = SpecNormalizer.normalizeSpec(json, "quad-strider");     // check spec.errors first
GaitController ctl = new GaitController(spec, terrain, Scenarios.Command.STOP, 0); // runs the warm-up
GaitController.TickState s = ctl.tick(new Scenarios.Command(throttle, steer));  // one server tick
s.planned();  s.actual();            // planned hull pose / suspension-follower pose (pos, yaw, pitch, roll)
s.legs().get(i).st().stance();       // planted or swinging; .target() = foot target, .fh()/.next() = footholds
s.legs().get(i).foot();              // IK result in world space (also hip, base, knee)
ctl.decidedFootholds(i);             // every foothold decided so far for leg i
```

Positions are in the walker's own course frame: it starts at (startX, 0) facing +x with yaw 0, and +yaw turns
left (toward -z). The mod has to map that frame onto the world (and the hull sub-level) itself.

**Fixtures.** `tools/export-fixtures.mjs` writes `src/test/resources/sim-fixtures/<walker>__<course>.json.gz`
for every walker × course (frames for every tick 0–600, every per-tick series, all footholds, events and the
summary), plus `spec-cases.json.gz` (hand-written valid and invalid specs, for `normalizeSpec`'s messages).
Numbers are rounded to 9 decimals; about 2.5 MB in total. `SimFixtureTest` re-simulates each one and fails
on the first tick, leg and field that differs by more than 1e-6 (discrete values must match exactly).

**Numerics.** JS numbers and Java doubles are both IEEE-754, so arithmetic matches exactly. For the rest:
`StrictMath` (fdlibm, like V8) for sin/cos/atan/atan2/acos, and `SimMath.hypot` ports V8's `Math.hypot`
(`java.lang.Math.hypot` rounds differently). Use them in any new code here. The port is then bit-exact: the
observed deviation is the fixtures' 5e-10 rounding. Other traps the port handles: `Math.round` (JS rounds
halves up), `toFixed` and number-to-string in messages (`SimMath.toFixed`, `jsString`), sort comparators
(`a - b`, where -0 equals 0), `%` (same in both), and `Infinity` (fixtures store it as a string).

## Walking (steps 2 and 4: decisions D1, D4, D5, D7)

`WalkerControllerBlockEntity.configureWalker(specJson, originPlot, forward, suspension)` switches a controller to
walk mode. `specJson` is a walkers/*.json spec, and `originPlot` is the hull's bottom-face centre in plot space.

- **Every server tick:** the controller turns its controls into a command, and `WalkDriver` advances the ported
  walking loop (`sim.GaitController`). The loop is causal, so footholds are decided at lift-off from what's
  known then. On its first tick the driver fixes the walker's course frame in the world (`WalkFrame`).
  - **The shaft drives the legs (D7):** the loop runs |RPM| ÷ the spec's `drive.rpm` design ticks per server
    tick, accumulated. Twice the RPM halves the gait cycle and doubles the top speed, and stress doubles with
    it. No rotation freezes the legs where they are.
  - **History is trimmed** every 200 gait ticks (`GaitController.trimHistory`), so memory stays bounded. This
    doesn't change any result.
  - **`WorldTerrain` feeds it the real ground:** heights at the exact world point under each foot, found by
    scanning **down from the hull's bottom**, so roofs and bridges above the walker aren't taken for ground.
- **Every physics tick:** each **planted** leg pushes its hip toward the plan's hip position. It uses a
  spring-damper toward the planned position and velocity, plus a share of the weight. Legs push but never pull,
  and a friction cone limits the horizontal force (μ = 1). Swinging legs don't push. A stance leg only counts
  if its foothold is within the leg's reach of the **actual** hip and its ground block still exists. So the
  hull is only held where feet are really planted, and an unstable gait needs a Gyro Stabilizer.
- **Controls (D5, D7):** the controller is a Create kinetic block. Its shaft goes into the **back** face.
  - A Creative Motor set to a positive speed walks forward. The direction is relative to the controller's
    facing; reverse the shaft to back up.
  - throttle = Throttle Lever (0–1, full if there's none) × direction. RPM sets the gait's speed (above), not
    the throttle.
  - steer = Steering Wheel angle ÷ its max angle (−1–1). Positive turns left, as in the studio.
  - No rotation, or an overstressed network, means the walker stands.
  - Stress impact per RPM comes from the spec: legs × `stressPerLegPerRpm` + mass × `stressPerBlockPerRpm`.
- **Not saved:** the gait itself. After a reload the walker restarts standing wherever its hull is.

| Walking test (crawling quad, 5×5 hull) | Result |
|---|---|
| Walk forward 11 s | 11.54 b against a plan of 11.57 b; 0.16 b off the plan, height within 0.05 b |
| Turn in place | 158° against a plan of 157°, 0.69 b drift |
| Walk, then stop | stops where the plan stops (0.00002 b/s), 4 feet planted |
| Motor at the design RPM (32), full lever | 10.2 b forward in 10 s |
| Half the design RPM | 4.9 b (half speed; gait clock at 0.5×) |
| Twice the design RPM | 20.9 b (double speed; gait clock at 2×) |
| Lever at 0 | stands still |
| Reversed shaft | 10.5 b backward |
| Steering Wheel held at full lock | steer 1.0, turns 139° left in place |
| Stress | 19.1 SU/RPM, matching the spec formula; Create's network reports 611 SU at 32 RPM; clients see the impact |
| Ground removed under a standing walker | falls into the hole (no leg counts as planted) |
| Hull shoved 7 b sideways, beyond leg reach | falls; isn't pulled back |
| Walking under a ceiling 5 blocks above the hull | rides at 3.00 b, not on the ceiling |
| Course frame half a block off the grid | heights read from the right world column |

**Finding: a plan can ask more than the feet can give.** With full hull sway (`comShift: 1`) the plan needed
~30 b/s² of sideways acceleration. Planted feet couldn't push that hard (friction), so the real hull swung up to
±1.9 b and ±40° around the plan. With `comShift: 0.4` (12 b/s²) it tracked within 0.16 b. The studio now
reports this as "Peak hull acceleration" (target 20 b/s², provisional).

## Spike findings (decisions D1, D2, D5)

| Test | Result |
|---|---|
| Hull on 4 spring hips, ride height 3.0 | settles at 3.02 b, 0.0° tilt |
| Same hull standing on another sub-level (a 9×9 platform) | 3.02 b above the platform, level |
| Same hull without suspension (control) | falls onto the floor |
| Heavy 4-block load on one corner | 0.57° tilt |
| 4 m/s sideways shove | peaks at 0.23 b, returns to within 0.001 b, level |
| Bearing-driven arm built inside the hull's plot | becomes a colliding Sable kinematic body, rotates, rides along |
| That arm swinging into a free block on a pillar | block knocked 5.2 b (control without the arm: 0.002 b) |
| Arm built, then the hull turned 90° and moved 3 b | block in the arm's new swing path knocked 4.7 b; block in its old path: 0.002 b |
| Simulated Throttle Lever / Steering Wheel | read correctly at runtime |
| Two-legged hull with a sustained side load, no gyro | tips over (50°) |
| Same, gyro at 1 RPM (100 N·m cap) | saturates and tips over (50°) |
| Same, gyro at 16 RPM (1600 N·m cap) | stays upright (0.13°, using 268 N·m) and draws 128 SU |

Gotchas found on the way:
- A ray hit on another sub-level comes back in **that sub-level's plot space**. Project it to world space
  (`hitSubLevel.logicalPose().transformPosition`) before using it, or a walker standing on a ship
  sees no ground.
- `Sable.HELPER.getVelocity(level, pos)` (two arguments) **writes the result into `pos`**. Use the
  three-argument overload with a separate destination vector.
- A contraption's kinematic body position (`sable$getPosition()`) is in the parent hull's *plot* space,
  at the contraption's rotating center of mass, not its pivot.
- Apply the planted feet's horizontal force **at the hips**. An earlier version applied it at center-of-mass
  height to fix a tilt, but that tilt really came from stale hulls in the test world. Applying it at the
  center of mass holds the center of mass over the feet and makes any walker statically stable, so a biped
  would never need a Gyro Stabilizer.

## Notes

- **Nested jars**: the Aeronautics release is a bundle (Simulated, Aeronautics and Offroad in nested jars),
  and Sable nests its math API (`sable-companion`) and Veil. `extractNestedJars` unpacks them into
  `build/nested-jars` as `compileOnly`; at runtime NeoForge loads the outer jars.
- **Licences**: Sable is PolyForm Shield and Aeronautics has its own licence. Check both before you
  redistribute anything built on them. Depending on them as separate mods is the normal setup.
- The mod id (`walkers`), name and package (`com.lbogerd.walkers`) are placeholders. Rename them in
  `gradle.properties` and move the package together.
