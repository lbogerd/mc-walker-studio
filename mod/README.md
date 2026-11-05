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

- `walker/WalkerControllerBlockEntity`: a Sable `BlockEntitySubLevelActor`. On every physics tick, each hip
  casts down to the ground. Planted hips push the hull with a spring-damper toward `rideHeight`, with a share
  of the hull's weight as feedforward. A critically damped horizontal spring holds each hip over the spot its
  foot touched down; it acts at center-of-mass height so it doesn't tip the hull. Gains scale with the hull's
  effective mass at each hip, like Offroad's wheels. Tuning lives in `walker/Suspension`.
- `control/SimulatedControls`: reads Simulated's Steering Wheel (`[-1, 1]`) and Throttle Lever (`[0, 1]`).
- `sim/`: the studio's walking loop in plain Java (see "Walking loop port" below). Not wired into the
  block entity yet.
- Tests: `SuspensionGameTests`, `ContraptionLegGameTests`, `ControlsGameTests`, `SetupGameTests` (11 in total).

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

Gotchas found on the way:
- A ray hit on another sub-level comes back in **that sub-level's plot space**. Project it to world space
  (`hitSubLevel.logicalPose().transformPosition`) before using it, or a walker standing on a ship
  sees no ground.
- `Sable.HELPER.getVelocity(level, pos)` (two arguments) **writes the result into `pos`**. Use the
  three-argument overload with a separate destination vector.
- A contraption's kinematic body position (`sable$getPosition()`) is in the parent hull's *plot* space,
  at the contraption's rotating center of mass, not its pivot.
- Applying horizontal foot forces at the hips (below the center of mass) tips the hull against the vertical
  springs. Apply them at center-of-mass height.

## Notes

- **Nested jars**: the Aeronautics release is a bundle (Simulated, Aeronautics and Offroad in nested jars),
  and Sable nests its math API (`sable-companion`) and Veil. `extractNestedJars` unpacks them into
  `build/nested-jars` as `compileOnly`; at runtime NeoForge loads the outer jars.
- **Licences**: Sable is PolyForm Shield and Aeronautics has its own licence. Check both before you
  redistribute anything built on them. Depending on them as separate mods is the normal setup.
- The mod id (`walkers`), name and package (`com.lbogerd.walkers`) are placeholders. Rename them in
  `gradle.properties` and move the package together.
