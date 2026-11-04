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
- Tests: `SuspensionGameTests`, `ContraptionLegGameTests`, `ControlsGameTests`, `SetupGameTests`.

## Spike findings (decisions D1, D2, D5)

| Test | Result |
|---|---|
| Hull on 4 spring hips, ride height 3.0 | settles at 3.02 b, 0.0° tilt |
| Same hull without suspension (control) | falls onto the floor |
| Heavy 4-block load on one corner | 0.57° tilt |
| 4 m/s sideways shove | peaks at 0.23 b, returns to within 0.001 b, level |
| Bearing-driven arm built inside the hull's plot | becomes a colliding Sable kinematic body, rotates, rides along |
| That arm swinging into a free block on a pillar | block knocked 5.2 b (control without the arm: 0.002 b) |
| Simulated Throttle Lever / Steering Wheel | read correctly at runtime |

Gotchas found on the way:
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
