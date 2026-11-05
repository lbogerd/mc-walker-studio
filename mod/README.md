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
./gradlew build                 # compile + jar in build/libs/
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
- `walker/WalkerControllerBlock` has a horizontal `facing`: the hull's forward (+x in specs), set to the way the
  player looks when placing it.
- `leg/`: the leg blocks of decision D3: `leg_actuator` (hip, `facing` = toward the leg), `leg_strut` (`axis`),
  `leg_joint` (`facing` = knee hint for straight legs) and `foot_pad`, plus `LegParts` to classify a block state.
  All five walker blocks are in the "Walkers" creative tab.
- `assembly/LegScanner`: measures block-built legs on any `BlockGetter` before assembly (see "Building legs").
  `scan(level, controller, walkerBlocks)` returns a `LegScan`: the `HullFrame`, the hull / actuator / per-leg
  partition, one `MeasuredLeg` per actuator, errors and warnings. `collect(level, controller, max)` flood-fills a
  walker's blocks. `WalkerSpecJson.toSpecJson(scan, options)` writes a spec the studio loads.
- Tests: `SuspensionGameTests`, `ContraptionLegGameTests`, `ControlsGameTests`, `SetupGameTests`,
  `LegScannerGameTests` (28 in total).

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
