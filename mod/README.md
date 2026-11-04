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
`src/main/resources/data/walkers/structure/` (`empty.nbt` is a 3×3×3 air box).

## Notes

- **Aeronautics API**: the release jar is a bundle with its classes in nested jars, so it's `runtimeOnly`.
  To compile against Simulated/Aeronautics/Offroad, extract those nested jars into `compileOnly` dependencies.
  Sable and Create are on the compile classpath already.
- **Licences**: Sable is PolyForm Shield and Aeronautics has its own licence. Check both before you
  redistribute anything built on them. Depending on them as separate mods is the normal setup.
- The mod id (`walkers`), name and package (`com.lbogerd.walkers`) are placeholders. Rename them in
  `gradle.properties` and move the package together.
